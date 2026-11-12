package srdc.stage.cds.model

/**
 * Presentation data of the diseases the STAGE model predicts: the group the apps show a disease in, and the sex a
 * disease applies to (sex-specific cancers).
 *
 * Which diseases are deployed comes from the ML service (shap-client `GET /models/stage/diseases`, see
 * [[srdc.stage.cds.client.MLModelClient.getDiseases]]). Names are the ones shap-client uses; it also accepts '_'
 * instead of spaces. A deployed disease missing here is shown in group [[OtherGroup]], for all patients. The names
 * below, the diseases of model stage_v0_3_1, are also the fallback list when the ML service cannot be reached.
 */
object StageDiseases {
  case class Disease(name: String, group: String, sex: Option[String] = None)

  final val OtherGroup = "Other"

  final val Known: Seq[Disease] = Seq(
    Disease("Atrial Fibrillation", "Cardiovascular"),
    Disease("Coronary Heart Disease", "Cardiovascular"),
    Disease("Heart Failure", "Cardiovascular"),
    Disease("Hypertension", "Cardiovascular"),
    Disease("Peripheral Vascular Disease", "Cardiovascular"),
    Disease("Stroke Cerebrovascular", "Cardiovascular"),
    Disease("Diabetes Mellitus", "Metabolic"),
    Disease("Hyperlipidemia", "Metabolic"),
    Disease("Hypothyroidism", "Metabolic"),
    Disease("Chronic Kidney Disease", "Kidney and liver"),
    Disease("Chronic Liver Disease", "Kidney and liver"),
    Disease("COPD", "Respiratory"),
    Disease("Sleep Apnea", "Respiratory"),
    Disease("Osteoarthritis", "Musculoskeletal"),
    Disease("Osteoporosis", "Musculoskeletal"),
    Disease("Rheumatoid Arthritis", "Musculoskeletal"),
    Disease("Anxiety", "Mental health"),
    Disease("Depression", "Mental health"),
    Disease("Substance Use Disorder", "Mental health"),
    Disease("Cancer Breast", "Cancer", Some("female")),
    Disease("Cancer Colorectal", "Cancer"),
    Disease("Cancer Lung", "Cancer"),
    Disease("Cancer Prostate", "Cancer", Some("male")),
    Disease("Cancer Other", "Cancer")
  )

  private val byName: Map[String, Disease] = Known.map(d => normalize(d.name) -> d).toMap

  /** The presentation data of a disease name of the ML service ('_' or spaces) */
  def describe(name: String): Disease = byName.getOrElse(normalize(name), Disease(name, OtherGroup))

  private def normalize(name: String): String = name.replace('_', ' ').trim.toLowerCase
}
