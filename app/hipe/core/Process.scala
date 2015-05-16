/**
 * Copyright(c) 2015 Knut Petter Meen, all rights reserved.
 */
package hipe.core

import com.mongodb.casbah.commons.Imports._
import core.converters.{WithObjectBSONConverters, WithDateTimeConverters}
import core.mongodb.{WithMongoIndex, WithMongo}
import org.bson.types.ObjectId
import play.api.Logger
import play.api.libs.functional.syntax._
import play.api.libs.json._

/**
 * This case class holds actual process configuration.
 *
 * @param id ProcessId The unique identifier for the Process
 * @param name String with a readable name
 * @param strict Boolean flag indicating if movement of tasks in the process should be free-form/open or restricted
 * @param description String Readable text describing the process
 * @param stepList List of Steps in the process.
 */
case class Process(
  id: Option[ProcessId],
  name: String,
  strict: Boolean = false,
  description: Option[String] = None,
  stepList: StepList = StepList.empty) {

  def step(id: StepId): Option[Step] = stepList.find(s => id == s.id)

}

/**
 * The companion, with JSON and BSON converters for exposure and persistence.
 */
object Process extends WithObjectBSONConverters[Process] with WithDateTimeConverters with WithMongo with WithMongoIndex {

  val logger = Logger(classOf[Process])

  implicit val procFormat: Format[Process] = (
    (__ \ "id").formatNullable[ProcessId] and
      (__ \ "name").format[String] and
      (__ \ "strict").format[Boolean] and
      (__ \ "description").formatNullable[String] and
      (__ \ "steps").format[StepList]
    )(Process.apply, unlift(Process.unapply))

  implicit override def toBSON(x: Process): DBObject = {
    val builder = MongoDBObject.newBuilder

    x.id.foreach(builder += "_id" -> _.asOID)
    builder += "name" -> x.name
    builder += "strict" -> x.strict
    x.description.foreach(builder += "description" -> _)
    builder += "steps" -> StepList.toBSON(x.stepList)

    builder.result()
  }

  override def fromBSON(dbo: DBObject): Process = {
    Process(
      id = ProcessId.asOptId(dbo.getAs[ObjectId]("_id")),
      name = dbo.as[String]("name"),
      strict = dbo.getAs[Boolean]("strict").getOrElse(false),
      description = dbo.getAs[String]("description"),
      stepList = StepList.fromBSON(dbo.as[MongoDBList]("steps"))
    )
  }

  override val collectionName: String = "processes"

  override def ensureIndex(): Unit = ???

  def save(proc: Process): Unit = {
    val res = collection.save(proc)

    if (res.isUpdateOfExisting) logger.info("Updated existing user")
    else logger.info("Inserted new user")

    logger.debug(res.toString)
  }

  def findById(procId: ProcessId): Option[Process] = {
    collection.findOneByID(procId.asOID).map(pct => fromBSON(pct))
  }

  def delete(procId: ProcessId): Unit = {
    collection.remove(MongoDBObject("_id" -> procId.asOID))
  }
}