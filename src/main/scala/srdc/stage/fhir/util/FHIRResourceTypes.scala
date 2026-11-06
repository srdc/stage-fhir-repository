package srdc.stage.fhir.util

object FHIRResourceTypes extends Enumeration {
  type FHIRResourceTypes = String
  val ALLERGY_INTOLERANCE = "AllergyIntolerance"
  val APPOINTMENT = "Appointment"
  val BASIC = "Basic"
  val CAREPLAN = "CarePlan"
  val CARETEAM = "CareTeam"
  val COMMUNICATION = "Communication"
  val COMMUNICATION_REQUEST = "CommunicationRequest"
  val CONDITION = "Condition"
  val EPISODE_OF_CARE = "EpisodeOfCare"
  val GOAL = "Goal"
  val MEDICATION = "Medication"
  val MEDICATION_REQUEST = "MedicationRequest"
  val MEDICATION_STATEMENT = "MedicationStatement"
  val OBSERVATION = "Observation"
  val PATIENT = "Patient"
  val PARAMETERS = "Parameters"
  val PROCEDURE = "Procedure"
  val QUESTIONNAIRE_RESPONSE = "QuestionnaireResponse"
  val VALUE_SET = "ValueSet"
  val RELATED_PERSON = "RelatedPerson"
  val PERSON = "Person"
  val PRACTITIONER = "Practitioner"
  val SERVICE_REQUEST = "ServiceRequest"
}
