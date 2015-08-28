/**
 * Copyright(c) 2015 Knut Petter Meen, all rights reserved.
 */
package dman

import com.mongodb.casbah.commons.Imports._
import dman.CommandStatusTypes._
import dman.FileWrapper._
import dman.Lock.LockOpStatusTypes._
import models.customer.CustomerId
import models.parties.UserId
import org.slf4j.LoggerFactory

/**
 * Singleton object that provides document management operations towards GridFS. Operations allow access to both
 * Folders, which are simple entries in the fs.files collection, and the complete GridFSFile instance including the
 * input stream of the file itself (found in fs.chunks).
 */
trait Operations {
  self =>

  val logger = LoggerFactory.getLogger(self.getClass)

  /**
   * Ensures that all indices in the <bucket>.files collection are in place
   */
  protected def ensureIndices(): Unit = BaseFile.ensureIndex()

  /**
   * Function allowing renaming of folder segments!
   * - Find the tree of folders from the given path element to rename
   * - Update all path segments with the new name for the given path element.
   * - Return all folders that were affected
   *
   * TODO: This should also trigger a re-indexing in the search engine (once that's in place)
   *
   * @param cid CustomerId
   * @param orig Folder with the original full path
   * @param mod Folder with the modified full path
   * @return A collection containing the folder paths that were updated.
   */
  protected def moveFolder(cid: CustomerId, orig: FolderPath, mod: FolderPath): Seq[FolderPath] = {
    treeWithFiles(cid, orig).flatMap { fw =>
      fw.metadata.path.map { f =>
        val upd = FolderPath(f.path.replaceAll(orig.path, mod.path))
        FolderPath.move(cid, f, upd) match {
          case CommandOk(n) => Option(upd)
          case CommandKo(n) =>
            logger.warn(s"Path ${f.path} was not updated to ${upd.path}")
            None
          case CommandError(n, m) =>
            logger.error(s"An error occured when trying to update path ${f.path} to ${upd.path}. Message is: $m")
            None
        }
      }
    }.filter(_.isDefined).flatten
  }

  /**
   * This method will return the a collection of FileWrapper instances , representing the folder/directory
   * structure that has been set-up in GridFS.
   *
   * @param cid CustomerId
   * @param from Folder location to return the tree structure from. Defaults to rootFolder
   * @return a collection of FileWrapper instances that match the criteria
   */
  protected def treeWithFiles(cid: CustomerId, from: FolderPath = FolderPath.rootFolder): Seq[FileWrapper] =
    FolderPath.treeWith[FileWrapper](cid, from)(mdbo => FileWrapper.fromDBObject(mdbo))

  /**
   * This method will return a collection of FileWrapper instances , representing the direct descendants
   * for the given Folder.
   *
   * @param cid CustomerId
   * @param from Folder location to return the tree structure from. Defaults to rootFolder
   * @return a collection of FileWrapper instances that match the criteria
   */
  protected def childrenWithFiles(cid: CustomerId, from: FolderPath = FolderPath.rootFolder): Seq[FileWrapper] =
    FolderPath.childrenWith[FileWrapper](cid, from)(mdbo => FileWrapper.fromDBObject(mdbo))

  /**
   * Moves a file to another folder if, and only if, the folder doesn't contain a file with the same name.
   *
   * @param cid CustomerId
   * @param filename String
   * @param orig Folder
   * @param mod Folder the folder to place the file
   * @return An Option with the updated FileWrapper
   */
  protected def moveFile(cid: CustomerId, filename: String, orig: FolderPath, mod: FolderPath): Option[FileWrapper] = {
    FileWrapper.findLatest(cid, filename, Some(mod)).fold(
      FileWrapper.move(cid, filename, orig, mod)
    ) { _ =>
      logger.info(s"Not moving file $filename to $mod because a file with the same name already exists.")
      None
    }
  }

  protected def moveFile(fileId: FileId, orig: FolderPath, mod: FolderPath): Option[FileWrapper] = {
    FileWrapper.get(fileId).map(fw => moveFile(fw.metadata.cid, fw.filename, orig, mod)).getOrElse {
      logger.info(s"Could not find file with with id $fileId")
      None
    }
  }

  /**
   * Attempt to create a folder. If successful it will return the FolderId.
   * If segments of the Folder path is non-existing, these will be created as well.
   *
   * @param cid CustomerId
   * @param at Folder to create
   * @return maybe a FolderId if it was successfully created
   */
  protected def createFolder(cid: CustomerId, at: FolderPath, createMissing: Boolean = true): Option[FolderId] = {
    if (createMissing) {
      logger.debug(s"Creating folder $at for $cid")
      val fid = FolderPath.save(cid, at)
      logger.debug(s"Creating any missing parent folders for $at")
      createNonExistingFoldersInPath(cid, at)
      fid
    } else {
      val verifyPath: String = at.materialize.split(",").filterNot(_.isEmpty).dropRight(1).mkString("/", "/", "/")
      val vf = FolderPath(verifyPath)
      val missing = FolderPath.filterMissing(cid, vf)
      if (missing.isEmpty) {
        logger.debug(s"Parent folders exist, creating folder $at for $cid")
        FolderPath.save(cid, at)
      } else {
        logger.warn(s"Did not create folder because there are missing parent folders for $at.")
        None
      }
    }
  }

  /**
   * Will create any missing path segments found in the Folder path, and return a List of all the
   * Folders that were created.
   *
   * @param cid CustomerId
   * @param f Folder to verify path and create non-existing segments
   * @return A List containing the missing folders that were created.
   */
  private def createNonExistingFoldersInPath(cid: CustomerId, f: FolderPath): List[FolderPath] = {
    val missing = FolderPath.filterMissing(cid, f)
    missing.foreach(mf => FolderPath.save(cid, mf))
    missing
  }

  /**
   * Convenience function for creating the root Folder.
   *
   * @param cid CustomerId
   * @return maybe a FolderId if the root folder was created
   */
  protected def createRootFolder(cid: CustomerId): Option[FolderId] = FolderPath.save(cid)

  /**
   * Checks for the existence of a Path/Folder
   *
   * @param cid CustomerId
   * @param at Folder with the path to look for
   * @return true if the folder exists, else false
   */
  protected def folderExists(cid: CustomerId, at: FolderPath): Boolean = FolderPath.exists(cid, at)

  /**
   * Fetch the full folder tree structure without any file refs.
   *
   * @param cid CustomerId
   * @param from Folder location to return the tree structure from. Defaults to rootFolder
   * @return a collection of Folders that match the criteria.
   */
  protected def treeNoFiles(cid: CustomerId, from: FolderPath = FolderPath.rootFolder): Seq[FolderPath] = FolderPath.treeNoFiles(cid, from)

  /**
   * Saves the passed on FileWrapper in MongoDB GridFS
   *
   * @param uid UserId
   * @param f FileWrapper
   * @return Option[ObjectId]
   */
  protected def saveFileWrapper(uid: UserId, f: FileWrapper): Option[FileId] = {
    val dest = f.metadata.path.getOrElse(FolderPath.rootFolder)
    if (FolderPath.exists(f.metadata.cid, dest)) {
      FileWrapper.findLatest(f.metadata.cid, f.filename, f.metadata.path).fold(FileWrapper.save(f)) { latest =>
        val canSave = latest.metadata.lock.fold(true)(l => l.by == uid)
        if (canSave) {
          val res = FileWrapper.save(
            f.copy(metadata = f.metadata.copy(version = latest.metadata.version + 1, lock = latest.metadata.lock))
          )
          // Unlock the previous version.
          unlockFile(uid, latest.id.get)
          res
        } else {
          logger.warn(s"Cannot save file because it is locked by another user: ${latest.metadata.lock.get.by}")
          None
        }
      }
    } else {
      logger.warn(s"Attempted to save file to non-existing destination folder: ${dest.path}")
      None
    }
  }

  /**
   * Will return a FileWrapper (if found) with the provided id.
   *
   * @param fid FileId
   * @return Option[FileWrapper]
   */
  protected def getFileWrapper(fid: FileId): Option[FileWrapper] = FileWrapper.get(fid)

  /**
   * Will return a collection of FileWrapper (if found) with the provided filename and folder properties.
   *
   * @param cid CustomerId
   * @param filename String
   * @param maybePath Option[Folder]
   * @return Seq[FileWrapper]
   */
  protected def getFileWrappers(cid: CustomerId, filename: String, maybePath: Option[FolderPath]): Seq[FileWrapper] =
    FileWrapper.find(cid, filename, maybePath)

  /**
   * Will return the latest version of a file (FileWrapper)
   *
   * @param cid CustomerId
   * @param filename String
   * @param maybePath Option[Folder]
   * @return An Option with a FileWrapper
   */
  protected def getLatestFileWrapper(cid: CustomerId, filename: String, maybePath: Option[FolderPath]): Option[FileWrapper] =
    FileWrapper.findLatest(cid, filename, maybePath)

  /**
   * List all the files in the given Folder path for the given CustomerId
   *
   * @param cid CustomerId
   * @param folder Folder
   * @return Option[FileWrapper]
   */
  protected def listFiles(cid: CustomerId, folder: FolderPath): Seq[FileWrapper] = FileWrapper.listFiles(cid, folder.materialize)

  /**
   * Places a lock on a file to prevent any modifications or new versions of the file
   *
   * @param uid UserId The id of the user that places the lock
   * @param fileId FileId of the file to lock
   * @return Option[Lock] None if no lock was applied, else the Option will contain the applied lock.
   */
  protected def lockFile(uid: UserId, fileId: FileId): Option[Lock] = FileWrapper.lock(uid, fileId) match {
    case Success(s) => s
    case _ => None
  }

  /**
   * Unlocks the provided file if and only if the provided user is the one holding the current lock.
   *
   * @param uid UserId
   * @param fid FileId
   * @return
   */
  protected def unlockFile(uid: UserId, fid: FileId): Boolean = FileWrapper.unlock(uid, fid) match {
    case Success(t) => true
    case _ => false
  }

  /**
   * Checks if the file has a lock or not
   *
   * @param fileId FileId
   * @return true if locked, else false
   */
  protected def hasLock(fileId: FileId): Boolean = FileWrapper.locked(fileId).isDefined

  /**
   * Checks if the file is locked and if it is locked by the given user
   *
   * @param fileId FileId
   * @param uid UserId
   * @return true if locked by user, else false
   */
  protected def isLockedBy(fileId: FileId, uid: UserId): Boolean = locked(fileId).contains(uid)
}
