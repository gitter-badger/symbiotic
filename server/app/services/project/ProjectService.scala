/**
 * Copyright(c) 2015 Knut Petter Meen, all rights reserved.
 */
package services.project

import com.mongodb.casbah.Imports._
import core.lib.{Failure, Created, Updated, SuccessOrFailure}
import core.mongodb.{DefaultDB, WithMongoIndex}
import models.party.PartyBaseTypes.OrganisationId
import models.project.{Project, ProjectId}
import org.slf4j.LoggerFactory

import scala.util.Try

// TODO: This is really a repository implementation...refactor once the repo interfaces have been defined!
object ProjectService extends DefaultDB with WithMongoIndex {

  val logger = LoggerFactory.getLogger(ProjectService.getClass)

  override val collectionName: String = "projects"

  ensureIndex()

  override def ensureIndex(): Unit = index(List(
    Indexable("id", unique = true),
    Indexable("oid", unique = false),
    Indexable("title", unique = false)
  ), collection)

  /**
   *
   * @param proj
   */
  def save(proj: Project): SuccessOrFailure =
    Try {
      val res = collection.save(proj)
      logger.debug(res.toString)

      if (res.isUpdateOfExisting) Updated
      else Created
    }.recover {
      case t =>
        logger.warn(s"An error occurred when saving $proj", t)
        throw t
    }.getOrElse {
      Failure(s"Project $proj could not be saved")
    }

  /**
   *
   * @param pid
   * @return
   */
  def findById(pid: ProjectId): Option[Project] = {
    logger.info(s"Building query to find Project with $pid")
    collection.findOne(MongoDBObject("id" -> pid.value)).map(Project.fromBSON)
  }

  /**
   *
   * @param oid
   * @return
   */
  def listByOrgId(oid: OrganisationId): Seq[Project] =
    collection.find(MongoDBObject("oid" -> oid.value)).map(Project.fromBSON).toSeq

}
