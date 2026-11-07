package srdc.stage.cds

import akka.actor.ActorSystem
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import io.onfhir.api.client.OnFhirLocalClient
import io.onfhir.cds.OnFhirCds
import io.onfhir.config.{FhirConfigurationManager, OnfhirConfig}
import srdc.stage.cds.CdsServiceFactory
import srdc.stage.cds.config.CdsConfig

import scala.concurrent.duration.{DurationInt, FiniteDuration}

/**
 * The STAGE CDS Hooks services (formerly the standalone smart-cds server), served by this onFHIR server
 * (Onfhir.apply(..., cdsRoute = ...), see srdc.stage.fhir.Boot).
 *
 * The services use the FHIR configuration of this server (FhirConfigurationManager) for their in-memory FHIR search,
 * and prefetch requests without a fhirServer go to this server in-process (OnFhirLocalClient). When the FHIR API is
 * access controlled, CdsSecurity checks the service calls and sends their prefetch reads through the FHIR API instead.
 *
 * Served at <server>/cds-hooks and under the FHIR base, <server>/fhir/cds-hooks, i.e. <fhir.root-url>/cds-hooks: behind a
 * proxy that publishes only the FHIR base (e.g. /stage-fhir -> :7702/fhir), the CDS services of each repository are at
 * <public FHIR URL>/cds-hooks, and the apps find them from the FHIR server they are logged in to.
 */
object CdsModule {

  /**
   * Builds the CDS Hooks route ([<server.base-uri>/]<basePath>/cds-services/...). Call once, after onFHIR has been initialized
   * (Onfhir.apply has returned): the service implementations read the FHIR configuration when they are created.
   *
   * @param basePath       Path segment of the CDS Hooks endpoints (not a URL)
   * @param requestTimeout Request timeout of the CDS endpoints; onfhir-feast based services may take minutes, longer
   *                       than the akka.http.server.request-timeout of the FHIR API
   * @param system         The actor system of the onFHIR server
   */
  def route(basePath: String = "cds-hooks", requestTimeout: FiniteDuration = 320.seconds)(implicit system: ActorSystem): Route = {
    val onfhirCds = OnFhirCds.asOnFhirModule(CdsServiceFactory, basePath, FhirConfigurationManager, OnFhirLocalClient)
    // Service definitions to be used internally
    CdsConfig.cdsServiceDefinitions = onfhirCds.getConfig().cdsServiceDefinitions
    val cdsRoute = onfhirCds.start().route
    // The server tries this route first for every request: change the timeout only for the CDS paths
    // (withRequestTimeout takes effect even if the inner route rejects the request)
    val securedCdsRoute = CdsSecurity.secure(basePath)(cdsRoute)
    val timedCdsRoute = withRequestTimeout(requestTimeout)(securedCdsRoute)
    pathPrefixTest(separateOnSlashes(basePath)) {
      timedCdsRoute
    } ~
      // <fhir base>/cds-hooks: the FHIR base is consumed, the CDS route sees /cds-hooks/...
      pathPrefix(separateOnSlashes(OnfhirConfig.baseUri)) {
        pathPrefixTest(separateOnSlashes(basePath)) {
          timedCdsRoute
        }
      }
  }
}
