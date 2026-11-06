package srdc.stage.cds.model.fhir

import srdc.stage.cds.util.JsonClass

final case class Period(start: Option[String],
                        end: Option[String]) extends JsonClass
