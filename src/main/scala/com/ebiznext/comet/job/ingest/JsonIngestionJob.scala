/*
 *
 *  * Licensed to the Apache Software Foundation (ASF) under one or more
 *  * contributor license agreements.  See the NOTICE file distributed with
 *  * this work for additional information regarding copyright ownership.
 *  * The ASF licenses this file to You under the Apache License, Version 2.0
 *  * (the "License"); you may not use this file except in compliance with
 *  * the License.  You may obtain a copy of the License at
 *  *
 *  *    http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 *
 */

package com.ebiznext.comet.job.ingest

import com.ebiznext.comet.config.{DatasetArea, HiveArea}
import com.ebiznext.comet.schema.handlers.StorageHandler
import com.ebiznext.comet.schema.model._
import org.apache.hadoop.fs.Path
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.execution.datasources.json.JsonIngestionUtil
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{DataFrame, Row}

/**
  * Main class to complex json delimiter separated values file
  * If your json contains only one level simple attribute aka. kind of dsv but in json format please use SIMPLE_JSON instead. It's way faster
  *
  * @param domain         : Input Dataset Domain
  * @param schema         : Input Dataset Schema
  * @param types          : List of globally defined types
  * @param path           : Input dataset path
  * @param storageHandler : Storage Handler
  */
class JsonIngestionJob(
  val domain: Domain,
  val schema: Schema,
  val types: List[Type],
  val path: Path,
  val storageHandler: StorageHandler
) extends IngestionJob {

  /**
    * load the json as an RDD of String
    * @return Spark Dataframe loaded using metadata options
    */
  def loadDataSet(): DataFrame = {
    val df = session.read
      .format("com.databricks.spark.csv")
      .option("inferSchema", value = false)
      .text(path.toString)
    df.printSchema()
    df
  }

  lazy val schemaSparkType: StructType = schema.sparkType(Types(types))

  /**
    * Where the magic happen
    * @param dataset input dataset as a RDD of string
    */
  def ingest(dataset: DataFrame): (RDD[_], RDD[_]) = {
    val rdd = dataset.rdd
    dataset.printSchema()
    val checkedRDD = JsonIngestionUtil.parseRDD(rdd, schemaSparkType).cache()
    val acceptedRDD: RDD[String] = checkedRDD.filter(_.isRight).map(_.right.get)
    val rejectedRDD: RDD[String] =
      checkedRDD.filter(_.isLeft).map(_.left.get.mkString("\n"))
    val acceptedDF = session.read.json(acceptedRDD)
    saveRejected(rejectedRDD)
    saveAccepted(acceptedDF) // prefer to let Spark compute the final schema
    (rejectedRDD, acceptedRDD)
  }

  /**
    * Use the schema we used for validation when saving
    * @param acceptedRDD
    */
  @deprecated("We let Spark compute the final schema", "")
  def saveAccepted(acceptedRDD: RDD[Row]): Unit = {
    val writeMode = metadata.getWriteMode()
    val acceptedPath = new Path(DatasetArea.accepted(domain.name), schema.name)
    saveRows(
      session.createDataFrame(acceptedRDD, schemaSparkType),
      acceptedPath,
      writeMode,
      HiveArea.accepted,
      schema.merge.isDefined
    )
  }

  override def name: String = "JsonJob"
}
