package srdc.stage.fhir

import io.onfhir.Onfhir
import io.onfhir.operation.FhirOperationHandlerFactory
import io.onfhir.r4.config.FhirR4Configurator

/**
 * STAGE FHIR Repository: onFHIR R4 with the STAGE conformance resources (fhir-conf), a few account/admin operations
 * and SMART Health Cards issuing ($health-cards-issue, one of the default onFHIR operations)
 */
object Boot extends App {

  // STAGE operations (the default onFHIR operations, e.g. $validate, $lastn, $health-cards-issue, are added by onFHIR)
  val stageFhirOperations = Map[String, String](
    "http://kroniq.srdc.com.tr/fhir/OperationDefinition/distinct-pages" -> "srdc.stage.fhir.operation.DistinctPages",
    "http://kroniq.srdc.com.tr/fhir/OperationDefinition/account-details" -> "srdc.stage.fhir.operation.AccountDetails",
    "http://kroniq.srdc.com.tr/fhir/OperationDefinition/delete-account" -> "srdc.stage.fhir.operation.DeleteAccount",
    "http://kroniq.srdc.com.tr/fhir/OperationDefinition/admin-panel-patient-list" -> "srdc.stage.fhir.operation.AdminPanelPatientList"
  )

  // Initialize onFHIR for R4
  val onfhir = Onfhir.apply(
    fhirConfigurator = new FhirR4Configurator(),
    fhirOperationLibraries = Seq(new FhirOperationHandlerFactory(stageFhirOperations))
  )

  // Store the STAGE ValueSets and Questionnaires
  StageInitializer.registerStageResources()

  // Start it
  onfhir.start
}
