package srdc.stage.fhir

import io.onfhir.api.Resource
import io.onfhir.api.model.FHIRRequest
import io.onfhir.authz.{AuthzContext, AuthzResult, IAuthorizer, SmartAuthorizer, WGHeartScope}
import io.onfhir.config.FhirConfigurationManager
import org.json4s.JValue

/**
 * onFHIR's SMART authorization, with patient scopes applying only to the resource types of the Patient compartment.
 *
 * Since onFHIR 3.4 (SmartAuthorizer.resolveEffectiveScopes) a token with any patient/ scope fails with
 * "FHIR Patient compartment for resource type X should be configured ..." (500) on every resource type outside the
 * Patient compartment, e.g. a clinician logged in with a patient context (scopes launch/patient, patient/all, user/all) reading
 * their own Practitioner, or a patient reading a Questionnaire. Before 3.4, patient scopes were used only for the types
 * of the Patient compartment and the user/system scopes of the token decided on the others. This restores that: for a
 * type outside the Patient compartment the patient scopes are left out, so the user/system scopes decide (no such
 * scope: 401 insufficient_scope).
 */
class StageSmartAuthorizer extends IAuthorizer {

  // SmartAuthorizer reads the FHIR configuration when it is created, which onFHIR initializes in Onfhir.apply, after
  // the authorizer is given to it: created on first use
  private lazy val delegate = new PatientCompartmentAwareSmartAuthorizer

  override def furtherParamsInAuthzContext: List[String] = delegate.furtherParamsInAuthzContext

  override def authorize(authzContext: AuthzContext, fhirRequest: FHIRRequest): AuthzResult =
    delegate.authorize(authzContext, fhirRequest)

  override def authorizeForPublic(fhirRequest: FHIRRequest): AuthzResult = delegate.authorizeForPublic(fhirRequest)
}

private class PatientCompartmentAwareSmartAuthorizer
  extends SmartAuthorizer(
    io.onfhir.config.OnfhirConfig.authzConfig.getConfig(io.onfhir.api.AUTHZ_METHOD_FHIR_ON_SMART),
    FhirConfigurationManager) {

  private def inPatientCompartment(rtype: String): Boolean =
    FhirConfigurationManager.fhirConfig.compartmentRelations.get("Patient").exists(_.isDefinedAt(rtype))

  override protected def authorizeForResourceType(rtype: String,
                                                  interaction: String,
                                                  resourceContent: Option[Resource],
                                                  fhirPathContext: Map[String, JValue],
                                                  authzContext: AuthzContext,
                                                  scopes: Seq[WGHeartScope]): AuthzResult =
    super.authorizeForResourceType(rtype, interaction, resourceContent, fhirPathContext, authzContext,
      if (inPatientCompartment(rtype)) scopes else scopes.filterNot(_.ptype == SmartAuthorizer.PERMISSION_TYPE_PATIENT))
}
