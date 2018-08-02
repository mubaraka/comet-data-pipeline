package com.ebiznext.comet.services
import java.nio.file.Path

import better.files.Dsl.SymbolicOperations
import better.files.{File, _}
import com.ebiznext.comet.db.RocksDBConnection
import com.ebiznext.comet.model.CometModel.{Cluster, User, _}
import com.ebiznext.comet.utils.Launcher
import com.softwaremill.macwire.wire
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.ExecutionContext
import scala.util.Try

/**
  * Created by Mourad on 30/07/2018.
  */
class ClusterService(implicit executionContext: ExecutionContext, implicit val dbConnection: RocksDBConnection)
    extends LazyLogging {

  lazy val nodeService: NodeService = wire[NodeService]

  def get(userId: String, clusterId: String): Try[Option[Cluster]] = Try {
    dbConnection.read[User](userId) match { // FIXME replace with User Service
      case Some(u) =>
        u.clusters.find(_.id == clusterId)
      case None =>
        val message = s"User with id $userId not found! "
        logger.error(message)
        throw new Exception(message)
    }
  }

  def create(userId: String, cluster: Cluster): Try[Option[String]] = Try {
    dbConnection.read[User](userId) match { // FIXME replace with User Service
      case Some(u) =>
        val clusters: Set[Cluster] = u.clusters
        clusters.find(_.id == cluster.id) match {
          case Some(c) =>
            val message = s"Cluster object with id ${c.id} already exists!"
            logger.error(message)
            None
          case None =>
            //TODO get Nodes and Node groups before build
            val baseDir: String           = getUserTempBaseDir(u.id)
            val inventoryFilePath: String = s"$baseDir/${cluster.id}-inventory.ini"
            inventoryFilePath.toFile < cluster.inventoryFile

            val newNodes: Set[Node] = nodeService.getNodes(inventoryFilePath).map(n => Node.empty.copy(name = n)).toSet
            val newGroups: Set[NodeGroup] = nodeService
              .getGroups(inventoryFilePath)
              .map(m => NodeGroup.empty.copy(name = m._1, nodes = m._2.map(n => Node.empty.copy(name = n)).toSet))
              .toSet

            val clusterToCreate = cluster.copy(nodes = newNodes, nodeGroups = newGroups)

            val user = u.copy(id = u.id, clusters = clusters + clusterToCreate)
            dbConnection.write[User](user.id, user)
            Some(cluster.id)
        }
      case None =>
        val message = s"User with id $userId not found! "
        logger.error(message)
        throw new Exception(message)

    }
  }

  def delete(userId: String, clusterId: String): Try[Unit] = Try {
    dbConnection.read[User](userId) match { // FIXME replace with User Service
      case Some(u) =>
        dbConnection.write[User](u.id, User(u.id, u.clusters.filterNot(_.id == clusterId)))
      case None =>
        val message = s"User with id $userId not found!"
        logger.error(message)
        throw new Exception(message)
    }
  }

  def update(userId: String, clusterId: String, newCluster: Cluster): Try[Option[Cluster]] = {
    get(userId, clusterId).flatMap[Option[Cluster]] {
      case None => Try(None) // Nothing to update
      case Some(cluster) =>
        val updatedCluster = newCluster.copy(id = cluster.id)
        delete(userId, cluster.id).flatMap { _ =>
          create(userId, updatedCluster).map(_ => Some(updatedCluster))
        }
    }
  }

  def clone(userId: String, clusterId: String, tagsOnly: Boolean): Try[Option[String]] = {
    get(userId, clusterId).flatMap[Option[String]] {
      case None => Try(None) // Nothing to update
      case Some(cluster) =>
        if (tagsOnly)
          create(userId, Cluster.empty.copy(tags = cluster.tags, inventoryFile = "", nodes = Set(), nodeGroups = Set()))
        else
          create(userId, cluster.copy(id = generateId))
    }
  }

  def buildAnsibleScript(userId: String, clusterId: String): Try[Option[Path]] = Try {
    val baseDir: String              = getUserTempBaseDir(userId)
    val generatedZipFilePath: String = s"$baseDir/$clusterId.zip"
    //TODO edit the cmd with the one that generate the zip
    val cmd              = s"touch $generatedZipFilePath && ls $generatedZipFilePath"
    val (exit, out, err) = Launcher.runCommand(cmd)
    exit match {
      case 0 =>
        logger.info(out)
      //TODO
      case _ =>
        logger.error(s"$exit")
        logger.error(out)
        logger.error(err)
    }
    Some(generatedZipFilePath.toFile.path)
  }

  private def getUserTempBaseDir(userId: String) = {
    val baseTempDir: File = File.newTemporaryDirectory()
    val baseDir: String   = s"$baseTempDir/$userId"
    baseDir.toFile.createIfNotExists(asDirectory = true, createParents = true)
    baseDir
  }
}
