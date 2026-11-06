package srdc.stage.cds.model.fhir

import srdc.stage.cds.util.CareTeamStatus.CareTeamStatus
import srdc.stage.cds.util.JsonClass

final case class CareTeam(resourceType: String,
                          status: CareTeamStatus,
                          name: Option[String],
                          priority: Option[String],
                          subject: Option[Reference],
                          participant: Option[Seq[CareTeamParticipant]]
                          ) extends JsonClass

final case class CareTeamParticipant(role: Option[Seq[CodeableConcept]],
                                     member: Option[Reference],
                                     extension: Seq[Extension]) extends JsonClass
