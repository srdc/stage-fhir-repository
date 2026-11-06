package srdc.stage.fhir

import akka.http.scaladsl.server.Route
import io.onfhir.Onfhir
import io.onfhir.operation.FhirOperationHandlerFactory
import io.onfhir.r4.config.FhirR4Configurator
import srdc.stage.cds.CdsModule

/**
 * STAGE FHIR Repository: onFHIR R4 with the STAGE conformance resources (fhir-conf), a few account/admin operations
 * and SMART Health Cards issuing ($health-cards-issue, one of the default onFHIR operations), plus the STAGE CDS Hooks
 * services (srdc.stage.cds, formerly the smart-cds project) under /cds-hooks
 */
object Boot extends App {

  // STAGE operations (the default onFHIR operations, e.g. $validate, $lastn, $health-cards-issue, are added by onFHIR)
  val stageFhirOperations = Map[String, String](
    "http://kroniq.srdc.com.tr/fhir/OperationDefinition/distinct-pages" -> "srdc.stage.fhir.operation.DistinctPages",
    "http://kroniq.srdc.com.tr/fhir/OperationDefinition/account-details" -> "srdc.stage.fhir.operation.AccountDetails",
    "http://kroniq.srdc.com.tr/fhir/OperationDefinition/delete-account" -> "srdc.stage.fhir.operation.DeleteAccount",
    "http://kroniq.srdc.com.tr/fhir/OperationDefinition/admin-panel-patient-list" -> "srdc.stage.fhir.operation.AdminPanelPatientList"
  )

  // CDS Hooks services. Onfhir.apply takes the route up front, but the CDS services need the FHIR
  // configuration, which onFHIR initializes in its constructor: the route is built after Onfhir.apply (below) and
  // passed here deferred. It runs on onFHIR's actor system.
  lazy val cdsRoute: Route = CdsModule.route("cds-hooks")(Onfhir.actorSystem)

  // Initialize onFHIR for R4
  val onfhir = Onfhir.apply(
    fhirConfigurator = new FhirR4Configurator(),
    fhirOperationLibraries = Seq(new FhirOperationHandlerFactory(stageFhirOperations)),
    cdsRoute = Some(ctx => cdsRoute(ctx))
  )

  // Build the CDS module now, so that a problem in the CDS definitions stops the start-up instead of the first CDS call
  cdsRoute

  // Store the STAGE ValueSets and Questionnaires
  StageInitializer.registerStageResources()

  // Start it
  onfhir.start
}
