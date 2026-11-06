package srdc.stage.fhir.service

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import akka.http.scaladsl.unmarshalling.Unmarshal
import io.onfhir.Onfhir
import io.onfhir.authz.TokenClient
import io.onfhir.config.OnfhirConfig
import io.onfhir.exception.InternalServerException
import io.onfhir.util.JsonFormatter.formats
import org.json4s.native.JsonMethods
import org.json4s.native.JsonMethods.compact
import org.json4s.{JArray, JBool, JField, JObject, JString, JValue}
import org.slf4j.{Logger, LoggerFactory}
import srdc.stage.fhir.config.StageConfig
import srdc.stage.fhir.util.Json4sFormatter.parseFromJson
import org.json4s.JsonDSL._

import scala.collection.mutable.ListBuffer
import scala.concurrent.Future

object OnauthService {

  private implicit val actorSystem: ActorSystem = Onfhir.actorSystem
  implicit val executionContext = actorSystem.dispatcher

  private val logger: Logger = LoggerFactory.getLogger("OnauthService")

  private val authzConfig = OnfhirConfig.authzConfig
  private val tokenClient = if (isSecure) new TokenClient(authzConfig.protectedResourceInformation.getID.getValue,
    authzConfig.protectedResourceInformation.getSecret.getValue, Seq("user.create", "user/*.*", "user/*/profile.*", "user/*/roles/*.delete", "user/*/details.*", "user/*/roles/*.*", "user/*/password.*"),
    authzConfig.authzServerMetadata) else null

  def isSecure: Boolean = authzConfig != null && authzConfig.isSecure

  def getToken(): String = {
    tokenClient.getToken match {
      case Some(token: String) => token
      case _ => throw new InternalServerException("Authentication exception")
    }
  }

  def getCreateUserRequestObject(ID: String, username: String, email: Option[String], password: String, family: String,
                                 given: Seq[String], realm: Seq[String]): JObject = {
    val request = JObject(
      "sub" -> JString(ID),
      "account" -> JObject(
        "username" -> JString(username),
        "login_method" -> JString("password"),
        "enabled" -> JBool(true),
        "email_verified" -> JBool(true),
        "phone_verified" -> JBool(false)
      ),
      "credentials" -> JObject("password" -> JString(password)),
      "profile" -> JObject(
        "family_name" -> JString(family),
        "given_name" -> JString(given.mkString(","))
      ),
      "roles" -> JArray(realm.map(JString(_)).toList)
    )
    email match {
      case Some(value) => (request transformField { case JField("account", account: JObject) => ("account", account ~ ("email" -> value)) }).asInstanceOf[JObject]
      case _ => request
    }
  }

  def createUser(userObject: JObject, token: String): Future[Option[(StatusCode, JObject)]] = {
    val responseFuture: Future[HttpResponse] = Http().singleRequest(HttpRequest(method = HttpMethods.POST,
      uri = s"${StageConfig.AUTHZ_SERVER_URL}/pm/user")
      .withHeaders(Authorization.apply(OAuth2BearerToken(token)))
      .withEntity(HttpEntity.apply(ContentTypes.`application/json`, compact(JsonMethods.render(userObject)))))

    responseFuture flatMap[Option[(StatusCode, JObject)]] {
      case HttpResponse(StatusCodes.Created, _, _, _) =>
        Future { None }
      case HttpResponse(errorCode, _, responseEntity, _) =>
        parseEntity(responseEntity) map {
          errorResponse => Some(errorCode, errorResponse)
        }
    }
  }

  def updateUser(id: String, token: String, email: Option[String], password: Option[String], isTemporary: Option[Boolean], given: List[String],
                 family: Option[String], addRoles: Seq[String], removeRoles: Seq[String]): Future[Option[(StatusCode, JObject)]] = {
    getUser(id, token) flatMap {
      case (Some(response), _, _) => {
        val futures = Seq(
          email.map(_email => updateAccountDetails((response \ "account").asInstanceOf[JObject] merge JObject("email" -> JString(_email)), id, token)).getOrElse(Future.apply(None)),
          password.map(_password => changePassword(_password, isTemporary.getOrElse(false), id, token)).getOrElse(Future.apply(None)),
          if (family.nonEmpty || given.nonEmpty) {
            val listBuffer = new ListBuffer[(String, JValue)]()
            if (family.nonEmpty) listBuffer += ("family_name" -> JString(family.get))
            if (given.nonEmpty) listBuffer += ("given_name" -> JString(given.mkString(" ")))
            updateAccountProfile((response \ "profile").asInstanceOf[JObject] merge JObject(listBuffer.toList), id, token)
          } else Future.apply(None),
          updateRoles((response \ "roles").extract[List[String]], addRoles, removeRoles, id, token)
        )
        Future.sequence(futures) map { results =>
          if (results.exists(_.nonEmpty)) {
            val error = results.find(_.nonEmpty).head.get
            logger.error(s"Cannot update user $id: ${error._1} - ${error._2.toString}")
            Some(error)
          } else {
            None
          }
        }
      }
      case (None, Some(statusCode), Some(errorResponse)) => {
        logger.error(s"Error while reading user $id, $statusCode - ${errorResponse.toString}")
        Future.apply(Some(statusCode, errorResponse))
      }
    }
  }

  def getUser(id: String, token: String): Future[(Option[JObject], Option[StatusCode], Option[JObject])] = {
    val responseFuture: Future[HttpResponse] = Http().singleRequest(HttpRequest(method = HttpMethods.GET,
      uri = s"${StageConfig.AUTHZ_SERVER_URL}/pm/user/$id")
      .withHeaders(Authorization.apply(OAuth2BearerToken(token))))

    responseFuture flatMap {
      case HttpResponse(StatusCodes.OK, _, responseEntity, _) =>
        Unmarshal(responseEntity).to[String] map {
          responseString: String => {
            val response = responseString.parseJson.asInstanceOf[JObject]
            (Some(response), None, None)
          }
        }
      case HttpResponse(errorCode, _, responseEntity, _) =>
        parseEntity(responseEntity) map { errorResponse =>
          (None, Some(errorCode), Some(errorResponse))
        }
    }
  }

  def updateRoles(existingRoles: Seq[String], addRoles: Seq[String], removeRoles: Seq[String], id: String, token: String): Future[Option[(StatusCode, JObject)]] = {
    val newRoles = addRoles.filter(role => !existingRoles.contains(role))
    val removedRoles = removeRoles.filter(role => existingRoles.contains(role))
    val createRequests = newRoles map { role =>
      Http().singleRequest(HttpRequest(method = HttpMethods.PUT,
        uri = s"${StageConfig.AUTHZ_SERVER_URL}/pm/user/$id/roles/$role")
        .withHeaders(Authorization.apply(OAuth2BearerToken(token)))) flatMap {
        case HttpResponse(StatusCodes.OK, _, _, _) =>
          Future { None }
        case HttpResponse(errorCode, _, responseEntity, _) =>
          parseEntity(responseEntity) map { errorResponse =>
            logger.error(s"Error while adding role $role to $id: $errorCode - ${errorResponse.toString}")
            Some(errorCode, errorResponse)
          }
      }
    }
    val deleteRequests = removedRoles map { role =>
      Http().singleRequest(HttpRequest(method = HttpMethods.DELETE,
        uri = s"${StageConfig.AUTHZ_SERVER_URL}/pm/user/$id/roles/$role")
        .withHeaders(Authorization.apply(OAuth2BearerToken(token)))) flatMap {
        case HttpResponse(StatusCodes.NoContent, _, _, _) =>
          Future { None }
        case HttpResponse(errorCode, _, responseEntity, _) =>
          parseEntity(responseEntity) map { errorResponse =>
            logger.error(s"Error while removing role $role from $id: $errorCode - ${errorResponse.toString}")
            Some(errorCode, errorResponse)
          }
      }
    }

    Future.sequence(createRequests ++ deleteRequests) map { responses =>
      val errors = responses.filter(response => response.nonEmpty)
      errors.headOption match {
        case None => None
        case Some(error) => error
      }
    }
  }

  def updateAccountDetails(details: JObject, id: String, token: String): Future[Option[(StatusCode, JObject)]] = {
    logger.debug(s"Update account details for $id")
    updateAccount(id, "details", details, token)
  }

  def updateAccountProfile(profile: JObject, id: String, token: String): Future[Option[(StatusCode, JObject)]] = {
    logger.debug(s"Update account profile for $id")
    updateAccount(id, "profile", profile, token)
  }

  def changePassword(password: String, isTemporary: Boolean, id: String, token: String): Future[Option[(StatusCode, JObject)]] = {
    val responseFuture: Future[HttpResponse] = Http().singleRequest(HttpRequest(method = HttpMethods.POST,
      uri = s"${StageConfig.AUTHZ_SERVER_URL}/pm/user/$id/password")
      .withHeaders(Authorization.apply(OAuth2BearerToken(token)))
      .withEntity(FormData(
        "isTemporary" -> (if(isTemporary) "true" else "false"),
        "new_password" -> password,
        "confirmation" -> password
      ).toEntity))

    responseFuture flatMap {
      case HttpResponse(StatusCodes.OK, _, _, _) =>
        Future { None }
      case HttpResponse(errorCode, _, responseEntity, _) =>
        parseEntity(responseEntity) map { errorResponse =>
          logger.error(s"Error while changing password for $id: $errorCode - ${errorResponse.toString}")
          Some(errorCode, errorResponse)
        }
    }
  }

  def deleteAccount(id: String, token: String): Future[Option[(StatusCode, JObject)]] = {
    val responseFuture: Future[HttpResponse] = Http().singleRequest(HttpRequest(method = HttpMethods.DELETE,
      uri = s"${StageConfig.AUTHZ_SERVER_URL}/pm/user/$id")
      .withHeaders(Authorization.apply(OAuth2BearerToken(token))))

    responseFuture flatMap {
      case HttpResponse(StatusCodes.NoContent, _, _, _) =>
        Future { None }
      case HttpResponse(errorCode, _, responseEntity, _) =>
        parseEntity(responseEntity) map { errorResponse =>
          logger.error(s"Error while deleting account: $id ($errorCode - ${errorResponse.toString})")
          Some(errorCode, errorResponse)
        }
    }
  }

  private def updateAccount(id: String, endpoint: String, entity: JObject, token: String): Future[Option[(StatusCode, JObject)]] = {
    val responseFuture: Future[HttpResponse] = Http().singleRequest(HttpRequest(method = HttpMethods.PUT,
      uri = s"${StageConfig.AUTHZ_SERVER_URL}/pm/user/$id/$endpoint")
      .withHeaders(Authorization.apply(OAuth2BearerToken(token)))
      .withEntity(HttpEntity.apply(ContentTypes.`application/json`, compact(JsonMethods.render(entity)))))

    responseFuture flatMap[Option[(StatusCode, JObject)]] {
      case HttpResponse(StatusCodes.OK, _, _, _) =>
        Future { None }
      case HttpResponse(errorCode, _, responseEntity, _) =>
        parseEntity(responseEntity) map { errorResponse =>
          logger.error(s"Error while updating account ($endpoint) of $id: $errorCode - ${errorResponse.toString}")
          Some(errorCode, errorResponse)
        }
    }
  }

  private def parseEntity(responseEntity: ResponseEntity): Future[JObject] = {
    Unmarshal(responseEntity).to[String] map {
      responseString: String => {
        responseString.parseJson.asInstanceOf[JObject]
      }
    }
  }
}
