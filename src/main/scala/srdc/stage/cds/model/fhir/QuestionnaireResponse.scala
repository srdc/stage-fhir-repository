package srdc.stage.cds.model.fhir

import srdc.stage.cds.util.JsonClass

case class QuestionnaireResponse(resourceType: String,
                                 identifier: Option[Identifier],
                                 meta: Option[Meta],
                                 contained: Option[Array[Questionnaire]],
                                 questionnaire: String,
                                 status: String,
                                 item: Option[Array[Item]]) extends JsonClass
