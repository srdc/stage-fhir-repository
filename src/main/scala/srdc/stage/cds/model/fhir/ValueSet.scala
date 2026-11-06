package srdc.stage.cds.model.fhir

import srdc.stage.cds.util.JsonClass

case class ValueSet(id: Option[String], compose: Compose, url: Option[String] = None, status: Option[String] = None, date: Option[String] = None, resourceType: String = "ValueSet") extends JsonClass

case class Compose(include: Option[Seq[Concept]])

case class Concept(system: Option[String], concept: Option[Seq[Coding]])