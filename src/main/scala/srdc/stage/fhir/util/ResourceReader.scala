package srdc.stage.fhir.util

import io.onfhir.api.Resource
import org.json4s.JsonAST.{JArray, JObject, JString}
import org.json4s.jackson.JsonMethods
import org.slf4j.{Logger, LoggerFactory}

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.util.{Failure, Success, Try}

/**
 * Reads the FHIR resources of a configuration folder (recursively); a Bundle file contributes its entries
 */
object ResourceReader {

  private val logger: Logger = LoggerFactory.getLogger(this.getClass)

  def readResourcesInFolder(path: Option[String]): Seq[Resource] = {
    path.map(new File(_)) match {
      case Some(folder) if folder.isDirectory => jsonFiles(folder).flatMap(readFile)
      case Some(folder) =>
        logger.warn(s"Configuration folder ${folder.getPath} does not exist; nothing to store.")
        Nil
      case None => Nil
    }
  }

  private def jsonFiles(folder: File): Seq[File] = {
    Option(folder.listFiles()).toSeq.flatten.sortBy(_.getName).flatMap(file =>
      if (file.isDirectory) jsonFiles(file)
      else if (file.getName.toLowerCase.endsWith(".json")) Seq(file)
      else Nil
    )
  }

  private def readFile(file: File): Seq[Resource] = {
    Try(JsonMethods.parse(new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8).stripPrefix("﻿"))) match {
      case Success(resource: JObject) if (resource \ "resourceType") == JString("Bundle") =>
        (resource \ "entry") match {
          case JArray(entries) => entries.map(_ \ "resource").collect { case entry: JObject => withId(entry, file) }
          case _ => Nil
        }
      case Success(resource: JObject) => Seq(withId(resource, file))
      case Success(_) =>
        logger.warn(s"${file.getPath} is not a FHIR resource; skipped.")
        Nil
      case Failure(e) =>
        logger.error(s"${file.getPath} could not be parsed; skipped.", e)
        Nil
    }
  }

  /** Resources without an id get the file name (without extension) as id */
  private def withId(resource: JObject, file: File): JObject = {
    resource \ "id" match {
      case JString(id) if id.nonEmpty => resource
      case _ => JObject(("id" -> JString(file.getName.replaceAll("(?i)\\.json$", "").replaceAll("[^A-Za-z0-9\\-.]", "-").take(64))) :: resource.obj.filterNot(_._1 == "id"))
    }
  }
}
