package srdc.stage.cds.service

import io.onfhir.cds.api.model.CdsResponse
import io.onfhir.cds.service.{BaseCdsService, CdsServiceContext, CdsServiceRequest}
import org.json4s.{DefaultFormats, JNothing}
import srdc.stage.cds.flow.ACCAHAFlowExecution
import srdc.stage.cds.util.{CdsPrefetchUtil, ConceptIdUtil}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class ACCAHAService(cdsServiceContext: CdsServiceContext) extends BaseCdsService(cdsServiceContext) {
  implicit val formats: DefaultFormats.type = DefaultFormats

  override def executeCds(cdsServiceRequest: CdsServiceRequest)(implicit ex: ExecutionContext): Future[CdsResponse] = {
    val fhirPathEvaluator = getFhirPathEvaluator(cdsServiceRequest)
    val responseBuilder = createResponse(cdsServiceRequest)

    Future {
      // Without a (full) birth date or a gender the score cannot be calculated: no cards, the app tells the user so
      val age = Try(CdsPrefetchUtil.getAge(fhirPathEvaluator)).toOption
      val gender = Try(fhirPathEvaluator.evaluateString(CdsPrefetchUtil.GENDER_PATH, JNothing).headOption).toOption.flatten
      if (age.isEmpty || gender.isEmpty) responseBuilder.cdsResponse
      else ACCAHAFlowExecution.executeFlow(
        age = age.get,
        gender = gender.get,
        totalCholesterol = CdsPrefetchUtil.getObservationValue("TotalCholesterol", fhirPathEvaluator),
        hdlCholesterol = CdsPrefetchUtil.getObservationValue("HDL", fhirPathEvaluator),
        systolicBP = CdsPrefetchUtil.getObservationOrComponentValue("BP_SBP", ConceptIdUtil.SYSTOLIC_BP, fhirPathEvaluator),
        smokingStatus = CdsPrefetchUtil.getSmokingCategory("SmokingStatus", fhirPathEvaluator),
        type1Diabetes = CdsPrefetchUtil.existsInt("Type1Diabetes", fhirPathEvaluator),
        type2Diabetes = CdsPrefetchUtil.existsInt("Type2Diabetes", fhirPathEvaluator),
        hypertensiveTreatment = CdsPrefetchUtil.existsInt("HypertensiveTreatment", fhirPathEvaluator),
        ethnicity = CdsPrefetchUtil.getRaceCategory("Ethnicity", fhirPathEvaluator),
        responseBuilder
      ).cdsResponse
    }
  }
}
