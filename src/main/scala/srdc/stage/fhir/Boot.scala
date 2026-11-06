package srdc.stage.fhir

import io.onfhir.Onfhir
import io.onfhir.r4.config.FhirR4Configurator
import srdc.stage.fhir.auth.StageAuthorizer
import srdc.stage.fhir.config.StageConfig

/**
 * STAGE FHIR Repository: onFHIR R4 with the STAGE conformance resources (fhir-conf), a few account/admin operations
 * and SMART Health Cards issuing ($health-cards-issue)
 */
object Boot extends App {

  val stageFhirOperations = Map[String, String](
    "http://kroniq.srdc.com.tr/fhir/OperationDefinition/distinct-pages" -> "srdc.stage.fhir.operation.DistinctPages",
    "http://kroniq.srdc.com.tr/fhir/OperationDefinition/account-details" -> "srdc.stage.fhir.operation.AccountDetails",
    "http://kroniq.srdc.com.tr/fhir/OperationDefinition/delete-account" -> "srdc.stage.fhir.operation.DeleteAccount",
    "http://kroniq.srdc.com.tr/fhir/OperationDefinition/admin-panel-patient-list" -> "srdc.stage.fhir.operation.AdminPanelPatientList",
    "http://hl7.org/fhir/uv/smart-health-cards-and-links/OperationDefinition/patient-i-health-cards-issue" -> "io.onfhir.operation.HealthCardIssueOperationHandler"
  )

  implicit val actorSystem = Onfhir.actorSystem

  // Initialize onFHIR for R4
  val onfhir = Onfhir.apply(
    fhirConfigurator = new FhirR4Configurator(),
    fhirOperationImplms = stageFhirOperations,
    customAuthorizer = if (StageConfig.AUTHZ_METHOD == "custom") Some(new StageAuthorizer()) else None
  )

  // Store the STAGE ValueSets and Questionnaires
  StageInitializer.registerStageResources()

  // Start it
  onfhir.start
}
