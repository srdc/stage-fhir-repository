package srdc.stage.cds.model.fhir

import srdc.stage.cds.util.JsonClass

case class Parameters(resourceType: String,
                      var parameter: Seq[Parameter]) extends JsonClass

case class Parameter(name: String,
                     valueString: Any) extends JsonClass
