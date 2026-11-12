package srdc.stage.cds.service

import io.onfhir.Onfhir.actorSystem
import io.onfhir.cds.api.model.CdsResponse
import io.onfhir.cds.service.{BaseCdsService, CdsServiceContext, CdsServiceRequest}
import io.onfhir.util.JsonFormatter._
import io.onfhir.util.JsonFormatter.formats
import org.json4s.JsonAST.{JArray, JDouble, JObject, JString}
import srdc.stage.cds.client.LLMClient
import srdc.stage.cds.model.fhir.Observation

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

import org.json4s.JsonDSL._
import org.json4s._

class AskLlmService(cdsServiceContext: CdsServiceContext) extends BaseCdsService(cdsServiceContext) {

  override def executeCds(cdsServiceRequest: CdsServiceRequest)(implicit ex: ExecutionContext): Future[CdsResponse] = {
    val sessionId = Try(cdsServiceRequest.getStringContext("sessionId")).toOption
    val question = Try(cdsServiceRequest.getStringContext("question")).toOption

    if (question.isEmpty || sessionId.isEmpty) {
      throw new Exception("Question and session ID should be provided!")
    }

    val responseBuilder = createResponse(cdsServiceRequest)

    // The plain user question: fhir-rag-med keeps the context (the patient's risks, SHAP values and the plot shown)
    // in the session, so the optional context parameters 'disease' and 'chartType' are not added to it
    LLMClient.ask(sessionId.get, question.get) map { response =>
      val withCard = responseBuilder.withCard(_.loadCardWithPostTranslation("card-llm-response",
        "text" -> response.text.replaceAll("\n", "\\\\n"),
        "sessionId" -> response.session_id,
        "suggestedQuestions" -> toCdsHooksSuggestions(response.suggested_questions)
      ))
      withCard.cdsResponse
    }

  }

  private def toCdsHooksSuggestions(suggestions: List[String]) = {
    JArray(
      if (suggestions.isEmpty) List.empty
      else suggestions.zipWithIndex.map{ case (suggestion, i) =>
        ("label" -> suggestion) ~ ("uuid" -> s"suggested-question-$i")
      }
    ).toJson
  }
}
