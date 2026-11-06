package srdc.stage.cds.model.fhir

case class Condition(code: Option[CodeableConcept], subject: Reference, onsetDateTime: Option[String], recordedDate: Option[String], clinicalStatus: Option[CodeableConcept])
