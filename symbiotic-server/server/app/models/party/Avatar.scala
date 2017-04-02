/**
 * Copyright(c) 2017 Knut Petter Meen, all rights reserved.
 */
package models.party

import java.io.InputStream
import java.util.UUID

import net.scalytica.symbiotic.data.PartyBaseTypes.UserId
import net.scalytica.symbiotic.data.GridFSDocument
import org.joda.time.DateTime

case class Avatar(
    id: Option[UUID] = None,
    uploadDate: Option[DateTime] = None,
    length: Option[String] = None,
    filename: String,
    contentType: Option[String] = None,
    stream: Option[InputStream] = None,
    metadata: AvatarMetadata
) extends GridFSDocument[AvatarMetadata]

object Avatar {

  def apply(
      uid: UserId,
      ctype: Option[String],
      s: Option[InputStream]
  ): Avatar =
    Avatar(
      filename = uid.value,
      contentType = ctype,
      stream = s,
      metadata = AvatarMetadata(uid)
    )

}