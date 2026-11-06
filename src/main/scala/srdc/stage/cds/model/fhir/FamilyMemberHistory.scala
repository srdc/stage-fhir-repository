package srdc.stage.cds.model.fhir

import srdc.stage.cds.util.JsonClass

case class FamilyMemberHistory (condition: Option[Seq[FamilyMemberHistoryCondition]])

final case class FamilyMemberHistoryCondition(code: CodeableConcept) extends JsonClass