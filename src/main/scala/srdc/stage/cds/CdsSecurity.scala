package srdc.stage.cds

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import io.onfhir.cds.server.CdsCorsHandler
import io.onfhir.config.OnfhirConfig
import org.json4s._
import org.json4s.jackson.JsonMethods.{compact, parse, render}
import org.slf4j.{Logger, LoggerFactory}
import srdc.stage.cds.config.CdsConfig

import java.util.regex.Pattern
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

/**
 * Access control of the CDS Hooks service calls (POST <basePath>/cds-services/{id}).
 *
 * The CDS services run inside the FHIR server, so a service call can expose patient data in two ways: the prefetches
 * that the caller does not supply are fetched from this server, and the services call other backends (e.g.
 * onfhir-feast) for the patient given in the context. Both use the rights of the FHIR API token of the caller:
 *
 *  - The token is the SMART access token that the apps send in the request (fhirAuthorization.access_token, as in the
 *    CDS Hooks specification), or else an "Authorization: Bearer" header.
 *  - If the context has a patientId, the token must be able to read Patient/{patientId} from the FHIR API of this
 *    server (checked with a FHIR read, so the onFHIR authorization decides: a patient token for that patient, or a
 *    user/system token that can read patients).
 *  - The missing prefetches are fetched from the FHIR API of this server with the token (fhirServer is set to the
 *    local FHIR URL), never with the unauthenticated in-process client. A fhirServer other than this server is refused.
 *  - Calls that need neither (no patientId, every prefetch supplied: e.g. definition, valueset, in-memory-search)
 *    only compute on the given data and need no token.
 *
 * The discovery endpoint (GET <basePath>/cds-services) is public, as in the CDS Hooks specification.
 *
 * Enabled unless the FHIR API itself is open (fhir.authorization.method = none); stage.cds.security.enabled overrides.
 */
object CdsSecurity extends CdsCorsHandler {
  private val logger: Logger = LoggerFactory.getLogger(this.getClass)

  private val securityConfig = Try(CdsConfig.config.getConfig("stage.cds.security")).toOption

  /** If the CDS service calls are checked */
  lazy val enabled: Boolean =
    securityConfig.flatMap(c => Try(c.getBoolean("enabled")).toOption)
      .getOrElse(OnfhirConfig.authzConfig.isSecure())

  /** The FHIR API of this server as reached from inside the server (for the patient check and the prefetch reads) */
  lazy val localFhirUrl: String =
    securityConfig.flatMap(c => Try(c.getString("local-fhir-url")).toOption)
      .getOrElse(s"${OnfhirConfig.serverProtocol}://127.0.0.1:${OnfhirConfig.serverPort}/${OnfhirConfig.baseUri}")
      .stripSuffix("/")

  /** fhirServer values that mean this server: the root URL and the configured aliases (e.g. other host names) */
  lazy val ownFhirUrls: Set[String] =
    (Seq(OnfhirConfig.fhirRootUrl, localFhirUrl) ++
      securityConfig.flatMap(c => Try(c.getStringList("fhir-server-aliases").asScala.toSeq).toOption).getOrElse(Nil))
      .map(normalize).toSet

  private def normalize(url: String): String = url.trim.stripSuffix("/").toLowerCase

  private val FhirId = "^[A-Za-z0-9\\-.]{1,64}$".r

  /**
   * Wraps the CDS Hooks route with the access control (if enabled)
   *
   * @param basePath Path segment of the CDS Hooks endpoints
   * @param cdsRoute The onfhir-cds route
   */
  def secure(basePath: String)(cdsRoute: Route)(implicit system: ActorSystem): Route = {
    if (!enabled) {
      logger.warn("The CDS Hooks service calls are not access controlled (stage.cds.security.enabled = false or fhir.authorization.method = none)!")
      cdsRoute
    } else {
      logger.info(s"CDS Hooks service calls use the FHIR API rights of the caller's token (local FHIR API: $localFhirUrl)")
      // No route alternatives (~): a service call must never fall through to the unchecked route
      val ServiceCallPath = s"^/${Pattern.quote(basePath.stripPrefix("/").stripSuffix("/"))}/cds-services/([^/]+)/?$$".r
      ctx =>
        (ctx.request.method, ctx.unmatchedPath.toString) match {
          case (HttpMethods.POST, ServiceCallPath(serviceId)) =>
            // Strict, so that the body can be read here and again by onfhir-cds
            toStrictEntity(30.seconds)(checkServiceCall(serviceId, cdsRoute))(ctx)
          case _ => cdsRoute(ctx)
        }
    }
  }

  private def checkServiceCall(serviceId: String, cdsRoute: Route)(implicit system: ActorSystem): Route =
    CdsConfig.cdsServiceDefinitions.get(serviceId) match {
      case None => cdsRoute // Unknown service: onfhir-cds answers
      case Some(serviceDefinition) =>
        (extractRequest & entity(as[String])) { (request, body) =>
          Try(parse(body)).toOption.collect { case o: JObject => o } match {
            case None => cdsRoute // Not a JSON object: onfhir-cds answers with the error
            case Some(cdsRequest) =>
              val token = getString(cdsRequest \ "fhirAuthorization" \ "access_token")
                .orElse(request.header[Authorization].collect { case Authorization(OAuth2BearerToken(t)) => t })
              val patientId = getString(cdsRequest \ "context" \ "patientId")
              val suppliedPrefetches = (cdsRequest \ "prefetch") match {
                case JObject(fields) => fields.filter(f => f._2 != JNull && f._2 != JNothing).map(_._1).toSet
                case _ => Set.empty[String]
              }
              val missingPrefetches = serviceDefinition.prefetch.keySet.diff(suppliedPrefetches)
              val fhirServer = getString(cdsRequest \ "fhirServer")

              if (patientId.isEmpty && missingPrefetches.isEmpty)
                cdsRoute // Only computes on the supplied data
              else if (token.isEmpty)
                failWith(StatusCodes.Unauthorized, s"The CDS service $serviceId needs patient data: send the SMART access token of the app in fhirAuthorization.access_token (or as a Bearer token)!")
              else if (fhirServer.exists(s => !ownFhirUrls.contains(normalize(s))))
                failWith(StatusCodes.BadRequest, s"fhirServer must be this FHIR server (${OnfhirConfig.fhirRootUrl})!")
              else if (patientId.exists(FhirId.findFirstIn(_).isEmpty))
                failWith(StatusCodes.BadRequest, "Invalid patientId in the context!")
              else
                onComplete(patientId.map(canReadPatient(_, token.get)).getOrElse(Future.successful(StatusCodes.OK))) {
                  case Success(StatusCodes.OK) =>
                    // Fetch the missing prefetches from the FHIR API with the token
                    val securedRequest = JObject(
                      cdsRequest.obj.filterNot(f => f._1 == "fhirServer" || f._1 == "fhirAuthorization") ++ List(
                        "fhirServer" -> JString(localFhirUrl),
                        "fhirAuthorization" -> fhirAuthorization(cdsRequest \ "fhirAuthorization", token.get)
                      ))
                    mapRequest(_.withEntity(HttpEntity(ContentTypes.`application/json`, compact(render(securedRequest))))) {
                      cdsRoute
                    }
                  case Success(StatusCodes.Unauthorized) =>
                    failWith(StatusCodes.Unauthorized, "The access token is not valid (or has expired)!")
                  case Success(status) =>
                    logger.debug(s"CDS service $serviceId refused for Patient/${patientId.get}: the FHIR read returned $status")
                    failWith(StatusCodes.Forbidden, s"The access token does not give access to Patient/${patientId.get}!")
                  case Failure(t) =>
                    logger.error(s"Patient access check failed for the CDS service $serviceId (local FHIR API: $localFhirUrl)", t)
                    failWith(StatusCodes.ServiceUnavailable, "The patient access could not be checked!")
                }
          }
        }
    }

  /**
   * Status of reading Patient/{patientId} from the FHIR API of this server with the given token (the onFHIR
   * authorization decides). 200 means the token gives access to the patient, 401 that the token is not valid.
   */
  private def canReadPatient(patientId: String, token: String)(implicit system: ActorSystem): Future[StatusCode] = {
    import system.dispatcher
    Http()
      .singleRequest(HttpRequest(
        uri = s"$localFhirUrl/Patient/$patientId?_elements=id",
        headers = List(Authorization(OAuth2BearerToken(token)))
      ))
      .map { response =>
        response.discardEntityBytes()
        // onFHIR answers 401 also when the token is valid but gives no access (error=insufficient_scope)
        val insufficientScope = response.headers.exists(h =>
          h.is("www-authenticate") && h.value.contains("insufficient_scope"))
        if (response.status == StatusCodes.Unauthorized && insufficientScope) StatusCodes.Forbidden else response.status
      }
  }

  /** The fhirAuthorization of the request with the given token (all fields are required by onfhir-cds) */
  private def fhirAuthorization(given: JValue, token: String): JObject = JObject(
    "access_token" -> JString(token),
    "token_type" -> JString(getString(given \ "token_type").getOrElse("Bearer")),
    "expires_in" -> (given \ "expires_in" match {
      case JNothing | JNull => JInt(300)
      case v => v
    }),
    "scope" -> JString(getString(given \ "scope").getOrElse("")),
    "subject" -> JString(getString(given \ "subject").getOrElse(""))
  )

  private def getString(v: JValue): Option[String] = v match {
    case JString(s) if s.nonEmpty => Some(s)
    case _ => None
  }

  private def failWith(status: StatusCode, message: String): Route =
    complete(addCORSHeaders(HttpResponse(
      status,
      entity = HttpEntity(ContentTypes.`application/json`, compact(render(JObject("error" -> JString(message)))))
    )))
}
