package srdc.stage.fhir.model.fhir

import srdc.stage.fhir.util.JsonClass

final case class Period(start: Option[String],
                        end: Option[String]) extends JsonClass
