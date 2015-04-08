/**
 * Copyright(c) 2015 Knut Petter Meen, all rights reserved.
 */
package test.util.mongodb

import java.net.{InetSocketAddress, Socket, SocketAddress}

import com.mongodb.casbah.{MongoClient, MongoClientURI}
import dman.FileWrapper
import org.specs2.specification.BeforeAfterSpec
import org.specs2.specification.core.Fragments
import org.specs2.specification.create.DefaultFragmentFactory

/**
 * Use this trait when testing code that requires interaction with MongoDB.
 *
 * It works in the following way.
 *
 * 1. Check if there is a locally running MongoDB (with default port 27017) on the current system.
 * 2. a) If no local mongod, start an embedded MongoDB and set the appropriate properties
 * 2. b) A local DB is running, set the appropriate properties (including a specific test db name).
 * 3. let the specifications run through...
 * 4. a) Stop the embedded mongodb
 * 4. b) Remove the test database from the locally running mongodb
 *
 */
trait MongoSpec extends BeforeAfterSpec {

  val testDBName = "symbiotic_test"
  val localTestDBURI = s"mongodb://localhost:27017/$testDBName"

  val isLocal = isLocalRunning

  val preserveDB = System.getProperty("db.preserve", "false").toBoolean

  val mongoRunner: Option[MongoRunner] = {
    if (!isLocal) {
      val mr = BootstrapMongoRunner.mongoRunner
      System.setProperty("symbiotic.mongodb.uri", s"mongodb://localhost:${mr.port}/symbiotic")
      Some(mr)
    } else {
      println("Using locally running mongod...do not stop mongod")
      System.setProperty("symbiotic.mongodb.uri", localTestDBURI)
      None
    }
  }

  override def beforeSpec: Fragments = Fragments(DefaultFragmentFactory.step {
    mongoRunner.foreach(mr =>
      if (!mr.running) mr.startMongod()
      else println("Using already running mongod instance...")
    )
  }, DefaultFragmentFactory.step {
    cleanDatabase()
  }, DefaultFragmentFactory.step {
    // Ensure indices are in place...
    FileWrapper.ensureIndex()
  })

  override def afterSpec: Fragments = Fragments(DefaultFragmentFactory.step {
    if (!isLocalRunning) mongoRunner.foreach(_.stopMongod())
    else {
      cleanDatabase()
    }
  })

  private def cleanDatabase(): Unit =
    if (!preserveDB) {
      MongoClient(MongoClientURI(localTestDBURI))(testDBName).dropDatabase()
      println(s"Dropped database")
    } else {
      println("Preserving database as requested.")
    }

  /**
   * Tries to determine if there is a local mongod (with default port number) running on the current system.
   */
  private def isLocalRunning: Boolean = {
    val address: SocketAddress = new InetSocketAddress("localhost", 27017)
    val con = new Socket
    try {
      con.connect(address)
      con.isConnected
    } catch {
      case e: Throwable =>
        println("Local MongoDB isn't running...")
        false
    } finally {
      con.close()
    }
  }

}

/**
 * Singleton for wrapping a lazy instance of the MongoRunner.
 */
object BootstrapMongoRunner {
  lazy val mongoRunner = new MongoRunner()
}
