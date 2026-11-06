package srdc.stage.fhir.auth

import io.onfhir.authz.SmartAuthorizer

/**
 * SMART on FHIR authorization, with the launch context patient of the token available to the authorization rules
 * (fhir.authorization.method = "custom")
 */
class StageAuthorizer extends SmartAuthorizer {
  override def furtherParamsInAuthzContext: List[String] = List("patient")
}
