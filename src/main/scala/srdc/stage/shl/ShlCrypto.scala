package srdc.stage.shl

import com.nimbusds.jose.crypto.DirectEncrypter
import com.nimbusds.jose.{EncryptionMethod, JWEAlgorithm, JWEHeader, JWEObject, Payload}

import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, SecureRandom}
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.{PBEKeySpec, SecretKeySpec}

/** A stored secret (passcode or management token): never the secret itself */
case class Secret(alg: String, salt: String, hash: String, iterations: Int)

/**
 * Keys, tokens, file encryption and secret hashing of SMART Health Links
 */
object ShlCrypto {
  private val random = new SecureRandom()
  private val encoder = Base64.getUrlEncoder.withoutPadding()

  /** Passcodes are short and chosen by people: a slow hash; management tokens have 256 random bits: SHA-256 is enough */
  private val Pbkdf2 = "PBKDF2WithHmacSHA256"
  private val Pbkdf2Iterations = 120000
  private val Sha256 = "SHA-256"

  def b64url(bytes: Array[Byte]): String = encoder.encodeToString(bytes)

  def b64urlDecode(text: String): Array[Byte] = Base64.getUrlDecoder.decode(text)

  /** 32 random bytes, base64url (43 characters): link ids, keys (AES-256), management tokens, file location tickets */
  def randomToken(): String = {
    val bytes = new Array[Byte](32)
    random.nextBytes(bytes)
    b64url(bytes)
  }

  def sha256(text: String): Array[Byte] = MessageDigest.getInstance(Sha256).digest(text.getBytes(StandardCharsets.UTF_8))

  /**
   * A file of a link as a compact JWE (alg "dir", enc "A256GCM", cty = content type); a new random nonce for every call,
   * as the specification requires
   */
  def encrypt(plaintext: Array[Byte], contentType: String, key: String): String = {
    val header = new JWEHeader.Builder(JWEAlgorithm.DIR, EncryptionMethod.A256GCM).contentType(contentType).build()
    val jwe = new JWEObject(header, new Payload(plaintext))
    jwe.encrypt(new DirectEncrypter(new SecretKeySpec(b64urlDecode(key), "AES")))
    jwe.serialize()
  }

  def hashPasscode(passcode: String): Secret = {
    val salt = new Array[Byte](16)
    random.nextBytes(salt)
    Secret(Pbkdf2, b64url(salt), b64url(pbkdf2(passcode, salt, Pbkdf2Iterations)), Pbkdf2Iterations)
  }

  def hashToken(token: String): Secret = Secret(Sha256, "", b64url(sha256(token)), 1)

  /** Whether the secret matches the stored one (constant time comparison) */
  def verify(secret: String, stored: Secret): Boolean = {
    if (secret == null || secret.isEmpty) return false
    val expected = b64urlDecode(stored.hash)
    val actual = stored.alg match {
      case Pbkdf2 => pbkdf2(secret, b64urlDecode(stored.salt), stored.iterations)
      case Sha256 => sha256(secret)
      case _ => return false
    }
    MessageDigest.isEqual(actual, expected)
  }

  private def pbkdf2(secret: String, salt: Array[Byte], iterations: Int): Array[Byte] =
    SecretKeyFactory.getInstance(Pbkdf2).generateSecret(new PBEKeySpec(secret.toCharArray, salt, iterations, 256)).getEncoded
}
