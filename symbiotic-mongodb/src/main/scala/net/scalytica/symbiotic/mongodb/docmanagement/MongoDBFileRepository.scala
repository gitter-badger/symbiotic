package net.scalytica.symbiotic.mongodb.docmanagement

import java.util.UUID

import com.mongodb.casbah.Imports._
import com.mongodb.casbah.gridfs.GridFSDBFile
import com.mongodb.gridfs.{GridFSDBFile => MongoGridFSDBFile}
import com.typesafe.config.Config
import net.scalytica.symbiotic.api.persistence.FileRepository
import net.scalytica.symbiotic.api.types.Lock.LockOpStatusTypes._
import net.scalytica.symbiotic.api.types.MetadataKeys._
import net.scalytica.symbiotic.api.types.PartyBaseTypes.UserId
import net.scalytica.symbiotic.api.types._
import net.scalytica.symbiotic.mongodb.bson.BSONConverters.Implicits._
import org.joda.time.DateTime
import org.slf4j.LoggerFactory

import scala.util.Try

class MongoDBFileRepository(
    val configuration: Config
) extends FileRepository
    with MongoFSRepository {

  private val logger = LoggerFactory.getLogger(this.getClass)

  override def save(
      f: File
  )(implicit uid: UserId, tu: TransUserId): Option[FileId] = {
    val id   = UUID.randomUUID()
    val fid  = f.metadata.fid.getOrElse(FileId.create())
    val file = f.copy(metadata = f.metadata.copy(fid = Some(fid)))
    Try {
      f.stream
        .flatMap(
          s =>
            gfs(s) { gf =>
              gf.filename = file.filename
              file.contentType.foreach(gf.contentType = _)
              gf.metaData = managedfmd_toBSON(file.metadata)
              gf += ("_id" -> id.toString) // TODO: Verify this with the tests
          }
        )
        .map(_ => fid)
    }.recover {
      case e: Throwable =>
        logger.error(s"An error occurred trying to save $f", e)
        None
    }.toOption.flatten
  }

  override def get(
      id: FileId
  )(implicit uid: UserId, tu: TransUserId): Option[File] =
    gfs.findOne(MongoDBObject("_id" -> id.toString))

  override def getLatest(
      fid: FileId
  )(implicit uid: UserId, tu: TransUserId): Option[File] =
    collection
      .find(MongoDBObject(FidKey.full -> fid.value))
      .sort(MongoDBObject(VersionKey.full -> -1))
      .map(managedfile_fromBSON)
      .toSeq
      .headOption
      .flatMap(f => get(f.id.get))

  override def move(
      filename: String,
      orig: Path,
      mod: Path
  )(implicit uid: UserId, tu: TransUserId): Option[File] = {
    val q = MongoDBObject(
      "filename"    -> filename,
      OwnerKey.full -> uid.value,
      PathKey.full  -> orig.materialize
    )
    val upd = $set(PathKey.full -> mod.materialize)

    val res = collection.update(q, upd, multi = true)
    if (res.getN > 0) findLatest(filename, Some(mod))
    else None // TODO: Handle this situation properly...
  }

  override def find(
      filename: String,
      maybePath: Option[Path]
  )(implicit uid: UserId, tu: TransUserId): Seq[File] = {
    val fn = MongoDBObject("filename" -> filename, OwnerKey.full -> uid.value)
    val q = maybePath.fold(fn)(
      p => fn ++ MongoDBObject(PathKey.full -> p.materialize)
    )
    val sort = MongoDBObject("uploadDate" -> -1)

    gfs
      .files(q)
      .sort(sort)
      .collect[File] {
        case f: DBObject =>
          file_fromGridFS(new GridFSDBFile(f.asInstanceOf[MongoGridFSDBFile]))
      }
      .toSeq
  }

  override def findLatest(
      filename: String,
      maybePath: Option[Path]
  )(implicit uid: UserId, tu: TransUserId): Option[File] =
    find(filename, maybePath).headOption

  override def listFiles(
      path: String
  )(implicit uid: UserId, tu: TransUserId): Seq[File] =
    gfs
      .files(
        MongoDBObject(
          OwnerKey.full    -> uid.value,
          PathKey.full     -> path,
          IsFolderKey.full -> false
        )
      )
      .map(d => file_fromBSON(d))
      .toSeq

  override def locked(
      fid: FileId
  )(implicit uid: UserId, tu: TransUserId): Option[UserId] =
    getLatest(fid).flatMap(fw => fw.metadata.lock.map(l => l.by))

  private[this] def lockedAnd[A](fid: FileId)(
      f: (Option[UserId], FileId) => A
  )(implicit uid: UserId, tu: TransUserId): Option[A] =
    getLatest(fid).map(file => f(file.metadata.lock.map(_.by), file.id.get))

  override def lock(
      fid: FileId
  )(
      implicit uid: UserId,
      tu: TransUserId
  ): LockOpStatus[_ <: Option[Lock]] = {
    // Only permit locking if not already locked
    lockedAnd(fid) {
      case (maybeUid, oid) =>
        maybeUid.map[LockOpStatus[Option[Lock]]](Locked.apply).getOrElse {
          val lock = Lock(uid, DateTime.now())
          val qry  = MongoDBObject(FidKey.full -> fid.value)
          val upd  = $set(LockKey.full -> lock_toBSON(lock))

          Try {
            if (collection.update(qry, upd).getN > 0) LockApplied(Option(lock))
            else LockError("Locking query did not match any documents")
          }.recover {
            case e: Throwable =>
              LockError(
                s"An error occured trying to unlock $fid: ${e.getMessage}"
              )

          }.get
        }
    }.getOrElse(LockError(s"File $fid was not found"))
  }

  override def unlock(
      fid: FileId
  )(implicit uid: UserId, tu: TransUserId): LockOpStatus[_ <: String] = {
    lockedAnd(fid) {
      case (maybeUid, id) =>
        maybeUid.fold[LockOpStatus[_ <: String]](NotLocked()) { usrId =>
          if (uid == usrId) {
            Try {
              val res = collection.update(
                MongoDBObject("_id" -> id.toString),
                $unset(LockKey.full)
              )
              if (res.getN > 0) LockApplied(s"Successfully unlocked $fid")
              else LockError("Unlocking query did not match any documents")
            }.recover {
              case e: Throwable =>
                LockError(
                  s"An error occured trying to unlock $fid: ${e.getMessage}"
                )

            }.get
          } else NotAllowed()
        }
    }.getOrElse(LockError(s"File $fid was not found"))
  }

}
