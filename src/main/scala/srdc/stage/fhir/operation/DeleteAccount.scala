package srdc.stage.fhir.operation

import akka.http.scaladsl.model.StatusCodes
import io.onfhir.Onfhir
import io.onfhir.api.model.{FHIROperationRequest, FHIROperationResponse}
import io.onfhir.api.parsers.FHIRSearchParameterValueParser
import io.onfhir.api.service.{FHIROperationHandlerService, FHIRSearchService}
import io.onfhir.api.util.FHIRUtil
import io.onfhir.db.ResourceManager
import io.onfhir.exception.{InternalServerException, NotFoundException}
import io.onfhir.util.JsonFormatter.formats
import org.json4s.JsonAST.JField
import org.json4s.MonadicJValue.jvalueToMonadic
import org.json4s.{JArray, JObject}
import org.slf4j.{Logger, LoggerFactory}
import srdc.stage.fhir.model.fhir.Bundle
import srdc.stage.fhir.service.OnauthService
import srdc.stage.fhir.util.FHIRResourceTypes

import scala.concurrent.Future

class DeleteAccount extends FHIROperationHandlerService {

  val logger: Logger = LoggerFactory.getLogger("Operation[$delete-account]")

  implicit val actorSystem = Onfhir.actorSystem
  override implicit val executionContext = actorSystem.dispatcher

  override def executeOperation(operationName: String, operationRequest: FHIROperationRequest, resourceType: Option[String], resourceId: Option[String]): Future[FHIROperationResponse] = {
    if (resourceType.get == FHIRResourceTypes.RELATED_PERSON) {
      unlinkRelatedPerson(resourceId.get) flatMap { response =>
        deleteResource(FHIRResourceTypes.RELATED_PERSON, resourceId.get) map { _ =>
          response
        }
      }
    } else {
      deleteAccount(resourceType.get, resourceId.get, operationRequest.extractParamValue[Boolean]("deleteRelatedResources").getOrElse(false))
    }
  }

  def unlinkRelatedPerson(resourceId: String): Future[FHIROperationResponse] = {
    val personSearchParams = FHIRSearchParameterValueParser.parseSearchParameters(FHIRResourceTypes.PERSON, Map(
      "link" -> List(s"${FHIRResourceTypes.RELATED_PERSON}/${resourceId}")
    ))
    new FHIRSearchService().searchAndReturnBundle(FHIRResourceTypes.PERSON, personSearchParams) flatMap { personBundle =>
      if (personBundle.values.getOrElse("entry", List.empty).asInstanceOf[List[JObject]].nonEmpty) {
        val person = ((personBundle \ "entry").extract[List[JObject]].head \ "resource").extract[JObject]
        val links = (person \ "link").extract[List[JObject]]
        if (links.size == 1) {
          deleteAccount(FHIRResourceTypes.PERSON, (person \ "id").extract[String])
        } else {
          val updatedPerson = (person transformField {
            case JField("link", links) => JField("link",
              JArray(links.extract[List[JObject]].filter(link => (link \ "target" \ "reference").extract[String]
                != s"${FHIRResourceTypes.RELATED_PERSON}/${resourceId}")))
          }).asInstanceOf[JObject]
          ResourceManager.updateResource(FHIRResourceTypes.PERSON, (person \ "id").extract[String], updatedPerson,
            ((person \ "meta" \ "versionId").extract[String].toLong, person)) map {
            case (_, _, _) =>
              new FHIROperationResponse(StatusCodes.OK)
          }
        }
      } else {
        throw new NotFoundException(Seq.empty)
      }
    }
  }

  def deleteResource(resourceType: String, resourceId: String): Future[Unit] = {
    ResourceManager.getResource(resourceType, resourceId).flatMap {
      case None =>
        logger.debug("no document with given identifier, return 204 - No Content...")
        Future.apply()
      case Some(foundResource) =>
        if(FHIRUtil.isDeleted(foundResource)){
          logger.debug("document has already been marked as deleted, so simply return 204 - No Content...")
          Future.apply()
        } else {
          logger.debug("insert a new and empty document indicating that current document is deleted...")
          val currentVersion = FHIRUtil.extractVersionFromResource(foundResource)
          ResourceManager.deleteResource(resourceType, resourceId, currentVersion -> foundResource)
            .map(_._1) map {newVersion =>
            logger.debug("Empty document inserted...")
          }
        }
    }
  }

  def deleteRelatedResources(resourceId: String): Future[Unit] = {
    val resourceTypes = Seq("AllergyIntolerance", "Appointment", "CarePlan", "CareTeam", "Communication", "CommunicationRequest",
      "Condition", "Encounter", "EpisodeOfCare", "FamilyMemberHistory", "Goal", "Immunization", "MedicationAdministration",
      "MedicationRequest", "MedicationStatement", "Observation", "Procedure", "QuestionnaireResponse", "RelatedPerson", "ServiceRequest")

    val customParams = Map(
      "AllergyIntolerance" -> "patient",
      "Appointment" -> "patient",
      "FamilyMemberHistory" -> "patient",
      "RelatedPerson" -> "patient",
      "Patient" -> "_id",
      "EpisodeOfCare" -> "patient",
      "Immunization" -> "patient"
    )

    val searchService = new FHIRSearchService()

    Future.sequence(
      resourceTypes.map(rtype => {
        val params = FHIRSearchParameterValueParser.parseSearchParameters(rtype,
          if(customParams.contains(rtype)) Map(customParams(rtype) -> List(s"Patient/$resourceId"))
          else Map("subject" -> List(s"Patient/$resourceId")))
        searchService.searchAndReturnBundle(rtype, params) flatMap { bundleResource => {
          val bundle = bundleResource.extract[Bundle]
          Future.sequence(bundle.entry.map(entry => deleteResource((entry.resource \ "resourceType").extract[String], (entry.resource \ "id").extract[String])))
        } }
      })
    ) map {_ => }
  }

  def deleteAccount(resourceType: String, resourceId: String, deleteRelated: Boolean = false): Future[FHIROperationResponse] = {
    OnauthService.deleteAccount(resourceId, OnauthService.getToken()) flatMap {
      case None => {
        if (resourceType == "Patient" && deleteRelated) deleteRelatedResources(resourceId) else Future.apply()
        deleteResource(resourceType, resourceId) flatMap { _ =>
          Future(new FHIROperationResponse(StatusCodes.OK))
        }
      }
      case Some((_, error)) =>
        throw new InternalServerException((error \ "errorDesc").extract[String])
    }
  }
}
