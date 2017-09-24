package net.scalytica.symbiotic.fs

import java.io.{File => JFile}
import java.nio.file.{Files, Path => JPath}

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.FileIO
import com.typesafe.config.Config
import net.ceedubs.ficus.Ficus._
import net.scalytica.symbiotic.api.types.{File, FileId, FileStream, Version}
import org.joda.time.DateTime
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

class FileSystemIO(
    config: Config
)(
    implicit actorSystem: ActorSystem,
    materializer: Materializer
) {

  val log = LoggerFactory.getLogger(getClass)

  implicit val ec = actorSystem.dispatcher

  val baseDirStr = config.as[String](FileSystemIO.RootDirKey)
  val baseDir    = new JFile(baseDirStr)

  if (!baseDir.exists()) {
    log.info(s"Initializing ${FileSystemIO.RootDirKey}: $baseDir...")
    new JFile(baseDirStr).mkdirs()
  } else {
    log.info(s"Skipping initialization of ${FileSystemIO.RootDirKey}...")
  }

  log.info(s"Symbiotic FS init completed.")

  private def err(msg: String): Future[Either[String, Unit]] = {
    log.error(msg)
    Future.successful(Left(msg))
  }

  private def destPath(dte: DateTime): JPath = {
    new JFile(
      s"$baseDirStr/${dte.getYear}/${dte.getMonthOfYear}/${dte.getDayOfMonth}"
    ).toPath
  }

  private def removeFailed(f: JFile): Unit = {
    if (f.exists()) f.delete()
  }

  private[this] def writeFile(
      fid: FileId,
      dte: DateTime,
      fname: String,
      v: Version,
      s: FileStream
  ) = {
    val dest  = destPath(dte)
    val jfile = new JFile(s"${dest.toString}/${fid.value}.$v")

    if (!Files.exists(dest)) Files.createDirectories(dest)

    s.runWith(FileIO.toPath(jfile.toPath))
      .map { res =>
        res.status match {
          case Success(_) =>
            log.debug(s"Successfully wrote ${jfile.toPath}")
            Right(())

          case Failure(ex) =>
            log.error(
              s"An error occurred while writing file ${jfile.toPath}",
              ex
            )
            removeFailed(jfile)
            Left(ex.getMessage)
        }
      }
      .recover {
        case NonFatal(ex) =>
          log.error("An unexpected error occurred", ex)
          removeFailed(jfile)
          Left(ex.getMessage)

        case fatal =>
          log.error("An unexpected fatal error occurred", fatal)
          removeFailed(jfile)
          throw fatal // scalastyle:ignore

      }
  }

  def write(file: File): Future[Either[String, Unit]] = {
    val fnm = file.filename
    (for {
      fid <- file.metadata.fid.map(_.value).orElse(missingOptLog(fnm, "fid"))
      dte <- file.createdDate.orElse(missingOptLog(fnm, "createdDate"))
    } yield {
      file.stream.map { s =>
        writeFile(fid, dte, fnm, file.metadata.version, s)
      }.getOrElse {
        log.info(s"File $fnm did not contain a file stream.")
        Future.successful(Right(()))
      }
    }).getOrElse(
      err(s"File $fnm did not have a valid FileId or upload date")
    )
  }

  def read(file: File): Option[FileStream] = {
    val fnm = file.filename

    for {
      fid <- file.metadata.fid.map(_.value).orElse(missingOptLog(fnm, "fid"))
      dte <- file.createdDate.orElse(missingOptLog(fnm, "createdDate"))
      jfp <- {
        val dest = destPath(dte)
        val filePath = new JFile(
          s"${dest.toString}/$fid.${file.metadata.version}"
        ).toPath

        if (Files.exists(filePath)) {
          log.debug(s"Reading file for $fnm from $filePath")
          Some(filePath)
        } else {
          log.warn(s"File for $fnm not found at $filePath")
          None
        }
      }
    } yield FileIO.fromPath(jfp)
  }

  private def missingOptLog[A](fname: String, field: String): Option[A] = {
    log.warn(s"$fname is missing $field")
    None
  }

}

object FileSystemIO {
  val RootDirKey = "symbiotic.fs.rootDir"
}
