package srdc.stage.cds.model.fhir

import srdc.stage.cds.util.JsonClass

final case class Reference(reference: String,
                           display: Option[String] = None,
                           extension: Option[Seq[Extension]] = None) extends JsonClass {

  def withExtension(extension: Seq[Extension]): Reference = {
    this.copy(extension = Some(extension))
  }
}
