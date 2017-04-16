package net.scalytica.symbiotic.mongodb

import com.typesafe.config.ConfigFactory
import net.scalytica.symbiotic.mongodb.docmanagement.{
  MongoDBFSTreeRepository,
  MongoDBFileRepository,
  MongoDBFolderRepository
}
import net.scalytica.symbiotic.persistence.RepositoryProvider

object MongoRepositories extends RepositoryProvider {

  lazy val config = ConfigFactory.load()

  override lazy val fileRepository = new MongoDBFileRepository(config)

  override lazy val folderRepository = new MongoDBFolderRepository(config)

  override lazy val fsTreeRepository = new MongoDBFSTreeRepository(config)
}
