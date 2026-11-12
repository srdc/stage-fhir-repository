package srdc.stage.cds.service

import io.onfhir.Onfhir.actorSystem
import io.onfhir.cds.api.model.CdsResponse
import io.onfhir.cds.service.{BaseCdsService, CdsServiceContext, CdsServiceRequest}
import org.json4s.JsonAST.{JObject, JString}
import org.json4s.jackson.Serialization.write
import org.json4s.{DefaultFormats, JValue}
import srdc.stage.cds.client.MLModelClient
import srdc.stage.cds.model.StageDiseases

import scala.concurrent.{ExecutionContext, Future}

/**
 * CDS Service listing the diseases a risk prediction can be requested for, so that the user can choose them
 * before running risk_prediction (whose optional 'diseases' context then limits the ML service to these).
 *
 * The diseases are the ones the ML service's STAGE model predicts (shap-client `GET /models/stage/diseases`); their
 * groups and the sex of sex-specific cancers come from [[StageDiseases]], whose list is also the fallback when the
 * ML service cannot be reached. Sex-specific cancers are offered only for the matching patient gender; a disease
 * that has no model in the patient's stratum (e.g. Osteoporosis for men under 60) is simply not returned by the
 * ML service.
 *
 * Response: one card whose 'detail' is a JSON document {"source": ..., "diseases": [{"id", "name", "group"}]}.
 */
class RiskPredictionDiseasesService(cdsServiceContext: CdsServiceContext) extends BaseCdsService(cdsServiceContext) {
  implicit val formats: DefaultFormats.type = DefaultFormats

  override def executeCds(cdsServiceRequest: CdsServiceRequest)(implicit ex: ExecutionContext): Future[CdsResponse] = {
    val responseBuilder = createResponse(cdsServiceRequest)
    Future {
      val gender = patientGender(cdsServiceRequest)
      val (source, names) = MLModelClient.getDiseases().filter(_.nonEmpty) match {
        case Some(deployed) => ("ML service", deployed)
        case None => ("built-in list (ML service not reachable)", StageDiseases.Known.map(_.name))
      }
      val diseases =
        names.map(StageDiseases.describe)
          .filter(d => d.sex.isEmpty || gender.isEmpty || d.sex == gender)
          .map(d => Map("id" -> d.name, "name" -> d.name, "group" -> d.group))
      val detail = write(Map("source" -> source, "diseases" -> diseases))
      responseBuilder
        .withCard(_.loadCardWithPostTranslation("card-risk-prediction-diseases", "diseasesJson" -> detail))
        .cdsResponse
    }
  }

  /** 'male' / 'female' from the patient prefetch (a single resource or a list), if available */
  private def patientGender(cdsServiceRequest: CdsServiceRequest): Option[String] = {
    val patient: Option[JValue] = cdsServiceRequest.prefetches.get("patient") match {
      case Some(list: List[_]) => list.headOption.collect { case p: JObject => p }
      case Some(p: JObject) => Some(p)
      case _ => None
    }
    patient.flatMap(p => (p \ "gender") match {
      case JString(g) => Some(g.toLowerCase)
      case _ => None
    })
  }
}
