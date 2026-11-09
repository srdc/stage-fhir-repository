package srdc.stage.shl

import io.onfhir.db.MongoDB
import org.bson.{BsonArray, BsonBoolean, BsonDocument, BsonInt32, BsonInt64, BsonString, BsonValue}
import org.mongodb.scala._
import org.mongodb.scala.model.{Filters, FindOneAndUpdateOptions, Indexes, ReturnDocument, Updates}
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._

/** An encrypted file of a link */
case class ShlFile(contentType: String, jwe: String, lastUpdated: String)

/**
 * A SMART Health Link: what is needed to serve it, never its key (the files cannot be decrypted with what is stored)
 *
 * @param exp               expiration, epoch seconds
 * @param flag              flags in alphabetical order (L, P, U), "" for none
 * @param remainingAttempts wrong passcodes still accepted (flag P)
 * @param owner             hash of the issuer and subject of the creator's access token, see ShlService.ownerOf
 */
case class ShlRecord(id: String,
                     createdAt: String,
                     exp: Option[Long],
                     flag: String,
                     label: Option[String],
                     active: Boolean,
                     passcode: Option[Secret],
                     remainingAttempts: Option[Int],
                     managementToken: Secret,
                     owner: Option[String],
                     revokedAt: Option[String],
                     files: Seq[ShlFile]) {

  /** Served while active, not expired and, for passcode links, with attempts left */
  def isServed(nowMillis: Long = System.currentTimeMillis()): Boolean =
    active && exp.forall(_ * 1000 > nowMillis) && !(passcode.isDefined && remainingAttempts.exists(_ <= 0))
}

/**
 * The links of this repository, in a collection of its MongoDB database (ShlConfig.collection)
 */
class ShlStore(collectionName: String)(implicit ec: ExecutionContext) {
  private val logger = LoggerFactory.getLogger(classOf[ShlStore])
  private lazy val collection: MongoCollection[Document] = MongoDB.getDatabase.getCollection[Document](collectionName)

  def createIndexes(): Future[Unit] =
    collection.createIndex(Indexes.ascending("owner")).toFuture().map(_ => ()).recover {
      case e => logger.warn(s"Index of the SMART Health Links ($collectionName) could not be created", e)
    }

  def insert(record: ShlRecord): Future[Unit] = collection.insertOne(Document(toBson(record))).toFuture().map(_ => ())

  def get(id: String): Future[Option[ShlRecord]] =
    collection.find(Filters.equal("_id", id)).headOption().map(_.map(d => fromBson(d.toBsonDocument)))

  def ownedBy(owner: String): Future[Seq[ShlRecord]] =
    collection.find(Filters.equal("owner", owner)).projection(Document("files" -> 0)).toFuture()
      .map(_.map(d => fromBson(d.toBsonDocument)))

  /**
   * Uses one passcode attempt of an active link (atomically, so parallel requests cannot share an attempt); disables the
   * link when none is left.
   *
   * @return the attempts left, or None when the link has none left (or is no longer active)
   */
  def useAttempt(id: String): Future[Option[Int]] =
    collection.findOneAndUpdate(
      Filters.and(Filters.equal("_id", id), Filters.equal("active", true), Filters.gt("remainingAttempts", 0)),
      Updates.inc("remainingAttempts", -1),
      FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
    ).headOption().flatMap {
      case None => Future.successful(None)
      case Some(doc) =>
        val remaining = doc.toBsonDocument.getInt32("remainingAttempts").getValue
        if (remaining > 0) Future.successful(Some(remaining))
        else collection.updateOne(Filters.equal("_id", id), Updates.set("active", false)).toFuture().map(_ => Some(0))
    }

  /** Revokes an active link and deletes its files; false when it was not active (any more) */
  def revoke(id: String, revokedAt: String): Future[Boolean] =
    collection.updateOne(
      Filters.and(Filters.equal("_id", id), Filters.equal("active", true)),
      Updates.combine(Updates.set("active", false), Updates.set("revokedAt", revokedAt), Updates.set("files", new BsonArray()))
    ).toFuture().map(_.getModifiedCount == 1)

  private def secret(s: Secret): BsonDocument = new BsonDocument()
    .append("alg", new BsonString(s.alg)).append("salt", new BsonString(s.salt))
    .append("hash", new BsonString(s.hash)).append("iterations", new BsonInt32(s.iterations))

  private def toBson(r: ShlRecord): BsonDocument = {
    val doc = new BsonDocument()
      .append("_id", new BsonString(r.id))
      .append("createdAt", new BsonString(r.createdAt))
      .append("flag", new BsonString(r.flag))
      .append("active", new BsonBoolean(r.active))
      .append("managementToken", secret(r.managementToken))
      .append("files", new BsonArray(r.files.map(f => new BsonDocument()
        .append("contentType", new BsonString(f.contentType))
        .append("jwe", new BsonString(f.jwe))
        .append("lastUpdated", new BsonString(f.lastUpdated)): BsonValue).asJava))
    r.exp.foreach(exp => doc.append("exp", new BsonInt64(exp)))
    r.label.foreach(label => doc.append("label", new BsonString(label)))
    r.passcode.foreach(p => doc.append("passcode", secret(p)))
    r.remainingAttempts.foreach(n => doc.append("remainingAttempts", new BsonInt32(n)))
    r.owner.foreach(owner => doc.append("owner", new BsonString(owner)))
    r.revokedAt.foreach(at => doc.append("revokedAt", new BsonString(at)))
    doc
  }

  private def fromBson(d: BsonDocument): ShlRecord = {
    def string(key: String): Option[String] = Option(d.get(key)).filter(_.isString).map(_.asString.getValue)
    def readSecret(value: BsonValue): Secret = {
      val s = value.asDocument()
      Secret(s.getString("alg").getValue, s.getString("salt").getValue, s.getString("hash").getValue, s.getInt32("iterations").getValue)
    }
    ShlRecord(
      id = d.getString("_id").getValue,
      createdAt = d.getString("createdAt").getValue,
      exp = Option(d.get("exp")).filter(_.isNumber).map(_.asNumber.longValue),
      flag = string("flag").getOrElse(""),
      label = string("label"),
      active = d.getBoolean("active", BsonBoolean.FALSE).getValue,
      passcode = Option(d.get("passcode")).filter(_.isDocument).map(readSecret),
      remainingAttempts = Option(d.get("remainingAttempts")).filter(_.isNumber).map(_.asNumber.intValue),
      managementToken = readSecret(d.get("managementToken")),
      owner = string("owner"),
      revokedAt = string("revokedAt"),
      files = Option(d.get("files")).filter(_.isArray).map(_.asArray.getValues.asScala.toSeq.map { f =>
        val file = f.asDocument()
        ShlFile(file.getString("contentType").getValue, file.getString("jwe").getValue, file.getString("lastUpdated").getValue)
      }).getOrElse(Nil)
    )
  }
}
