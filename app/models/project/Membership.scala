/**
 * Copyright(c) 2015 Knut Petter Meen, all rights reserved.
 */
package models.project

import com.mongodb.DBObject
import com.mongodb.casbah.Imports._
import com.mongodb.casbah.commons.MongoDBObject
import core.converters.ObjectBSONConverters
import core.mongodb.SymbioticDB
import core.security.authorization.Role
import models.base.{PersistentType, PersistentTypeConverters, Username}
import models.customer.CustomerId
import models.parties.{OrganizationId, UserId}
import org.bson.types.ObjectId
import play.api.libs.json.{Format, Json}

/**
 * Represents a user involvement in a project. The following constraints apply:
 *
 * - 1 user can have >= 1 project membership
 * - 1 membership must have a unique combination of uid + cid + pid + oid
 *
 */
case class Membership(
  _id: Option[ObjectId],
  id: Option[MembershipId],
  uid: UserId,
  uname: Username,
  cid: CustomerId,
  pid: ProjectId,
  oid: OrganizationId,
  roles: Seq[Role] = Seq.empty[Role]) extends PersistentType

object Membership extends PersistentTypeConverters with SymbioticDB with ObjectBSONConverters[Membership] {

  override val collectionName: String = "project_memberships"

  implicit val memFormat: Format[Membership] = Json.format[Membership]

  override def toBSON(m: Membership): DBObject = {
    val builder = MongoDBObject.newBuilder

    m._id.foreach(builder += "_id" -> _)
    m.id.foreach(builder += "id" -> _.value)
    builder += "uid" -> m.uid.value
    builder += "uname" -> m.uname.value
    builder += "cid" -> m.cid.value
    builder += "pid" -> m.pid.value
    builder += "oid" -> m.oid.value
    builder += "roles" -> m.roles.map(Role.toStringValue)

    builder.result()
  }

  override def fromBSON(d: DBObject): Membership = {
    Membership(
      _id = d.getAs[ObjectId]("_id"),
      id = d.getAs[String]("id"),
      uid = d.as[String]("uid"),
      uname = Username(d.as[String]("uname")),
      cid = d.as[String]("cid"),
      pid = d.as[String]("pid"),
      oid = d.as[String]("oid"),
      roles = d.as[Seq[String]]("roles").map(Role.fromStringValue)
    )
  }
}