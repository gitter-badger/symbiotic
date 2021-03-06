package net.scalytica.symbiotic.mongodb.bson

import java.util.UUID

import akka.stream.scaladsl.StreamConverters
import com.mongodb.DBObject
import com.mongodb.casbah.commons.Imports._
import com.mongodb.casbah.commons.MongoDBObject
import com.mongodb.casbah.gridfs.GridFSDBFile
import net.scalytica.symbiotic.api.types.CustomMetadataAttributes.Implicits._
import net.scalytica.symbiotic.api.types.CustomMetadataAttributes.MetadataMap
import net.scalytica.symbiotic.api.types.MetadataKeys._
import net.scalytica.symbiotic.api.types.ResourceParties._
import net.scalytica.symbiotic.api.types.{ManagedFile, _}
import net.scalytica.symbiotic.mongodb.bson.BaseBSONConverters.DateTimeBSONConverter // scalastyle:ignore
import org.joda.time.DateTime

object BSONConverters {

  object Implicits extends FileFolderBSONConverter with DateTimeBSONConverter

  trait LockBSONConverter extends DateTimeBSONConverter {
    implicit def lockToBSON(lock: Lock): MongoDBObject = {
      MongoDBObject(
        "by"   -> lock.by.value,
        "date" -> lock.date.toDate
      )
    }

    implicit def lockFromBSON(
        dbo: MongoDBObject
    )(implicit ctx: SymbioticContext): Lock = {
      Lock(
        by = ctx.toUserId(dbo.as[String]("by")),
        date = dbo.as[java.util.Date]("date")
      )
    }
  }

  trait ManagedMetadataBSONConverter extends LockBSONConverter {

    implicit def extraAttribsToBSON(mm: MetadataMap): DBObject = {
      mm.plainMap.map {
        case (key, v: DateTime) => key -> v.toDate
        case kv                 => kv
      }.asDBObject
    }

    implicit def extraAttribsFromBSON(dbo: MongoDBObject): MetadataMap = {
      // implicit conversion to a MetadataMap
      dbo.toMap[String, Any]
    }

    implicit def optExtraAttribsFromBSON(
        maybeDbo: Option[MongoDBObject]
    ): Option[MetadataMap] = maybeDbo.map(extraAttribsFromBSON)

    implicit def ownerToBSON(o: Owner): DBObject = {
      MongoDBObject(
        OwnerIdKey.key   -> o.id.value,
        OwnerTypeKey.key -> o.tpe.tpe
      )
    }

    implicit def ownerFromBSON(
        dbo: MongoDBObject
    )(implicit ctx: SymbioticContext): Owner = {
      val tpe: Type = dbo.as[String](OwnerTypeKey.key)
      val idStr     = dbo.as[String](OwnerIdKey.key)
      val id = tpe match {
        case Usr => ctx.toUserId(idStr)
        case Org => ctx.toOrgId(idStr)
      }
      Owner(id, tpe)
    }

    implicit def allowedPartyToBSON(ap: AllowedParty): DBObject = {
      MongoDBObject(
        AccessibleByIdKey.key  -> ap.id.value,
        AccessibleByTpeKey.key -> ap.tpe.tpe
      )
    }

    implicit def allowedPartyFromBSON(
        dbo: MongoDBObject
    )(implicit ctx: SymbioticContext): AllowedParty = {
      val tpe: Type = dbo.as[String](AccessibleByTpeKey.key)
      val idStr     = dbo.as[String](AccessibleByIdKey.key)
      val id = tpe match {
        case Usr => ctx.toUserId(idStr)
        case Org => ctx.toOrgId(idStr)
      }
      AllowedParty(id, tpe)
    }

    implicit def managedmdToBSON(fmd: ManagedMetadata): DBObject = {
      val b = MongoDBObject.newBuilder
      fmd.owner.foreach(o => b += OwnerKey.key -> ownerToBSON(o))
      b += AccessibleByKey.key -> fmd.accessibleBy.map(allowedPartyToBSON)
      b += VersionKey.key      -> fmd.version
      fmd.fid.foreach(b += "fid" -> _.value)
      b += IsFolderKey.key  -> fmd.isFolder
      b += IsDeletedKey.key -> fmd.isDeleted
      fmd.createdBy.foreach(u => b += CreatedByKey.key     -> u.value)
      fmd.description.foreach(d => b += DescriptionKey.key -> d)
      fmd.lock.foreach(l => b += LockKey.key               -> lockToBSON(l))
      fmd.path.foreach(f => b += PathKey.key               -> f.materialize)
      fmd.extraAttributes.foreach(
        mm => b += ExtraAttributesKey.key -> extraAttribsToBSON(mm)
      )

      b.result()
    }

    implicit def managedmdFromBSON(
        dbo: DBObject
    )(implicit ctx: SymbioticContext): ManagedMetadata = {
      ManagedMetadata(
        owner = dbo.getAs[MongoDBObject](OwnerKey.key).map(ownerFromBSON),
        fid = dbo.getAs[String](FidKey.key),
        createdBy = dbo.getAs[String](CreatedByKey.key).map(ctx.toUserId),
        version = dbo.getAs[Int](VersionKey.key).getOrElse(1),
        isFolder = dbo.as[Boolean](IsFolderKey.key),
        isDeleted = dbo.getAs[Boolean](IsDeletedKey.key).getOrElse(false),
        path = dbo.getAs[String](PathKey.key).map(Path.apply),
        description = dbo.getAs[String](DescriptionKey.key),
        lock = dbo.getAs[MongoDBObject](LockKey.key).map(lockFromBSON),
        extraAttributes = dbo.getAs[MongoDBObject](ExtraAttributesKey.key),
        accessibleBy = dbo
          .getAs[MongoDBList](AccessibleByKey.key)
          .map(_.map {
            case dbo: DBObject => allowedPartyFromBSON(dbo)
          })
          .getOrElse(Seq.empty)
      )
    }
  }

  trait FileFolderBSONConverter
      extends ManagedMetadataBSONConverter
      with DateTimeBSONConverter {

    implicit def folderFromBSON(
        dbo: DBObject
    )(implicit ctx: SymbioticContext): Folder = {
      val mdbo = new MongoDBObject(dbo)
      val md   = mdbo.as[DBObject](MetadataKey)
      Folder(
        id = mdbo.getAs[String]("_id").map(UUID.fromString),
        filename = mdbo.as[String]("filename"),
        fileType = mdbo.getAs[String]("contentType"),
        createdDate = mdbo.getAs[java.util.Date]("uploadDate"),
        metadata = managedmdFromBSON(md)
      )
    }

    /**
     * Converter to map between a GridFSDBFile (from read operations) to a File
     *
     * @param gf GridFSDBFile
     * @return File
     */
    implicit def fileFromGridFS(
        gf: GridFSDBFile
    )(implicit ctx: SymbioticContext): File = {
      File(
        id = gf.getAs[String]("_id").map(UUID.fromString),
        filename = gf.filename.getOrElse("no_name"),
        fileType = gf.contentType,
        createdDate = Option(asDateTime(gf.uploadDate)),
        length = Option(gf.length.toString),
        stream = Option(StreamConverters.fromInputStream(() => gf.inputStream)),
        metadata = managedmdFromBSON(gf.metaData)
      )
    }

    implicit def filesFromGridFS(
        gfs: Seq[GridFSDBFile]
    )(implicit ctx: SymbioticContext): Seq[File] = gfs.map(fileFromGridFS)

    implicit def fileFromMaybeGridFS(
        mgf: Option[GridFSDBFile]
    )(implicit ctx: SymbioticContext): Option[File] = mgf.map(fileFromGridFS)

    /**
     * Converter to map between a DBObject (from read operations) to a File.
     * This will typically be used when listing files in a GridFS <bucket>.files
     * collection
     *
     * @param dbo DBObject
     * @return File
     */
    implicit def fileFromBSON(
        dbo: DBObject
    )(implicit ctx: SymbioticContext): File = {
      val mdbo = new MongoDBObject(dbo)
      val md   = mdbo.as[DBObject](MetadataKey)

      File(
        id = mdbo.getAs[String]("_id").map(UUID.fromString),
        filename = mdbo.getAs[String]("filename").getOrElse("no_name"),
        fileType = mdbo.getAs[String]("contentType"),
        createdDate = mdbo.getAs[java.util.Date]("uploadDate"),
        length = mdbo.getAs[Long]("length").map(_.toString),
        stream = None,
        metadata = managedmdFromBSON(md)
      )
    }

    implicit def filesFromBSON(
        dbos: Seq[DBObject]
    )(implicit ctx: SymbioticContext): Seq[File] = dbos.map(fileFromBSON)

    implicit def managedfileFromBSON(
        dbo: DBObject
    )(implicit ctx: SymbioticContext): ManagedFile = {
      val isFolder = dbo.getAs[Boolean](IsFolderKey.full).getOrElse(false)
      if (isFolder) folderFromBSON(dbo)
      else fileFromBSON(dbo)
    }

    implicit def managedfilesFromBSON(
        dbos: Seq[DBObject]
    )(implicit ctx: SymbioticContext): Seq[ManagedFile] =
      dbos.map(managedfileFromBSON)
  }

}
