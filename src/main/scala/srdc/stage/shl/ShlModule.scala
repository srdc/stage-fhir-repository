package srdc.stage.shl

import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ExceptionHandler, Route}
import akka.http.scaladsl.model.EntityStreamSizeException
import org.slf4j.LoggerFactory

/**
 * The SMART Health Links server of this repository (formerly the Node shl-server), served at <server>/shl
 * (Onfhir.apply(..., cdsRoute = ...), see srdc.stage.fhir.Boot); behind a proxy, publish it with shl.public-base-url,
 * e.g. https://kroniq.srdc.com.tr/stage-shl -> <server>/:
 *
 *   POST   /shl/create           create a link (SHL payload, shlink:/ URI and QR code); also POST /shl
 *   POST   /shl/:id/m            manifest request {recipient, passcode?, embeddedLengthMax?}
 *   GET    /shl/:id/m?recipient  direct file of a U flag link (application/jose)
 *   GET    /shl/:id/f/:ticket    file `location` of a manifest, valid for a limited time (application/jose)
 *   DELETE /shl/:id              revoke the link (Authorization: Bearer <managementToken>, or the owner's access token)
 *   GET    /shl/owned            links bound to the caller (Authorization: Bearer <access token>); metadata only
 *
 * Creating, listing and revoking links are not defined by the SHL specification (only the receiver side is).
 * Links are stored in the repository's MongoDB database (ShlConfig.collection); file locations are kept in memory.
 */
object ShlModule {
  private val logger = LoggerFactory.getLogger(this.getClass)
  private val BearerPattern = "(?i)^Bearer\\s+(\\S+)$".r

  /** The SHL route; call once, after onFHIR has been initialized (the links are stored in its database) */
  def route(implicit system: ActorSystem): Route = {
    if (!ShlConfig.enabled) {
      logger.info("SMART Health Links are disabled (shl.enabled = false)")
      return reject
    }
    implicit val ec = system.dispatcher
    val store = new ShlStore(ShlConfig.collection)
    store.createIndexes()
    val service = new ShlService(store)
    logger.info(s"SMART Health Links at /${ShlConfig.basePath}, links use ${ShlConfig.publicBaseUrl.getOrElse("the request host")}, " +
      s"stored in ${ShlConfig.collection}; link owners ${if (service.ownersEnabled) "enabled" else "disabled"}")

    val bearer = optionalHeaderValueByName("Authorization").map(_.collect { case BearerPattern(token) => token })
    val baseUrl = extractRequest.map(publicBaseUrl)
    val body = withSizeLimit(ShlConfig.bodyLimit) & entity(as[String])
    val tooLarge = ExceptionHandler {
      case _: EntityStreamSizeException =>
        complete(ShlService.errorResponse(ShlError(StatusCodes.PayloadTooLarge, s"Request body larger than ${ShlConfig.bodyLimit} bytes.")))
    }

    val endpoints = concat(
      (pathEndOrSingleSlash | path("create")) {
        post {
          (body & bearer & baseUrl) { (request, token, base) => complete(service.create(request, token, base)) }
        }
      },
      path("owned") {
        get {
          bearer(token => complete(service.owned(token)))
        }
      },
      path(Segment / "m") { id =>
        concat(
          post {
            (body & baseUrl) { (request, base) => complete(service.manifest(id, request, base)) }
          },
          get {
            parameter("recipient".optional)(recipient => complete(service.directFile(id, recipient)))
          }
        )
      },
      path(Segment / "f" / Segment) { (id, ticket) =>
        get {
          complete(service.locatedFile(id, ticket))
        }
      },
      path(Segment) { id =>
        delete {
          bearer(token => complete(service.revoke(id, token)))
        }
      }
    )

    pathPrefix(separateOnSlashes(ShlConfig.basePath)) {
      cors(handleExceptions(tooLarge)(endpoints))
    }
  }

  /**
   * CORS for SHL receivers and apps on other origins (onFHIR's CORS handling covers the FHIR API only): any origin, no
   * credentials (links and management tokens are bearer secrets); preflight requests are answered here
   */
  private def cors(inner: Route): Route =
    respondWithHeaders(`Access-Control-Allow-Origin`.*) {
      options {
        optionalHeaderValueByName("Access-Control-Request-Headers") { requested =>
          complete(HttpResponse(StatusCodes.NoContent, List(
            `Access-Control-Allow-Methods`(HttpMethods.GET, HttpMethods.HEAD, HttpMethods.POST, HttpMethods.DELETE, HttpMethods.OPTIONS),
            RawHeader("Access-Control-Allow-Headers", requested.filter(_.trim.nonEmpty).getOrElse("Content-Type, Authorization")),
            `Access-Control-Max-Age`(86400)
          )))
        }
      } ~ inner
    }

  /** shl.public-base-url, else the scheme and host of the request (X-Forwarded-Proto/-Host behind a proxy) */
  private def publicBaseUrl(request: HttpRequest): String = ShlConfig.publicBaseUrl.getOrElse {
    def header(name: String) = request.headers.find(_.is(name)).map(_.value.split(',').head.trim).filter(_.nonEmpty)
    val scheme = header("x-forwarded-proto").getOrElse(request.uri.scheme)
    val host = header("x-forwarded-host").getOrElse(request.uri.authority.toString)
    s"$scheme://$host"
  }
}
