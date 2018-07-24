package com.ebiznext.comet.db

import java.nio.file.Files
import java.util.UUID

import org.apache.commons.io.FileUtils
import org.scalatest.{ BeforeAndAfter, BeforeAndAfterAll, FlatSpec }

/**
 * Created by Mourad on 23/07/2018.
 */
class RocksDBConnectionSpec extends FlatSpec with BeforeAndAfter with BeforeAndAfterAll {

  lazy val tempDir = Files.createTempDirectory("comet-test").toFile
  lazy val db = getConnection().db

  override def afterAll(): Unit = {
    db.close()
    FileUtils.deleteDirectory(tempDir.getAbsoluteFile)
  }

  def getConnection(): RocksDBConnection = {
    val path = tempDir.getAbsolutePath + "/" + UUID.randomUUID()
    new RocksDBConnection(RocksDBConfiguration(path))
  }

  "RocksDb" should "put and get properly" in {
    (1 to 10).foreach { i =>
      db.put(Array(i toByte), s"VALUE$i".toCharArray.map(_.toByte))
    }
    (1 to 10).foreach { i =>
      assert(new String(db.get(Array(i toByte))) == s"VALUE$i")
    }
  }

  "RocksDb" should "remove properly" in {
    (1 to 10).foreach { i =>
      db.put(Array(i toByte), s"VALUE$i".toCharArray.map(_.toByte))
    }
    (1 to 10).foreach { i =>
      db.remove(Array(i toByte))
      assert(db.get(Array(i toByte)) == null)
    }
  }

}
