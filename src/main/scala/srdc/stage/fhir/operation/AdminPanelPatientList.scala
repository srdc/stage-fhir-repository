package srdc.stage.fhir.operation

import akka.http.scaladsl.model.StatusCodes
import io.onfhir.Onfhir
import io.onfhir.api.{FHIR_BUNDLE_TYPES, Resource}
import io.onfhir.api.client.OnFhirLocalClient
import io.onfhir.api.model.{FHIROperationRequest, FHIROperationResponse}
import io.onfhir.config.IFhirConfigurationManager
import io.onfhir.api.service.FHIROperationHandlerService
import io.onfhir.api.util.FHIRUtil
import io.onfhir.db.MongoDB
import io.onfhir.util.JsonFormatter.formats
import org.json4s.{Extraction, JArray, JField, JObject, JString}
import org.slf4j.{Logger, LoggerFactory}
import srdc.stage.fhir.model.fhir.{ContactPoint, HumanName, Patient}
import srdc.stage.fhir.util.AggregationQueryHelper._
import srdc.stage.fhir.service.OnauthService
import srdc.stage.fhir.util.FHIRResourceTypes

import scala.concurrent.Future
import scala.util.Try

class AdminPanelPatientList(fhirConfigurationManager: IFhirConfigurationManager) extends FHIROperationHandlerService(fhirConfigurationManager) {
  private val logger: Logger = LoggerFactory.getLogger("Operation[$admin-panel-patient-list]")

  implicit val actorSystem = Onfhir.actorSystem

  private final val defaultCount = 10

  override def executeOperation(operationName: String, operationRequest: FHIROperationRequest, resourceType: Option[String], resourceId: Option[String]): Future[FHIROperationResponse] = {
    val token = if (OnauthService.isSecure) OnauthService.getToken() else ""
    val page = operationRequest.extractParamValue[Int]("_page")
    val count = operationRequest.extractParamValue[Int]("_count")
    val all = operationRequest.extractParamValue[Boolean]("all")
    handleOperation(token, page, count, all) map { responseBundle =>
      val operationResponse = new FHIROperationResponse(StatusCodes.OK)
      operationResponse.setResponse(responseBundle)
      operationResponse
    }
  }

  private def createBundle(resources: Seq[Resource], id: Option[String] = None, total: Option[Long] = None, next: Option[Int] = None): Resource = {
    val entries = resources.map(result => JObject("resource" -> result))
    val bundle = FHIRUtil.createBundle(FHIR_BUNDLE_TYPES.SEARCH_SET,
      next.map(n => List(("next", s"$n"))).getOrElse(List()), entries,
      total.getOrElse(resources.length), None)
    bundle match {
      case JObject(l) => JObject(l map {
        case ("id", _id) => ("id", id.map(JString).getOrElse(_id))
        case other => other
      })
    }
  }

  private def handleOperation(token: String, page: Option[Int], count: Option[Int], all: Option[Boolean]): Future[Resource] = {
    (if (all.contains(true)) {
      getAllPatients(token) map { results =>
        (Some(results._1.length.toLong), results._3.length.toLong, results)
      }
    } else {
      getPatientsWithAccounts(token, page.getOrElse(1), count) flatMap { results =>
        getActiveCarePlanCount() map { cpCount =>
          (results._5, cpCount.toLong, (results._1, results._2, results._3, results._4))
        }
      }
    }) map { results =>
      val (patientCount, cpCount, _) = results
      val (patients, accounts, carePlans, next) = results._3
      createBundle(
        Seq(
          createBundle(patients, Some("patients"), patientCount),
          createBundle(accounts, Some("accounts")),
          createBundle(carePlans, Some("care-plans"), Some(cpCount))),
        None, None, next)
    }
  }

  private def getAllPatients(token: String, page: Int = 1): Future[(Seq[Resource], Seq[Resource], Seq[Resource], Option[Int])] = {
    getPatientsWithAccounts(token, page) flatMap { results =>
      val (patients, accounts, carePlans, next, _) = results
      if (next.nonEmpty) {
        getAllPatients(token, next.get) map { _results =>
          val (_patients, _accounts, _carePlans, _next) = _results
          (patients ++ _patients, accounts ++ _accounts, carePlans ++ _carePlans, _next)
        }
      } else {
        Future.apply((patients, accounts, carePlans, None))
      }
    }
  }

  private def getPatientsWithAccounts(token: String, page: Int, count: Option[Int] = None): Future[(Seq[Resource], Seq[Resource], Seq[Resource], Option[Int], Option[Long])] = {
    OnFhirLocalClient.search(FHIRResourceTypes.PATIENT)
      .where("_page", s"$page")
      .where("_count", s"${count.getOrElse(defaultCount)}")
      .executeAndReturnBundle() flatMap { patientBundle =>
        Future.sequence(patientBundle.searchResults.map(patient => {
          val patientId = (patient \ "id").extract[String]
          val patientAccount = if (OnauthService.isSecure && patient.values.contains("active") && (patient \ "active").extract[Boolean])
            OnauthService.getUser(patientId, token) map { case (Some(user), _, _) => Some(user)
            case (_, _, _) => None
          } else Future.apply(None)
          val carePlan = OnFhirLocalClient.search(FHIRResourceTypes.CAREPLAN)
            .where("subject", s"Patient/$patientId")
            .where("status", "active,draft")
            .sortOnDesc("date")
            .where("_count", "1")
            .executeAndReturnBundle() map { cpBundle => cpBundle.searchResults.headOption }
          Future.sequence(Seq(patientAccount, carePlan)) map { results =>
            (results.head, results(1))
          }
        })) map { patientsWithAccountAndCarePlan =>
          val accounts = patientsWithAccountAndCarePlan.map(_._1).filter(_.nonEmpty).map(_.get).map(account => {
            val id = (account \ "sub").extract[String]
            val username = (account \ "account" \ "username").extract[String]
            val email = Try((account \ "account" \ "email").extract[String]).toOption
            val family = Try((account \ "profile" \ "family_name").extract[String]).toOption
            val given = Try((account \ "profile" \ "given_name").extract[String].split(" ")).toOption
            val gender = Try((account \ "profile" \ "gender").extract[String]).toOption
            val name = if (given.nonEmpty || family.nonEmpty) Some(HumanName(family, given)) else None
            Extraction.decompose(Patient(FHIRResourceTypes.PATIENT, s"$id|$username", Some(true), None, gender, None, name.map(Array(_)), None,
                email.map(_ => Array(ContactPoint(Some("email"), email, None, None, None))))).asInstanceOf[Resource]
          })
          val carePlans = patientsWithAccountAndCarePlan.map(_._2).filter(_.nonEmpty).map(_.get)
          val patients = patientBundle.searchResults
          (patients, accounts, carePlans, if (patientBundle.hasNext()) Some(page + 1) else None, patientBundle.total)
        }
    }
  }

  private def getActiveCarePlanCount(): Future[Int] = {
    val collection = MongoDB.getCollection("CarePlan")
    collection.aggregate(Seq(
      $match(("__method", _document("$ne", "DELETE")),
        ("$or", Seq(_document("status", "draft"), _document("status", "active")))),
      $lookup("Patient", "subject.reference.__rid", "id", "patient"),
      $match(("patient.__method", _document("$ne", "DELETE")), ("patient.active", true)),
      $group("$patient.id")
    )).toFuture().map(_.size)
  }
}
