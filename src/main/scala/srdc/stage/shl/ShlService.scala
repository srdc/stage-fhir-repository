package srdc.stage.shl

import akka.http.scaladsl.model.headers.{Allow, CacheDirectives, `Cache-Control`}
import akka.http.scaladsl.model._
import io.onfhir.authz.AuthzConfigurationManager
import org.json4s._
import org.json4s.native.JsonMethods
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets
import java.time._
import java.time.format.DateTimeFormatter
import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/** A request that is answered with an error (status, JSON body, headers) */
case class ShlError(status: StatusCode, body: JValue, headers: Seq[HttpHeader] = Nil) extends Exception(compactJson(body))

object ShlError {
  def apply(status: StatusCode, message: String): ShlError = ShlError(status, JObject("error" -> JString(message)))
}

/**
 * SMART Health Links (https://docs.smarthealthit.org/smart-health-links/spec,
 * http://hl7.org/fhir/uv/smart-health-cards-and-links/links-specification.html): the protocol of the Node shl-server
 * this replaces (same requests, answers and error messages; see deploy/testing/shl-conformance).
 *
 * Shared content is encrypted as received: JSON is not re-serialized through doubles, so FHIR decimals keep their precision
 * (1.50 stays 1.50).
 */
class ShlService(store: ShlStore)(implicit ec: ExecutionContext) {
  import ShlService._

  private val logger = LoggerFactory.getLogger(classOf[ShlService])

  /** Short-lived file locations handed out in manifests: ticket -> (link id, file index, expiry in epoch millis) */
  private val tickets = TrieMap[String, (String, Int, Long)]()

  // ---------------------------------------------------------------------------------------------------------------- create

  def create(body: String, bearer: Option[String], baseUrl: String): Future[HttpResponse] = Future(parseBody(body)).flatMap { request =>
    val label = request \ "label" match {
      case JNothing => None
      case JString(l) if l.length <= MaxLabelLength => Some(l)
      case _ => throw ShlError(StatusCodes.BadRequest, s""""label" must be a string of at most $MaxLabelLength characters.""")
    }
    val passcode = request \ "passcode" match {
      case JNothing | JNull => None
      case JString(p) if p.nonEmpty => Some(p)
      case _ => throw ShlError(StatusCodes.BadRequest, """"passcode" must be a non-empty string.""")
    }
    val files = requestedFiles(request)
    val exp = expiration(request \ "exp")
    val flag = flags(request \ "flag", passcode.isDefined, files.size)

    val key = ShlCrypto.randomToken()
    val id = ShlCrypto.randomToken()
    val managementToken = ShlCrypto.randomToken()
    val now = Timestamp.format(Instant.now())
    ownerOf(bearer).flatMap { owner =>
      val record = ShlRecord(
        id = id, createdAt = now, exp = exp, flag = flag, label = label, active = true,
        passcode = passcode.map(ShlCrypto.hashPasscode),
        remainingAttempts = passcode.map(_ => ShlConfig.passcodeAttempts),
        managementToken = ShlCrypto.hashToken(managementToken),
        owner = owner, revokedAt = None,
        files = files.map { case (contentType, content) => ShlFile(contentType, ShlCrypto.encrypt(content, contentType, key), now) }
      )
      store.insert(record).map { _ =>
        val url = s"$baseUrl/${ShlConfig.basePath}/$id/m"
        if (url.length > MaxManifestUrlLength)
          logger.warn(s"Manifest URL is ${url.length} characters; the specification allows at most $MaxManifestUrlLength. Use a shorter shl.public-base-url.")
        val payload = JObject(List(
          Some("url" -> JString(url)), Some("key" -> JString(key)),
          exp.map(e => "exp" -> JInt(e)),
          Some(flag).filter(_.nonEmpty).map(f => "flag" -> JString(f)),
          label.filter(_.nonEmpty).map(l => "label" -> JString(l))
        ).flatten)
        val shlink = "shlink:/" + ShlCrypto.b64url(compactJson(payload).getBytes(StandardCharsets.UTF_8))
        logger.debug(s"Created SHL $id" + (if (flag.nonEmpty) s" (flag $flag)" else ""))
        json(StatusCodes.Created, JObject(
          "id" -> JString(id),
          "payload" -> payload,
          "manifest" -> payload, // kept for older clients
          "shlink" -> JString(shlink),
          "qr" -> JString(ShlQrCode.dataUrl(shlink)),
          "managementToken" -> JString(managementToken),
          "ownerBound" -> JBool(owner.isDefined)
        ))
      }
    }
  }.recover(errors)

  /**
   * Files of the create request: `shc` (a {verifiableCredential: [...]} object, a JWS array or a JWS), `fhir` (a FHIR
   * resource or an array of them) and/or `files` ([{contentType, content}]), in this order, as (content type, plaintext)
   */
  private def requestedFiles(request: JValue): Seq[(String, Array[Byte])] = {
    val shc = request \ "shc" match {
      case JNothing => Nil
      case value =>
        val credentials = value match {
          case s: JString => Some(List(s))
          case JArray(items) => Some(items)
          case o: JObject => o \ "verifiableCredential" match {
            case JArray(items) => Some(items)
            case _ => None
          }
          case _ => None
        }
        credentials.filter(c => c.nonEmpty && c.forall { case JString(s) => s.nonEmpty; case _ => false }) match {
          case Some(c) => Seq(SmartHealthCard -> plaintext(JObject("verifiableCredential" -> JArray(c))))
          case None => throw ShlError(StatusCodes.BadRequest, """"shc" must hold at least one verifiable credential (compact JWS).""")
        }
    }
    val fhir = request \ "fhir" match {
      case JNothing => Nil
      case value =>
        (value match { case JArray(items) => items; case single => List(single) }).map {
          case resource: JObject if truthy(resource \ "resourceType") => FhirJson -> plaintext(resource)
          case _ => throw ShlError(StatusCodes.BadRequest, """"fhir" must hold FHIR resources.""")
        }
    }
    val unsupported = ShlError(StatusCodes.BadRequest,
      s"Unsupported file: contentType must be $SmartHealthCard, $FhirJson or $SmartApiAccess, with content.")
    val other = request \ "files" match {
      case value if !truthy(value) => Nil
      case JArray(items) => items.map {
        case file: JObject => (file \ "contentType", file \ "content") match {
          case (JString(contentType), content) if content != JNothing &&
            Set(SmartHealthCard, FhirJson, SmartApiAccess).contains(contentType.split(';').head.trim) => contentType -> plaintext(content)
          case _ => throw unsupported
        }
        case _ => throw unsupported
      }
      case _ => throw unsupported
    }
    val files = shc ++ fhir ++ other
    if (files.isEmpty) throw ShlError(StatusCodes.BadRequest, """Nothing to share: provide "shc", "fhir" or "files".""")
    files
  }

  /** Expiration as epoch seconds: epoch seconds or an ISO 8601 date (time) in the future */
  private def expiration(value: JValue): Option[Long] = {
    val seconds: Option[Long] = value match {
      case JNothing | JNull | JString("") => return None
      case JInt(n) => Some(n.toLong)
      case JLong(n) => Some(n)
      case JDecimal(d) => Some(d.setScale(0, BigDecimal.RoundingMode.FLOOR).toLong)
      case JDouble(d) if !d.isNaN && !d.isInfinite => Some(math.floor(d).toLong)
      case JString(text) => parseTime(text).map(t => Math.floorDiv(t.toEpochMilli, 1000L))
      case _ => None
    }
    seconds.filter(_ * 1000 > System.currentTimeMillis()).orElse(
      throw ShlError(StatusCodes.BadRequest, """"exp" must be a future time (epoch seconds or ISO date time)."""))
  }

  /** Flags in alphabetical order; P is set by giving a passcode, L and U on request */
  private def flags(requested: JValue, hasPasscode: Boolean, fileCount: Int): String = {
    val asked = requested match {
      case JString(s) => s.toUpperCase
      case _ => ""
    }
    if (asked.exists(c => !"LUP".contains(c))) throw ShlError(StatusCodes.BadRequest, """"flag" may only contain L, P and U.""")
    if (asked.contains('P') && !hasPasscode) throw ShlError(StatusCodes.BadRequest, "Flag P needs a passcode.")
    val flag = asked.filter(_ != 'P').toSet ++ (if (hasPasscode) Set('P') else Set.empty[Char])
    if (flag.contains('U') && flag.contains('P')) throw ShlError(StatusCodes.BadRequest, "Flag U cannot be combined with a passcode (P).")
    if (flag.contains('U') && fileCount != 1) throw ShlError(StatusCodes.BadRequest, "Flag U needs exactly one file.")
    flag.toSeq.sorted.mkString
  }

  // ------------------------------------------------------------------------------------------------------------ receivers

  /** Manifest request {recipient, passcode?, embeddedLengthMax?} */
  def manifest(id: String, body: String, baseUrl: String): Future[HttpResponse] = Future(parseBody(body)).flatMap { request =>
    servedRecord(id).flatMap { record =>
      if (record.flag.contains('U'))
        throw ShlError(StatusCodes.MethodNotAllowed, JObject("error" -> JString("This link has a single file (flag U): GET the link URL with ?recipient=...")),
          Seq(Allow(HttpMethods.GET)))
      val recipient = request \ "recipient" match {
        case JString(r) if r.strip().nonEmpty => r
        case _ => throw ShlError(StatusCodes.BadRequest, """"recipient" is required in the manifest request.""")
      }
      val embeddedLengthMax: Option[BigInt] = request \ "embeddedLengthMax" match {
        case JNothing => None
        case JInt(n) if n >= 0 => Some(n)
        case JLong(n) if n >= 0 => Some(BigInt(n))
        case JDecimal(d) if d >= 0 && d.isWhole => Some(d.toBigInt)
        case JDouble(d) if d >= 0 && d.isWhole => Some(BigInt(d.toLong))
        case _ => throw ShlError(StatusCodes.BadRequest, """"embeddedLengthMax" must be a non-negative integer.""")
      }
      checkPasscode(record, request \ "passcode").map { _ =>
        logger.debug(s"Manifest of SHL $id requested by $recipient")
        json(StatusCodes.OK, JObject(
          "status" -> JString("finalized"), // stored contents never change
          "files" -> JArray(record.files.zipWithIndex.map { case (file, index) =>
            val content =
              if (embeddedLengthMax.forall(file.jwe.length <= _)) "embedded" -> JString(file.jwe)
              else "location" -> JString(fileLocation(baseUrl, id, index))
            JObject("contentType" -> JString(file.contentType), content, "lastUpdated" -> JString(file.lastUpdated))
          }.toList)
        ))
      }
    }
  }.recover(errors)

  private def checkPasscode(record: ShlRecord, passcode: JValue): Future[Unit] = record.passcode match {
    case None => Future.unit
    case Some(stored) => passcode match {
      case JString(p) if p.nonEmpty =>
        if (ShlCrypto.verify(p, stored)) Future.unit
        else store.useAttempt(record.id).map {
          case Some(remaining) =>
            logger.debug(s"Wrong passcode for SHL ${record.id}, $remaining attempts left")
            throw ShlError(StatusCodes.Unauthorized, JObject("remainingAttempts" -> JInt(remaining)))
          case None => throw ShlError(StatusCodes.NotFound, NotFound)
        }
      case _ => throw ShlError(StatusCodes.Unauthorized, JObject("remainingAttempts" -> JInt(BigInt(record.remainingAttempts.getOrElse(0)))))
    }
  }

  /** Direct file of a U flag link (GET <manifest url>?recipient=...) */
  def directFile(id: String, recipient: Option[String]): Future[HttpResponse] = servedRecord(id).map { record =>
    if (!record.flag.contains('U'))
      throw ShlError(StatusCodes.MethodNotAllowed, JObject("error" -> JString("POST a manifest request to this URL.")), Seq(Allow(HttpMethods.POST)))
    if (!recipient.exists(_.strip().nonEmpty)) throw ShlError(StatusCodes.BadRequest, """"recipient" query parameter is required.""")
    logger.debug(s"File of SHL $id requested by ${recipient.get}")
    jose(record.files.head.jwe)
  }.recover(errors)

  private def fileLocation(baseUrl: String, id: String, index: Int): String = {
    val now = System.currentTimeMillis()
    tickets.filterInPlace { case (_, (_, _, expiresAt)) => expiresAt > now }
    val ticket = ShlCrypto.randomToken()
    tickets.put(ticket, (id, index, now + ShlConfig.locationTtlSeconds * 1000L))
    s"$baseUrl/${ShlConfig.basePath}/$id/f/$ticket"
  }

  /** File location handed out in a manifest */
  def locatedFile(id: String, ticket: String): Future[HttpResponse] = tickets.get(ticket) match {
    case Some((linkId, index, expiresAt)) if linkId == id && expiresAt > System.currentTimeMillis() =>
      servedRecord(id).map(record => record.files.lift(index).map(f => jose(f.jwe)).getOrElse(throw ShlError(StatusCodes.NotFound, NotFound)))
        .recover(errors)
    case _ =>
      tickets.remove(ticket)
      Future.successful(errorResponse(ShlError(StatusCodes.NotFound, "File location not found or expired; request the manifest again.")))
  }

  private def servedRecord(id: String): Future[ShlRecord] =
    (if (IdPattern.matches(id)) store.get(id) else Future.successful(None)).map {
      case Some(record) if record.isServed() => record
      case _ => throw ShlError(StatusCodes.NotFound, NotFound)
    }

  // ------------------------------------------------------------------------------------------------------ revoking, owners

  /**
   * Revoke a link with its management token, or with the access token of its owner: the manifest and files answer 404
   * afterwards and the encrypted files are deleted
   */
  def revoke(id: String, bearer: Option[String]): Future[HttpResponse] = {
    val notFound = ShlError(StatusCodes.NotFound, "SMART Health Link not found.")
    val token = bearer.getOrElse("")
    (if (IdPattern.matches(id)) store.get(id) else Future.successful(None)).flatMap {
      case Some(record) if record.active =>
        val allowed =
          if (ShlCrypto.verify(token, record.managementToken)) Future.successful(true)
          else if (record.owner.isDefined) ownerOf(bearer).map(_ == record.owner)
          else Future.successful(false)
        allowed.flatMap {
          case false => throw ShlError(StatusCodes.Forbidden, "Invalid management token.")
          case true => store.revoke(id, Timestamp.format(Instant.now())).map {
            case true =>
              logger.debug(s"Revoked SHL $id")
              HttpResponse(StatusCodes.NoContent)
            case false => throw notFound
          }
        }
      case _ => throw notFound
    }.recover(errors)
  }

  /** Metadata of the links bound to the caller, newest first (no keys: the links cannot be rebuilt from it) */
  def owned(bearer: Option[String]): Future[HttpResponse] = {
    if (!ownersEnabled) Future.successful(errorResponse(ShlError(StatusCodes.NotFound, "Link owners are not enabled on this server.")))
    else ownerOf(bearer).flatMap {
      case None => throw ShlError(StatusCodes.Unauthorized, "A valid access token is required.")
      case Some(owner) => store.ownedBy(owner).map { records =>
        json(StatusCodes.OK, JArray(records.sortBy(_.createdAt)(Ordering[String].reverse).map { r =>
          JObject(List(
            Some("id" -> JString(r.id)), r.label.map(l => "label" -> JString(l)), Some("createdAt" -> JString(r.createdAt)),
            r.exp.map(e => "exp" -> JInt(e)), Some("flag" -> JString(r.flag)), Some("active" -> JBool(r.isServed())),
            r.revokedAt.map(at => "revokedAt" -> JString(at))
          ).flatten)
        }.toList))
      }
    }.recover(errors)
  }

  /** Whether links can be bound to owners: enabled, and the FHIR API validates access tokens */
  def ownersEnabled: Boolean = ShlConfig.ownersEnabled && Option(AuthzConfigurationManager.tokenResolver).isDefined

  /**
   * Owner id of a valid access token of this repository (validated as the FHIR API does): a hash of its issuer and subject,
   * so that links do not hold the user id itself. None when owners are disabled or the token is missing or not valid.
   */
  def ownerOf(bearer: Option[String]): Future[Option[String]] = bearer match {
    case Some(token) if ownersEnabled && token.split('.').length == 3 =>
      Future(AuthzConfigurationManager.tokenResolver.resolveToken(token, Nil)).flatten.map { context =>
        if (context.isActive && !context.isExpired)
          context.sub.filter(_.nonEmpty).map(sub => ShlCrypto.b64url(ShlCrypto.sha256(context.issuer.getOrElse("") + "|" + sub)))
        else None
      }.recover { case e =>
        logger.debug(s"Access token rejected: ${e.getMessage}")
        None
      }
    case _ => Future.successful(None)
  }

  // --------------------------------------------------------------------------------------------------------------- helpers

  private val errors: PartialFunction[Throwable, HttpResponse] = {
    case e: ShlError => errorResponse(e)
    case e =>
      logger.error("SMART Health Links request failed", e)
      errorResponse(ShlError(StatusCodes.InternalServerError, "Internal server error"))
  }
}

object ShlService {
  val SmartHealthCard = "application/smart-health-card"
  val FhirJson = "application/fhir+json"
  val SmartApiAccess = "application/smart-api-access"
  val MaxLabelLength = 80
  val MaxManifestUrlLength = 128
  /** Link ids are 32 random bytes in base64url */
  val IdPattern = "^[A-Za-z0-9_-]{43}$".r
  val NotFound: JValue = JObject("error" -> JString("SMART Health Link not found or no longer valid."))

  /** ISO 8601 in UTC with milliseconds, e.g. 2026-11-09T10:00:00.000Z */
  val Timestamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX").withZone(ZoneOffset.UTC)

  private val NoStore = `Cache-Control`(CacheDirectives.`no-store`)
  private val Jose = ContentType(MediaType.customBinary("application", "jose", MediaType.NotCompressible))

  /** The request JSON; numbers are kept as written (BigDecimal), an empty body is an empty object */
  def parseBody(body: String): JValue =
    if (body == null || body.isBlank) JObject()
    else Try(JsonMethods.parse(body, useBigDecimalForDouble = true)).getOrElse(throw ShlError(StatusCodes.BadRequest, "Invalid JSON body."))

  /** What is encrypted: a string as is, anything else as compact JSON */
  def plaintext(content: JValue): Array[Byte] = (content match {
    case JString(s) => s
    case other => compactJson(other)
  }).getBytes(StandardCharsets.UTF_8)

  /** JavaScript truthiness of a JSON value (the Node server's checks) */
  def truthy(value: JValue): Boolean = value match {
    case JNothing | JNull => false
    case JBool(b) => b
    case JString(s) => s.nonEmpty
    case JInt(n) => n != 0
    case JLong(n) => n != 0
    case JDecimal(d) => d != 0
    case JDouble(d) => d != 0 && !d.isNaN
    case _ => true
  }

  def parseTime(text: String): Option[Instant] =
    Try(Instant.parse(text)).orElse(Try(OffsetDateTime.parse(text).toInstant)).orElse(Try(ZonedDateTime.parse(text).toInstant))
      .orElse(Try(LocalDateTime.parse(text).toInstant(ZoneOffset.UTC))).orElse(Try(LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant))
      .toOption

  def json(status: StatusCode, body: JValue, headers: Seq[HttpHeader] = Nil): HttpResponse =
    HttpResponse(status, NoStore +: headers, HttpEntity(ContentTypes.`application/json`, compactJson(body)))

  def jose(jwe: String): HttpResponse =
    HttpResponse(StatusCodes.OK, List(NoStore), HttpEntity(Jose, jwe.getBytes(StandardCharsets.UTF_8)))

  def errorResponse(error: ShlError): HttpResponse = json(error.status, error.body, error.headers)
}
