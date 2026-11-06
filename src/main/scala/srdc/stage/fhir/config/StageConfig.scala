package srdc.stage.fhir.config

import com.typesafe.config.{Config, ConfigFactory}

import scala.util.Try

/**
 * STAGE specific settings of the repository (the onFHIR settings are read by onFHIR itself)
 */
object StageConfig {

  /** Application config object (honours -Dconfig.file) */
  val config: Config = ConfigFactory.load()

  /** Folders of the STAGE conformance resources stored at startup (created if missing, updated if changed) */
  lazy val valueSetsPath: Option[String] = Try(config.getString("fhir.initialization.stage-valuesets-path")).toOption
  lazy val questionnairesPath: Option[String] = Try(config.getString("fhir.initialization.stage-questionnaires-path")).toOption

  // AUTH configuration begin
  lazy val AUTHZ_SERVER_URL: String = config.getString("fhir.authorization.authz-server-url")
  // AUTH configuration end
}
