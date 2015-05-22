/**
 * Copyright(c) 2015 Knut Petter Meen, all rights reserved.
 */
package models.project

import core.converters.IdConverters
import models.base.Id

/**
 *
 * @param value
 */
case class ProjectId(value: String) extends Id

object ProjectId extends IdConverters[ProjectId] {

  implicit val projectIdReads = reads(ProjectId.apply)
  implicit val projectIdWrites = writes

  override implicit def asId(s: String): ProjectId = ProjectId(s)

}