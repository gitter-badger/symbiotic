package net.scalytica.symbiotic.api.types

import java.util.UUID

import akka.stream.Materializer
import akka.stream.scaladsl.StreamConverters
import org.joda.time.DateTime

trait SymbioticDocument[A <: BaseMetadata] {

  def id: Option[UUID]
  def filename: String
  // For files this is the contentType. For folders it indicates the folder type
  def fileType: Option[String]
  def createdDate: Option[DateTime]
  // Using String for length to prevent data loss when using lesser protocols
  // like JSON/JS. Where the precision is not all that good.
  def length: Option[String]
  def stream: Option[FileStream]
  def metadata: A

  def inputStream(implicit mat: Materializer): Option[java.io.InputStream] =
    stream.map(_.runWith(StreamConverters.asInputStream()))
}
