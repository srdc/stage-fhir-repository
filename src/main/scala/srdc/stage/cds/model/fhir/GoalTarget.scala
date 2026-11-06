package srdc.stage.cds.model.fhir

case class GoalTarget(
                     measure: Option[CodeableConcept],
                     detailRange: Option[TargetRange]
                     )
