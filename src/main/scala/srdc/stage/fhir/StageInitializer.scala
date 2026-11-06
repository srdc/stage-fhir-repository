package srdc.stage.fhir

import io.onfhir.api.Resource
import io.onfhir.api.util.FHIRUtil
import io.onfhir.config.FhirConfigurationManager
import io.onfhir.exception.InitializationException
import org.json4s.JsonAST.{JObject, JString}
import org.slf4j.{Logger, LoggerFactory}
import srdc.stage.fhir.config.StageConfig
import srdc.stage.fhir.util.ResourceReader

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

/**
 * Stores the STAGE conformance resources of fhir-conf (ValueSets, Questionnaires) at startup:
 * missing ones are created; existing ones are updated when their content changed
 * (or, when both have a 'date', when the file is newer).
 */
object StageInitializer {
  private val logger: Logger = LoggerFactory.getLogger(this.getClass)

  def registerStageResources(): Unit = {
    upsertResources("ValueSet", ResourceReader.readResourcesInFolder(StageConfig.valueSetsPath))
    upsertResources("Questionnaire", ResourceReader.readResourcesInFolder(StageConfig.questionnairesPath))
  }

  def upsertResources(resourceType: String, resources: Seq[Resource]): Unit = {
    val ofType = resources.filter(r => (r \ "resourceType") == JString(resourceType))
    if (ofType.size < resources.size)
      logger.warn(s"${resources.size - ofType.size} resources in the $resourceType folder are not ${resourceType}s; skipped.")
    if (ofType.isEmpty) {
      logger.info(s"No $resourceType resources to store.")
      return
    }
    val byId: Map[String, Resource] = ofType.map(r => FHIRUtil.extractIdFromResource(r) -> r).toMap
    val job = FhirConfigurationManager.resourceManager.getResourcesWithIds(resourceType, byId.keySet, excludeExtraFields = true).flatMap(existingResources => {
      val existing = existingResources.map(r => FHIRUtil.extractIdFromResource(r) -> r).toMap
      val toCreate = byId.filterNot(r => existing.contains(r._1))
      val toUpdate = byId.filter(r => existing.get(r._1).exists(current => changed(r._2, current)))
      val creation = if (toCreate.nonEmpty) FhirConfigurationManager.resourceManager.createResources(resourceType, toCreate).map(_ => ()) else Future.successful(())
      creation.flatMap(_ =>
        Future.sequence(toUpdate.toSeq.map { case (id, resource) =>
          val current = existing(id)
          FhirConfigurationManager.resourceManager.updateResource(resourceType, id, resource, FHIRUtil.extractVersionFromResource(current) -> current)
        })
      ).map(_ => logger.info(s"$resourceType: ${toCreate.size} created, ${toUpdate.size} updated, ${byId.size - toCreate.size - toUpdate.size} unchanged."))
    }).recover {
      case e: Throwable =>
        logger.error(s"Problem in storing $resourceType resources!", e)
        throw new InitializationException(s"Problem in storing $resourceType resources!")
    }
    Await.result(job, 120.seconds)
  }

  /** Whether the file content differs from the stored resource */
  private def changed(file: Resource, stored: Resource): Boolean = {
    (date(file), date(stored)) match {
      case (Some(fileDate), Some(storedDate)) => fileDate > storedDate
      case _ => withoutMeta(file) != withoutMeta(stored)
    }
  }

  private def date(resource: Resource): Option[String] = resource \ "date" match {
    case JString(value) => Some(value)
    case _ => None
  }

  private def withoutMeta(resource: Resource): JObject = JObject(resource.obj.filterNot(field => field._1 == "meta"))
}
