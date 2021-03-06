package net.scalytica.symbiotic.api.types

import java.util.UUID

trait IdOps[A <: Id] {

  implicit def asId(s: String): A

  implicit def asOptId(maybeId: Option[String]): Option[A] =
    maybeId.map(s => asId(s))

  implicit def asOptId(s: String): Option[A] = asOptId(Option(s))

  def fromUuid(uuid: java.util.UUID): A = asId(uuid.toString)

  @throws(classOf[IllegalArgumentException])
  def unsafeAsUuid(id: A): UUID = java.util.UUID.fromString(id.value)

  def create(): A = asId(java.util.UUID.randomUUID.toString)

  def createOpt(): Option[A] = asOptId(java.util.UUID.randomUUID.toString)

}
