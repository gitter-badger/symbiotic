/**
 * Copyright(c) 2015 Knut Petter Meen, all rights reserved.
 */
package repository.mongodb.docmanagement

import java.util.UUID

import com.google.inject.Singleton
import com.mongodb.casbah.Imports._
import com.mongodb.casbah.commons.TypeImports
import models.docmanagement.CommandStatusTypes.{CommandError, CommandKo, CommandOk, CommandStatus}
import models.docmanagement.MetadataKeys._
import models.docmanagement._
import models.party.PartyBaseTypes.UserId
import org.slf4j.LoggerFactory
import repository.FolderRepository
import repository.mongodb.bson.BSONConverters.Implicits._

import scala.util.Try

@Singleton
class MongoDBFolderRepository extends FolderRepository with MongoFSRepository {

  val logger = LoggerFactory.getLogger(this.getClass)

  override def get(folderId: FolderId)(implicit uid: UserId): Option[Folder] = {
    collection.findOne(MongoDBObject(
      OwnerKey.full -> uid.value,
      FidKey.full -> folderId.value,
      IsFolderKey.full -> true
    )).map(folder_fromBSON)
  }

  override def exists(at: Path)(implicit uid: UserId): Boolean =
    collection.findOne(MongoDBObject(
      OwnerKey.full -> uid.value,
      PathKey.full -> at.materialize,
      IsFolderKey.full -> true
    )).isDefined

  override def filterMissing(p: Path)(implicit uid: UserId): List[Path] = {

    case class CurrPathMiss(path: String, missing: List[Path])

    val segments = p.path.split("/").filterNot(_.isEmpty)

    // Left fold over the path segments and identify the ones that doesn't exist
    segments.foldLeft[CurrPathMiss](CurrPathMiss("", List.empty))((prev: CurrPathMiss, seg: String) => {
      val p = if (prev.path.isEmpty) seg else s"${prev.path}/$seg"
      val next = Path(p)
      if (exists(next)) CurrPathMiss(p, prev.missing)
      else CurrPathMiss(p, next +: prev.missing)
    }).missing
  }

  override def save(f: Folder)(implicit uid: UserId): Option[FileId] = {
    if (!exists(f)) {
      val id = f.id.getOrElse(UUID.randomUUID())
      val fid = Some(f.metadata.fid.getOrElse(FileId.create()))
      val sd = MongoDBObject(
        "_id" -> id.toString,
        "filename" -> f.filename,
        MetadataKey -> managedfmd_toBSON(f.metadata.copy(fid = fid))
      )
      Try {
        logger.debug(s"Creating folder $f")
        collection.save(sd)
        fid
      }.recover {
        case e: Throwable =>
          logger.error(s"An error occurred trying to save $f", e)
          None
      }.toOption.flatten
    } else {
      None
    }
  }

  override def move(orig: Path, mod: Path)(implicit uid: UserId): CommandStatus[Int] = {
    val qry = MongoDBObject(
      OwnerKey.full -> uid.value,
      PathKey.full -> orig.materialize
    )
    val upd = $set("filename" -> mod.nameOfLast, PathKey.full -> mod.materialize)

    Try {
      val res = collection.update(qry, upd)
      if (res.getN > 0) CommandOk(res.getN)
      else CommandKo(0)
    }.recover {
      case e: Throwable => CommandError(0, Option(e.getMessage))
    }.get
  }

}