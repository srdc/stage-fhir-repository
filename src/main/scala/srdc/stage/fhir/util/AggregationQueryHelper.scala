package srdc.stage.fhir.util

import org.bson
import org.mongodb.scala.bson.Document

object AggregationQueryHelper {
  def $first(query: Any): bson.Document = Document().append("$first", query)
  def $set(fields: (String, Any)*): bson.Document = Document().append("$set", foldQueriesToDocument(fields))
  def $filter(input: Any, as: String, cond: Any): bson.Document =
    Document().append("$filter", Document().append("input", input).append("as", as).append("cond", cond))
  def $match(query: (String, Any)*): bson.Document = Document().append("$match", foldQueriesToDocument(query))
  def $group(id: Any, fieldAccumulators: (String, Any)*): bson.Document = Document().append("$group",
      foldQueriesToDocument(fieldAccumulators).append("_id", id))
  def $lookup(from: String, localField: String, foreignField: String, as: String): bson.Document = Document().append("$lookup", Document()
    .append("from", from)
    .append("localField", localField)
    .append("foreignField", foreignField)
    .append("as", as))
  def $unwind(field: String, preserveNullAndEmptyArrays: Option[Boolean] = None): bson.Document = {
    if (preserveNullAndEmptyArrays.nonEmpty) Document().append("$unwind", Document().append("path", field)
      .append("preserveNullAndEmptyArrays", preserveNullAndEmptyArrays.get))
    else Document().append("$unwind", field)
  }
  def $sum(expr: Any): bson.Document = Document().append("$sum", expr)
  def $sort(fields: (String, Any)*): bson.Document = Document().append("$sort", foldQueriesToDocument(fields))
  def $push(fields: (String, Any)*): bson.Document = Document().append("$push", foldQueriesToDocument(fields))
  def $project(fields: (String, Any)*): bson.Document = Document().append("$project", foldQueriesToDocument(fields))
  def $dateToString(format: String, date: Any): bson.Document = Document().append("$dateToString", Document().append("format", format).append("date", date))
  def $exists(bool: Boolean): bson.Document = _document("$exists", bool)

  def _compare(op: String, fields: Any*): bson.Document = Document().append(op, fields)
  def _document(key: String, value: Any): bson.Document = Document().append(key, value)
  def _firstNotNull(items: Any*): bson.Document =
    $first($filter(items, "item", _compare("$ne", "$$item", null)))

  def foldQueriesToDocument(query: Seq[(String, Any)]): bson.Document = {
    val document: bson.Document = Document()
    query.foldLeft(document)((d, f) => d.append(f._1, f._2))
  }
}
