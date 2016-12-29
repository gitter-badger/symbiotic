/**
 * Copyright(c) 2015 Knut Petter Meen, all rights reserved.
 */
package repository

import java.util.UUID

import com.mohiva.play.silhouette.api.LoginInfo
import com.mohiva.play.silhouette.api.util.PasswordInfo
import com.mohiva.play.silhouette.impl.providers.OAuth2Info
import com.mohiva.play.silhouette.persistence.daos.DelegableAuthInfoDAO
import core.lib.SuccessOrFailure
import models.base.Username
import models.docmanagement.CommandStatusTypes.CommandStatus
import models.docmanagement.Lock.LockOpStatusTypes.LockOpStatus
import models.docmanagement._
import models.party.PartyBaseTypes.UserId
import models.party.{Avatar, User}

trait UserRepository {

  def save(user: User): SuccessOrFailure

  def findById(id: UserId): Option[User]

  def findByUsername(username: Username): Option[User]

  def findByLoginInfo(loginInfo: LoginInfo): Option[User]

}

trait AvatarRepository {
  /**
   * Saves a new Avatar for the User specified in the metadata.
   * Only 1 avatar image per user will be kept, so this method will ensure
   * that old avatar images are cleaned up after adding the new one.
   *
   * @param a the Avatar to save
   * @return an Option that will contain the UUID of the added avatar if successful
   */
  def save(a: Avatar): Option[UUID]

  /**
   * Will return a File (if found) with the provided id.
   *
   * @param uid UserId
   * @return Option[File]
   */
  def get(uid: UserId): Option[Avatar]

  /**
   * Removes _all_ avatar images where filename equals the uid
   *
   * @param uid UserId to remove avatar images for
   */
  def remove(uid: UserId): Unit

  /**
   *
   * @param uid UserId to remove files for.
   * @param ids a collection of the UUID's of files to remove
   */
  def remove(uid: UserId, ids: Seq[UUID]): Unit
}

trait FileRepository {

  /**
   * Saves the passed on File in MongoDB GridFS
   *
   * @param f File
   * @return Option[FileId]
   */
  def save(f: File)(implicit uid: UserId): Option[FileId]

  /**
   * Will return a File (if found) with the provided id.
   *
   * @param id of type java.util.UUID
   * @return Option[File]
   */
  def get(id: UUID)(implicit uid: UserId): Option[File]

  def getLatest(fid: FileId)(implicit uid: UserId): Option[File]

  /**
   * "Moves" a file (including all versions) from one folder to another.
   *
   * @param filename String
   * @param orig     Folder
   * @param mod      Folder
   * @return An Option with the updated File
   */
  def move(filename: String, orig: Path, mod: Path)(implicit uid: UserId): Option[File]

  /**
   * Will return a collection of File (if found) with the provided filename and
   * folder properties.
   *
   * @param filename  String
   * @param maybePath Option[Path]
   * @return Seq[File]
   */
  def find(filename: String, maybePath: Option[Path])(implicit uid: UserId): Seq[File]

  /**
   * Search for the latest version of a file matching the provided parameters.
   *
   * @param filename  String
   * @param maybePath Option[Folder]
   * @return An Option containing the latest version of the File
   */
  def findLatest(
    filename: String,
    maybePath: Option[Path]
  )(implicit uid: UserId): Option[File]

  /**
   * List all the files in the given Folder path
   *
   * @param path String
   * @return Option[File]
   */
  def listFiles(path: String)(implicit uid: UserId): Seq[File]

  /**
   * Check if a file is locked or not.
   *
   * @param fid FileId
   * @return an Option with the UserId of the user holding the lock
   */
  def locked(fid: FileId)(implicit uid: UserId): Option[UserId]

  /**
   * Places a lock on a file to prevent any modifications or new versions of
   * the file.
   *
   * @param uid UserId The id of the user that places the lock
   * @param fid FileId of the file to lock
   * @return Option[Lock] None if no lock was applied, else the Option will
   *         contain the applied lock.
   */
  def lock(fid: FileId)(implicit uid: UserId): LockOpStatus[_ <: Option[Lock]]

  /**
   * Unlocks the provided file if and only if the provided user is the one
   * holding the current lock.
   *
   * @param uid UserId
   * @param fid FileId
   * @return
   */
  def unlock(fid: FileId)(implicit uid: UserId): LockOpStatus[_ <: String]
}

trait FolderRepository {
  /**
   * Create a new virtual folder in GridFS.
   * If the folder is not defined, the method will attempt to create a root
   * folder if it does not already exist.
   *
   * @param f the folder to add
   * @return An option containing the Id of the created folder, or none if it
   *         already exists
   */
  def save(f: Folder)(implicit uid: UserId): Option[FileId]

  def get(folderId: FolderId)(implicit uid: UserId): Option[Folder]

  /**
   * Checks for the existence of a Folder
   *
   * @param f Folder
   * @return true if the folder exists, else false
   */
  def exists(f: Folder)(implicit uid: UserId): Boolean = exists(f.flattenPath)

  /**
   * Checks for the existence of a Path/Folder
   *
   * @param at Path to look for
   * @return true if the folder exists, else false
   */
  def exists(at: Path)(implicit uid: UserId): Boolean

  /**
   * Will attempt to identify if any path segments in the provided folders path
   * is missing. If found, a list of the missing Folders will be returned.
   *
   * @param p Path
   * @return list of missing folders
   */
  def filterMissing(p: Path)(implicit uid: UserId): List[Path]

  /**
   * This method allows for modifying the path from one value to another.
   * Should only be used in conjunction with the appropriate checks for any child nodes.
   *
   * @param orig FolderPath
   * @param mod  FolderPath
   * @return Option of Int with number of documents affected by the update
   */
  def move(orig: Path, mod: Path)(implicit uid: UserId): CommandStatus[Int]
}

trait FSTreeRepository {

  /**
   * Fetch only the Paths for the full folder tree structure, without any file refs.
   *
   * @param from Folder location to return the tree structure from. Defaults to rootFolder
   * @return a collection of Folders that match the criteria.
   */
  def treePaths(from: Option[Path])(implicit uid: UserId): Seq[(FileId, Path)]

  /**
   * This method will return the a collection of A instances , representing the
   * folder/directory structure that has been set-up in the database.
   *
   * @param from Folder location to return the tree structure from. Defaults to rootFolder
   * @return a collection of ManagedFile instances
   */
  def tree(from: Option[Path])(implicit uid: UserId): Seq[ManagedFile]

  /**
   * This method will return the a collection of A instances, representing the
   * direct descendants for the given Folder.
   *
   * @param from Folder location to return the tree structure from. Defaults to rootFolder
   * @return a collection of ManagedFile instances
   */
  def children(from: Option[Path])(implicit uid: UserId): Seq[ManagedFile]
}

trait PasswordAuthRepository extends DelegableAuthInfoDAO[PasswordInfo]

trait OAuth2Repository extends DelegableAuthInfoDAO[OAuth2Info]

//trait OpenIDAuthRepository extends DelegableAuthInfoDAO[OpenIDInfo]