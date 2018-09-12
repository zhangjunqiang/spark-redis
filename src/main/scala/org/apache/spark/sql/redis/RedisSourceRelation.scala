package org.apache.spark.sql.redis

import java.util.UUID

import com.redislabs.provider.redis.rdd.{Keys, RedisKeysRDD}
import com.redislabs.provider.redis.{RedisConfig, RedisEndpoint, toRedisContext}
import org.apache.commons.lang3.SerializationUtils
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.redis.RedisSourceRelation._
import org.apache.spark.sql.sources.{BaseRelation, Filter, InsertableRelation, PrunedFilteredScan}
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{DataFrame, Row, SQLContext}
import redis.clients.jedis.Protocol

import scala.collection.JavaConversions
import scala.collection.JavaConversions._

class RedisSourceRelation(override val sqlContext: SQLContext,
                          parameters: Map[String, String],
                          userSpecifiedSchema: Option[StructType])
  extends BaseRelation
    with InsertableRelation
    with PrunedFilteredScan
    with Keys
    with Serializable {

  val redisConfig: RedisConfig = {
    new RedisConfig(
      if ((parameters.keySet & Set("host", "port", "auth", "dbNum", "timeout")).isEmpty) {
        new RedisEndpoint(sqlContext.sparkContext.getConf)
      } else {
        val host = parameters.getOrElse("host", Protocol.DEFAULT_HOST)
        val port = parameters.get("port").map(_.toInt).getOrElse(Protocol.DEFAULT_PORT)
        val auth = parameters.getOrElse("auth", null)
        val dbNum = parameters.get("dbNum").map(_.toInt).getOrElse(Protocol.DEFAULT_DATABASE)
        val timeout = parameters.get("timeout").map(_.toInt).getOrElse(Protocol.DEFAULT_TIMEOUT)
        RedisEndpoint(host, port, auth, dbNum, timeout)
      }
    )
  }

  // TODO: allow to specify user parameter
  val tableName: String = parameters.getOrElse("path", throw new IllegalArgumentException("'path' parameter is not specified"))

  private val tableKeys = s"$tableName:*"

  override def schema: StructType = {
    userSpecifiedSchema.getOrElse(loadSchema(tableName))
  }

  override def insert(data: DataFrame, overwrite: Boolean): Unit = {
    // write schema, so that we can load dataframe back
    saveSchema(userSpecifiedSchema.getOrElse(data.schema), tableName)

    if (overwrite) {
      // truncate the table
      redisConfig.hosts.foreach { node =>
        val conn = node.connect()
        val keys = conn.keys(dataKeyPattern(tableName))
        if (keys.nonEmpty) {
          val keySeq = JavaConversions.asScalaSet(keys).toSeq
          conn.del(keySeq: _*)
        }
        conn.close()
      }
    }

    // write data
    data.foreachPartition { partition =>
      // TODO: allow user to specify key column
      val rowsWithKey: Map[String, Row] = partition.map(row => generateKey(tableName) -> row).toMap
      groupKeysByNode(redisConfig.hosts, rowsWithKey.keysIterator).foreach { case (node, keys) =>
        val conn = node.connect()
        val pipeline = conn.pipelined()
        keys.foreach { key =>
          println(s"saving key $key")
          val row = rowsWithKey(key)
          // serialize the entire row to byte array
          // TODO: remove schema from row
          // TODO: save as a hash
          val rowBytes = SerializationUtils.serialize(row)
          pipeline.set(key.getBytes, rowBytes)
        }
        pipeline.sync()
        conn.close()
      }
    }
  }

  def generateKey(prefix: String): String = {
    val uuid = UUID.randomUUID().toString.replace("-", "")
    s"$prefix:dataframe_data:$uuid"
  }

  def isEmpty: Boolean =
    sqlContext.sparkContext.fromRedisKeyPattern(tableKeys)
      .isEmpty()

  // TODO: reuse connection to node?
  def saveSchema(schema: StructType, tableName: String): Unit = {
    val schemaKey = schemaRedisKey(tableName)
    println(s"saving schema $schemaKey")
    val schemaNode = getMasterNode(redisConfig.hosts, schemaKey)
    val conn = schemaNode.connect()
    val schemaBytes = SerializationUtils.serialize(schema)
    conn.set(schemaKey.getBytes, schemaBytes)
    conn.close()
  }

  // TODO: reuse connection to node?
  def loadSchema(tableName: String): StructType = {
    val schemaKey = schemaRedisKey(tableName)
    println(s"loading schema $schemaKey")
    val schemaNode = getMasterNode(redisConfig.hosts, schemaKey)
    val conn = schemaNode.connect()
    val schemaBytes = conn.get(schemaKey.getBytes)
    val schema = SerializationUtils.deserialize[StructType](schemaBytes)
    conn.close()
    schema
  }

  override def buildScan(requiredColumns: Array[String], filters: Array[Filter]): RDD[Row] = {
    println("build scan")
    val schema = loadSchema(tableName)
    val schemaKey = schemaRedisKey(tableName)
    // TODO: partition num
    val keysRdd = new RedisKeysRDD(sqlContext.sparkContext, redisConfig, tableKeys)
    keysRdd.mapPartitions { partition =>
      groupKeysByNode(redisConfig.hosts, partition).flatMap { case (node, keys) =>
        val conn = node.connect()
        val pipeline = conn.pipelined()
        keys
          .filterNot(_.startsWith(schemaKey)) // skip schema key
          .foreach { key =>
          println(s"key $key")
          pipeline.get(key.getBytes)
        }
        val rows = pipeline.syncAndReturnAll().map { resp =>
          val value = resp.asInstanceOf[Array[Byte]]
          SerializationUtils.deserialize[Row](value)
        }
        conn.close()
        rows
      }.iterator
    }
  }

  override def unhandledFilters(filters: Array[Filter]): Array[Filter] = filters
}

object RedisSourceRelation {

  private val SchemaKey = "dataframe_schema"

  def schemaRedisKey(tableName: String): String = s"$tableName:$SchemaKey"

  def dataKeyPattern(tableName: String): String = s"$tableName:dataframe_data:*"

}