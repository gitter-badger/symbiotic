package net.scalytica.symbiotic.postgres.docmanagement

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.typesafe.config.Config
import net.scalytica.symbiotic.api.repository.IndexDataRepository
import net.scalytica.symbiotic.api.types.{File, Folder, SymbioticContext}
import net.scalytica.symbiotic.fs.FileSystemIO
import net.scalytica.symbiotic.postgres.SymbioticDb
import org.slf4j.LoggerFactory
import slick.jdbc.{ResultSetConcurrency, ResultSetType}

class PostgresIndexDataRepository(
    val config: Config,
    val fs: FileSystemIO
) extends IndexDataRepository
    with SymbioticDb
    with SymbioticDbTables
    with SharedQueries {

  private[this] val logger = LoggerFactory.getLogger(this.getClass)

  private[this] val pageSize = 100

  import profile.api._

  logger.debug(s"Initialized repository $getClass")

  override def streamFiles()(
      implicit ctx: SymbioticContext,
      as: ActorSystem,
      mat: Materializer
  ): Source[File, NotUsed] = {
    val qry =
      findLatestBaseQuery(_.filter { f =>
        f.isFolder === false &&
        f.isDeleted === false
      }).result
        .withStatementParameters(
          rsType = ResultSetType.ForwardOnly,
          rsConcurrency = ResultSetConcurrency.ReadOnly,
          fetchSize = pageSize
        )
        .transactionally

    Source
      .fromPublisher(db.stream(qry))
      .map(rowToFile)
      .map(f => f.copy(stream = fs.read(f)))
  }

  override def streamFolders()(
      implicit ctx: SymbioticContext,
      as: ActorSystem,
      mat: Materializer
  ): Source[Folder, NotUsed] = {
    val qry = findLatestBaseQuery(_.filter { f =>
      f.isFolder === true &&
      f.isDeleted === false
    }).result
      .withStatementParameters(
        rsType = ResultSetType.ForwardOnly,
        rsConcurrency = ResultSetConcurrency.ReadOnly,
        fetchSize = pageSize
      )
      .transactionally

    Source.fromPublisher(db.stream(qry)).map(rowToFolder)
  }
}
