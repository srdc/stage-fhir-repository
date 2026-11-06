package srdc.stage.fhir.operation

import akka.http.scaladsl.model.StatusCodes
import io.onfhir.api.{FHIR_BUNDLE_FIELDS, FHIR_BUNDLE_TYPES, FHIR_COMMON_FIELDS, FHIR_DATA_TYPES, FHIR_EXTRA_FIELDS, FHIR_METHOD_NAMES, FHIR_PARAMETER_TYPES}
import io.onfhir.api.model.{FHIROperationRequest, FHIROperationResponse, FHIRResponse, OutcomeIssue, Parameter}
import io.onfhir.api.parsers.FHIRSearchParameterValueParser
import io.onfhir.api.service.FHIROperationHandlerService
import io.onfhir.api.util.FHIRUtil
import io.onfhir.config.FhirConfigurationManager.fhirConfig
import io.onfhir.db.{MongoDB, ResourceManager}
import io.onfhir.db.BsonTransformer._
import io.onfhir.exception.{BadRequestException, NotImplementedException}
import org.json4s.JsonAST.{JArray, JObject}
import org.json4s.{DefaultFormats, Formats}
import org.json4s.JsonDSL._
import org.mongodb.scala.MongoCollection
import org.mongodb.scala.bson.collection.immutable.Document
import org.mongodb.scala.bson.conversions
import org.mongodb.scala.model.Filters.{and, equal, exists, in, not, notEqual}
import org.mongodb.scala.model.Projections.{exclude, fields}
import org.mongodb.scala.model.Sorts.{descending, orderBy}
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import scala.util.Try

/**
 * Created by mustafa on 07.08.2018.
 */
class DistinctPages extends FHIROperationHandlerService {
  private val logger: Logger = LoggerFactory.getLogger("Operation[$distinct-page]")

  private final var defaultCount = 10

  import scala.language.implicitConversions
  implicit def double2Float(x: Double): Float = x.toFloat
  implicit def int2Float(x: Int): Float = x.toFloat

  implicit val formats: Formats = DefaultFormats

  override def executeOperation(operationName: String, operationRequest: FHIROperationRequest, resourceType: Option[String], resourceId: Option[String]): Future[FHIROperationResponse] = {
    handleDistinctPages(operationRequest, resourceType).map(response => {
      val opResponse = new FHIROperationResponse(response.httpStatus)
      opResponse.setComplexOrResourceParam("return", response.responseBody.get)
      opResponse
    })
  }

  def handleDistinctPages(operationRequest: FHIROperationRequest, resourceType: Option[String]): Future[FHIRResponse] = {
    val subject: String = operationRequest.extractParamValue[String]("subject").get
    val codePath: String = operationRequest.extractParamValue[String]("codePath").get
    val dateParam: String = operationRequest.extractParamValue[String]("dateParam").get
    val sortParam: Option[String] = operationRequest.extractParamValue[String]("sortParam")
    val sortDirection: Option[String] = operationRequest.extractParamValue[String]("sortDirection")
    val page: Int = operationRequest.extractParamValue[Int]("_page").getOrElse(1)
    val count: Int = operationRequest.extractParamValue[Int]("_count").getOrElse(this.defaultCount)
    val all: Boolean = operationRequest.extractParamValue[Boolean]("_all").getOrElse(false)
    val queryParams: Option[JObject] = operationRequest.extractParamValue[JObject]("queryParams")

    resourceType match {
      case Some(rtype: String) =>

        val resourceQueryParams = queryParams.flatMap { parameters =>
          Try((parameters \ "parameter").asInstanceOf[JArray].arr.map(param => {
            val paramName: String = (param \ "name").extract[String]
            val paramValue = (param \ "valueString").extract[String]
            paramName -> paramValue
          }).groupBy(_._1).map(i => i._1 -> i._2.map(_._2))).toOption
        }.map(res => FHIRSearchParameterValueParser.parseSearchParameters(rtype, res.toMap)).getOrElse(List.empty)

        getCodes(rtype, subject, codePath, resourceQueryParams) flatMap { seq =>
          val futureMap = seq flatMap { code =>
            code._2 map { c =>
              getLatest(rtype, subject, c, code._1, dateParam, resourceQueryParams, code._2.toList.splitAt(code._2.indexOf(c))._1)
            }
          }
          Future.sequence(futureMap) map { resources =>
            var resourceSeq = resources.filter(_.isDefined).map(_.get).toSeq
            val result = if (sortParam.isDefined) {
              val sortPaths = resolveAllParameterPaths(rtype, sortParam.get)
              sortPaths._1 match {
                case FHIR_PARAMETER_TYPES.NUMBER =>
                  sortResources[Float](resourceSeq, sortPaths)
                case FHIR_PARAMETER_TYPES.QUANTITY =>
                  sortResources[Float](resourceSeq, sortPaths)
                case _ =>
                  sortResources[String](resourceSeq, sortPaths, false)
              }
            } else {
              resourceSeq
            }
            if (sortDirection.getOrElse("asc").equals("desc")) {
              generateResponse(result.reverse, count, page, all)
            } else {
              generateResponse(result, count, page, all)
            }
          }
        }
      case _ => {
        logger.debug("No resource type found")
        throw new BadRequestException(Seq(
          OutcomeIssue(
            FHIRResponse.SEVERITY_CODES.ERROR,
            FHIRResponse.OUTCOME_CODES.INVALID,
            None,
            Some(s"Resource type required"),
            Nil
          )
        ))
      }
    }


  }

  private def sortResources[T:Ordering](resources: Seq[JObject], sortPaths: (String, Seq[String]), isNumber: Boolean = true): Seq[JObject] = {
    resources.sortBy(resource => {
      sortPaths._2.map(path =>
        if (isNumber) {
          FHIRUtil.applySearchParameterPath(path, resource).headOption.map(_.extract[Float]).asInstanceOf[Option[T]]
        } else {
          FHIRUtil.applySearchParameterPath(path, resource).headOption.map(_.extract[String]).asInstanceOf[Option[T]]
        }
      ).find(_.isDefined).flatten
    })
  }

  private def extractPatientId(subject: String): String =
    if ("""Patient\/.+""".r.findFirstMatchIn(subject).isDefined) {
      subject.split('/')(1)
    } else {
      subject
    }

  private def resolveAllParameterPaths(resourceType: String, parameterName: String): (String, Seq[String]) = {
    val searchParameterResourceConf = fhirConfig.resourceQueryParameters(resourceType).get(parameterName)
    val searchParameterCommonConf = fhirConfig.commonQueryParameters.get(parameterName)
    val searchParameterConf = (searchParameterResourceConf, searchParameterCommonConf) match {
      case (Some(conf), _ ) => conf
      case (_, Some(conf)) => conf
      case _ =>
        throw new NotImplementedException(Seq(
          OutcomeIssue(
            FHIRResponse.SEVERITY_CODES.ERROR,
            FHIRResponse.OUTCOME_CODES.NOT_SUPPORTED,
            None,
            Some(s"Search operation doesn't support parameter ($parameterName). Please check the conformance statement of the server !!!"),
            Nil
          )
        ))
    }
    val paths = searchParameterConf.paths.zipWithIndex.map(t =>
      searchParameterConf.targetTypes(t._2).asInstanceOf[String] match {
        case FHIR_DATA_TYPES.PERIOD => t._1.asInstanceOf[String] + ".start"
        case FHIR_DATA_TYPES.QUANTITY => t._1.asInstanceOf[String] + ".value"
        case FHIR_DATA_TYPES.SIMPLE_QUANTITY => t._1.asInstanceOf[String] + ".value"
        case FHIR_DATA_TYPES.TIMING => t._1 + ".event.0"
        case _ => t._1.asInstanceOf[String]
      }
    )
    (searchParameterConf.ptype, paths)
  }

  def getCodes(resourceType: String, subject: String, codePath: String, resourceQueryParams: List[Parameter]): Future[Map[String, Seq[String]]] = {
    // First, arrange the compartment query
    val compartmentParam = FHIRSearchParameterValueParser.constructCompartmentSearchParameter("Patient", extractPatientId(subject), resourceType)

    // Append it to search parameters
    val queryParams = List(compartmentParam) ++ resourceQueryParams

    ResourceManager.constructQuery(resourceType, queryParams).flatMap {
      case finalQuery => {
        val collection = MongoDB.getCollection(resourceType)
        distinctInPathOrder(collection, finalQuery.get, codePath.split(",").toList, List(), Map())
      }

    }
  }

  private def distinctInPathOrder(collection: MongoCollection[Document], filter: conversions.Bson,
                                  pathList: List[String],
                                  checked: List[String], codes: Map[String, Seq[String]]): Future[Map[String, Seq[String]]] = {
    if (pathList.nonEmpty) {
      val path = pathList.head
      val remain = pathList.drop(1)
      val _filter = filter +: checked.map({ checkedPath => not(exists(checkedPath)) })
      val codeMap = if (!codes.isDefinedAt(path)) codes + (path -> Seq.empty) else codes

      collection.distinct[String](path, and(_filter:_*)).toFuture() flatMap {
        (newCodes: Seq[String]) => {
          val uCodes = codeMap.filter(_._1 == path).map { eachVal =>
            path -> (eachVal._2 ++ newCodes)
          } ++ codeMap.filterNot(_._1 == path)

          if (remain.nonEmpty) {
            distinctInPathOrder(collection, filter, remain, checked.+:(path), uCodes)
          } else {
            Future(uCodes)
          }
        }
      }
    } else {
      Future(codes)
    }

  }

  def getLatest(resourceType: String, subject: String, code: String, codePath: String, dateParam: String,
                resourceQueryParams: List[Parameter], excludeCodes: List[String]): Future[Option[JObject]] = {

    // First, arrange the compartment query
    val compartmentParam = FHIRSearchParameterValueParser.constructCompartmentSearchParameter("Patient", extractPatientId(subject), resourceType)

    // Append it to search parameters
    val queryParams = List(compartmentParam) ++ resourceQueryParams

    ResourceManager.constructQuery(resourceType, queryParams).flatMap {
      case finalQuery => {
        val collection = MongoDB.getCollection(resourceType)

        val excludingFields = Array(FHIR_COMMON_FIELDS.MONGO_ID, FHIR_EXTRA_FIELDS.CURRENT)

        val query = collection.find(and(finalQuery.get, equal(codePath, code),
          not(in(codePath, excludeCodes:_*)),
          notEqual(FHIR_EXTRA_FIELDS.METHOD, FHIR_METHOD_NAMES.METHOD_DELETE)))
        query.projection(fields(exclude(excludingFields: _*)))

        val datePaths = resolveAllParameterPaths(resourceType, dateParam)._2 ++ List("meta.lastUpdated")

        query.sort(orderBy(datePaths.map(descending(_)):_*))

        query.toFuture().map(s => s.headOption.map(_.fromBson))
      }
    }
  }

  private def generateResponse(resources: Seq[JObject], count: Int, page: Int, all: Boolean): FHIRResponse = {

    val results = if (all) resources else resources.slice((page-1)*count, page*count)

    logger.debug(s"Returning ${results.size} resources from ${resources.size} ...")

    val entries = results.map(result => FHIRUtil.createBundleEntry(result, FHIR_BUNDLE_TYPES.SEARCH_SET, false, false))

    val bundle = if (all) {
      FHIRUtil.createBundle(FHIR_BUNDLE_TYPES.SEARCH_SET, List(), entries, resources.length, None)
    } else {
      FHIRUtil.createBundle(FHIR_BUNDLE_TYPES.SEARCH_SET, generateLinks(page, count, resources.length), entries, resources.length, None)
    }
    FHIRResponse(StatusCodes.OK, Some(bundle))
  }

  private def generateLinks(page: Int, count: Int, total: Int): List[(String, String)] = {
    val result: ListBuffer[(String, String)] = ListBuffer()
    if (total > 0) {
      result += ((FHIR_BUNDLE_FIELDS.SELF_LINK, page.toString))
      result += ((FHIR_BUNDLE_FIELDS.LAST_LINK, Math.ceil(total.toFloat/count.toFloat).toInt.toString))
      if (page > 1) {
        result += ((FHIR_BUNDLE_FIELDS.PREVIOUS_LINK, (page - 1).toString))
      }
      if (page < (total / count)) {
        result += ((FHIR_BUNDLE_FIELDS.NEXT_LINK, (page + 1).toString))
      }
    }
    result.toList
  }

}

