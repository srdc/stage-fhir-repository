package srdc.stage.shl

import srdc.stage.fhir.config.StageConfig

/**
 * Settings of the SMART Health Links server (`shl` in application.conf)
 */
object ShlConfig {
  private val config = StageConfig.config

  private def opt[T](path: String)(read: String => T): Option[T] =
    if (config.hasPath(path)) Some(read(path)) else None

  /** Whether this repository serves SMART Health Links */
  lazy val enabled: Boolean = opt("shl.enabled")(config.getBoolean).getOrElse(true)

  /** Path of the SHL endpoints on this server: <server>/<basePath>/... */
  lazy val basePath: String = opt("shl.base-path")(config.getString).getOrElse("shl")

  /**
   * Public base URL of the SHL endpoints as seen by receivers, without /shl, e.g. https://kroniq.srdc.com.tr/stage-shl
   * (a reverse proxy maps it to <server>/). Manifest URLs are built from it; without it, from the request's scheme and
   * host, which misses any path prefix added by a proxy.
   */
  lazy val publicBaseUrl: Option[String] =
    opt("shl.public-base-url")(config.getString).map(_.trim.replaceAll("/+$", "")).filter(_.nonEmpty)

  /** MongoDB collection of the links (in the repository's database) */
  lazy val collection: String = opt("shl.collection")(config.getString).getOrElse("shl_links")

  /** Lifetime number of wrong passcodes accepted before a P link is disabled */
  lazy val passcodeAttempts: Int = math.max(1, opt("shl.passcode-attempts")(config.getInt).getOrElse(5))

  /** Lifetime of file `location` URLs in seconds; the specification allows at most one hour */
  lazy val locationTtlSeconds: Int = math.min(3600, math.max(1, opt("shl.location-ttl-seconds")(config.getInt).getOrElse(300)))

  /** Maximum request body size in bytes (the health cards of a patient summary can be large) */
  lazy val bodyLimit: Long = opt("shl.body-limit")(p => config.getBytes(p).longValue()).getOrElse(10L * 1024 * 1024)

  /**
   * Link owners: a link created with the user's access token of this repository (`Authorization: Bearer`) is bound to the
   * token's subject, who can list it (GET /shl/owned) and revoke it without its management token. Tokens are validated as
   * the FHIR API does (fhir.authorization); without FHIR authorization there are no owners.
   */
  lazy val ownersEnabled: Boolean = opt("shl.owners.enabled")(config.getBoolean).getOrElse(true)
}
