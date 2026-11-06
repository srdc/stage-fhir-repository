package srdc.stage.fhir.operation

import akka.http.scaladsl.model.StatusCodes
import io.onfhir.Onfhir
import io.onfhir.api.model.{FHIROperationRequest, FHIROperationResponse}
import io.onfhir.api.parsers.FHIRSearchParameterValueParser
import io.onfhir.api.service.{FHIROperationHandlerService, FHIRSearchService}
import io.onfhir.db.ResourceManager
import io.onfhir.exception.{InternalServerException, NotFoundException}
import io.onfhir.util.JsonFormatter.formats
import org.json4s.JObject
import org.slf4j.{Logger, LoggerFactory}
import srdc.stage.fhir.service.OnauthService
import srdc.stage.fhir.util.FHIRResourceTypes

import scala.concurrent.Future

class AccountDetails extends FHIROperationHandlerService  {
  private val logger: Logger = LoggerFactory.getLogger("Operation[$account-details]")

  implicit val actorSystem = Onfhir.actorSystem
  override implicit val executionContext = actorSystem.dispatcher

  def getRelatedPerson(patientId: String) = {
    val searchService = new FHIRSearchService()
    val searchParams = FHIRSearchParameterValueParser.parseSearchParameters(FHIRResourceTypes.RELATED_PERSON, Map(
      "patient" -> List(s"${FHIRResourceTypes.PATIENT}/${patientId}"),
      "active" -> List("true")
    ))
    searchService.searchAndReturnBundle(FHIRResourceTypes.RELATED_PERSON, searchParams) flatMap (result => {
      if (result.values.getOrElse("entry", List.empty).asInstanceOf[List[JObject]].nonEmpty) {
        val entry = (result \ "entry").extract[List[JObject]].head
        val relatedPerson = (entry \ "resource").extract[JObject]
        val id = (relatedPerson \ "id").extract[String]
        logger.debug(s"Related person found: RelatedPerson/${id}. Searching linked Person...")
        val personSearchParams = FHIRSearchParameterValueParser.parseSearchParameters(FHIRResourceTypes.PERSON, Map(
          "link" -> List(s"${FHIRResourceTypes.RELATED_PERSON}/${id}")
        ))
        searchService.searchAndReturnBundle(FHIRResourceTypes.PERSON, personSearchParams) map { personBundle =>
          if (personBundle.values.getOrElse("entry", List.empty).asInstanceOf[List[JObject]].nonEmpty) {
            val person = ((personBundle \ "entry").extract[List[JObject]].head \ "resource").extract[JObject]
            Some(person, relatedPerson)
          } else {
            logger.debug(s"No person found. Returning account information of Patient/${patientId}")
            None
          }
        }
      } else {
        logger.debug(s"No related person found. Returning account information of Patient/${patientId}")
        Future.apply(None)
      }
    })
  }

  override def executeOperation(operationName: String, operationRequest: FHIROperationRequest, resourceType: Option[String], resourceId: Option[String]):Future[FHIROperationResponse] = {
    logger.debug(s"Retrieving account details for Patient/${resourceId.get}")
    getPatient(resourceId.get) flatMap { resource =>
      logger.debug(s"Resource found: ${resourceType.get}/${resourceId.get}")
      val opResponse = new FHIROperationResponse(StatusCodes.OK)
      opResponse.setComplexOrResourceParam("patient", resource)
      logger.debug(s"Searching related person of Patient/${resourceId.get}")
      getRelatedPerson(resourceId.get) map {
        case Some((person, relatedPerson)) =>
          opResponse.setComplexOrResourceParam("person", person)
          opResponse.setComplexOrResourceParam("relatedPerson", relatedPerson)
          val personId = (person \ "id").extract[String]
          logger.debug(s"Person found: Person/${personId}.")
          (opResponse, Some(personId))
        case None =>
          (opResponse, None)
      }
    } flatMap { result =>
      val (opResponse, personId) = result
      if (OnauthService.isSecure) {
        logger.debug(s"Retrieving onauth account of Patient/${resourceId.get}")
        getUser(resourceId.get) flatMap { user =>
          if (user != null) {
            logger.debug(s"Onauth account found for Patient/${resourceId.get}")
            val account = (user \ "account").extract[JObject]
            val username = account.values("username").asInstanceOf[String]
            val email = account.values.getOrElse("email", "").asInstanceOf[String]
            opResponse.setPrimitiveParam("username", username)
            opResponse.setPrimitiveParam("email", email)
            personId match {
              case Some(id: String) =>
                logger.debug(s"Retrieving onauth account of Person/$personId")
                getUser(id) map { relatedPersonUser =>
                  if (relatedPersonUser != null) {
                    logger.debug(s"Onauth account found for Person/$personId")
                    val relatedPersonAccount = (relatedPersonUser \ "account").extract[JObject]
                    val relatedPersonUsername = (relatedPersonAccount \ "username").extract[String]
                    val relatedPersonEmail = (relatedPersonAccount \ "email").extractOpt[String]
                    val relatedPersonRoles = (relatedPersonUser \ "roles").extract[List[String]]
                    opResponse.setPrimitiveParam("relatedPersonUsername", relatedPersonUsername)
                    opResponse.setPrimitiveParam("relatedPersonEmail", relatedPersonEmail)
                    opResponse.setPrimitiveParamList("relatedPersonRole", relatedPersonRoles)
                  }
                  opResponse
                }
              case None =>
                Future.apply(opResponse)
            }
          } else {
            Future.apply(opResponse)
          }
        }
      } else {
        Future.apply(opResponse)
      }
    }
  }

  private def getUser(id: String) = {
    val token = OnauthService.getToken()
    OnauthService.getUser(id, token) map {
      case (Some(user), _, _) =>
        logger.debug(s"User account found for ${id}")
        user
      case (None, Some(statusCode), Some(errorResponse)) =>
        statusCode match {
          case StatusCodes.NotFound => {
            logger.debug(s"User account not found: ${id}")
//            throw new NotFoundException(Seq.empty)
            null
          }
          case _ => {
            val errorStr = errorResponse.values.getOrElse("errorDesc", errorResponse.values.getOrElse("error", "")).asInstanceOf[String]
            logger.error(s"Cannot get onauth account of ${id}: $errorStr")
            throw new InternalServerException(errorStr)
          }
        }
    }
  }

  private def getPatient(id: String) = {
    ResourceManager.getResource(FHIRResourceTypes.PATIENT, id, excludeExtraFields = true) map {
      case Some(resource) =>
        resource
      case _ =>
        logger.error(s"Resource not found: ${FHIRResourceTypes.PATIENT}/${id}")
        throw new NotFoundException(Seq.empty)
    }
  }
}
