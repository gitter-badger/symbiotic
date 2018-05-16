package net.scalytica.symbiotic.elasticsearch

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl._
import com.sksamuel.elastic4s.RefreshPolicy
import com.sksamuel.elastic4s.http.ElasticDsl._
import com.sksamuel.elastic4s.http.bulk.BulkResponseItem
import com.sksamuel.elastic4s.playjson._
import com.sksamuel.elastic4s.streams.ReactiveElastic._
import com.sksamuel.elastic4s.streams.{RequestBuilder, ResponseListener}
import com.typesafe.config.Config
import net.scalytica.symbiotic.api.indexing.Indexer
import net.scalytica.symbiotic.api.types.ManagedFile
import net.scalytica.symbiotic.json.Implicits.ManagedFileFormat
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/**
 * Indexer implementation for integrating with ElasticSearch.
 */
class ElasticSearchIndexer private[elasticsearch] (
    cfg: ElasticSearchConfig,
    refreshPolicy: RefreshPolicy = RefreshPolicy.NONE
)(implicit sys: ActorSystem, mat: Materializer)
    extends Indexer {

  private val logger = LoggerFactory.getLogger(getClass)

  implicit val reqBuilder: RequestBuilder[ManagedFile] =
    (t: ManagedFile) => indexInto(cfg.indexAndType).doc[ManagedFile](t)

  private[this] lazy val escClient = new ElasticSearchClient(cfg)

  override def index(
      a: ManagedFile
  )(implicit ec: ExecutionContext): Future[Boolean] = {
    escClient
      .exec(
        indexInto(cfg.indexAndType).doc(a).refresh(refreshPolicy)
      )
      .map {
        case Right(r) =>
          logger.debug(
            s"ManagedFile was${if (r.isError) " not" else ""} indexed"
          )
          r.isSuccess
        case Left(err) =>
          logger.warn(s"An error occurred indexing a file: ${err.error}")
          err.isError
      }
  }

  override def indexSource(
      src: Source[ManagedFile, NotUsed],
      includeFiles: Boolean = false
  )(implicit ec: ExecutionContext): Unit = {
    val refreshEach = RefreshPolicy.IMMEDIATE == refreshPolicy

    // TODO: if includeFiles == true the stream should also fetch the actual
    // files (if any), and pass it to ES.
    // (requires the Ingest Attachments plugin)
    val resListener: ResponseListener[ManagedFile] =
      (resp: BulkResponseItem, original: ManagedFile) => {
        if (logger.isDebugEnabled)
          logger.debug(
            s"Indexing file ${original.filename} returned ${resp.result}"
          )
      }

    val sink = Sink.fromSubscriber(
      escClient.httpClient.subscriber[ManagedFile](
        refreshAfterOp = refreshEach,
        completionFn = () => logger.info(s"Completed indexing Source"),
        listener = resListener
      )
    )

    src.runWith(sink)
  }

}

object ElasticSearchIndexer {

  def apply(config: Config)(
      implicit sys: ActorSystem,
      mat: Materializer
  ): ElasticSearchIndexer = {
    initIndices(config)
    new ElasticSearchIndexer(config)
  }

  def apply(config: Config, refreshPolicy: RefreshPolicy)(
      implicit sys: ActorSystem,
      mat: Materializer
  ): ElasticSearchIndexer = {
    initIndices(config)
    new ElasticSearchIndexer(config, refreshPolicy)
  }

  /** Initialize the indices when the class is instantiated. */
  private[elasticsearch] def initIndices(cfg: ElasticSearchConfig): Boolean = {
    import scala.concurrent.ExecutionContext.Implicits.global

    val c = new ElasticSearchClient(cfg)
    val res =
      Await.result(
        c.exec(indexExists(cfg.indexName)).flatMap {
          case Right(er) =>
            // TODO: Add specific mappings for ManagedFile types
            if (!er.result.exists) {
              c.exec(
                  createIndex(cfg.indexName) mappings mapping(cfg.indexType)
                )
                .map {
                  case Right(cir) => cir.result.acknowledged
                  case Left(_)    => false
                }
            } else {
              Future.successful(false)
            }

          case Left(_) =>
            Future.successful(false)
        },
        10 seconds
      )

    c.close()
    res
  }

  /** Removes the indices entirely from ElasticSearch */
  private[elasticsearch] def removeIndicies(
      cfg: ElasticSearchConfig
  ): Boolean = {
    import ExecutionContext.Implicits.global
    val c = new ElasticSearchClient(cfg)
    val res =
      Await.result(
        c.exec(indexExists(cfg.indexName)).flatMap {
          case Right(er) =>
            if (er.result.exists)
              c.exec(deleteIndex(cfg.indexName)).map {
                case Right(di) => di.result.acknowledged
                case Left(_)   => false
              } else Future.successful(false)

          case Left(_) =>
            Future.successful(false)
        },
        10 seconds
      )

    c.close()
    res
  }

}
