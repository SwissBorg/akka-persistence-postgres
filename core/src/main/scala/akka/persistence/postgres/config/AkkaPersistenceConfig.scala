/*
 * Copyright (C) 2014 - 2019 Dennis Vriend <https://github.com/dnvriend>
 * Copyright (C) 2019 - 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.postgres.config

import akka.persistence.postgres.util.ConfigOps._
import com.typesafe.config.Config

import scala.concurrent.duration._

object ConfigKeys {
  val useSharedDb = "use-shared-db"
}

class SlickConfiguration(config: Config) {
  val jndiName: Option[String] = config.as[String]("jndiName").trim
  val jndiDbName: Option[String] = config.as[String]("jndiDbName")
  override def toString: String = s"SlickConfiguration($jndiName,$jndiDbName)"
}

class JournalTableColumnNames(config: Config) {
  private val cfg = config.asConfig("tables.journal.columnNames")
  val ordering: String = cfg.as[String]("ordering", "ordering")
  val deleted: String = cfg.as[String]("deleted", "deleted")
  val persistenceId: String = cfg.as[String]("persistenceId", "persistence_id")
  val sequenceNumber: String = cfg.as[String]("sequenceNumber", "sequence_number")
  val created: String = cfg.as[String]("created", "created")
  val tags: String = cfg.as[String]("tags", "tags")
  val message: String = cfg.as[String]("message", "message")
  override def toString: String = s"JournalTableColumnNames($persistenceId,$sequenceNumber,$created,$tags,$message)"
}

class JournalTableConfiguration(config: Config) {
  private val cfg = config.asConfig("tables.journal")
  val tableName: String = cfg.as[String]("tableName", "journal")
  val schemaName: Option[String] = cfg.as[String]("schemaName").trim
  val columnNames: JournalTableColumnNames = new JournalTableColumnNames(config)
  override def toString: String = s"JournalTableConfiguration($tableName,$schemaName,$columnNames)"
}

class SnapshotTableColumnNames(config: Config) {
  private val cfg = config.asConfig("tables.snapshot.columnNames")
  val persistenceId: String = cfg.as[String]("persistenceId", "persistence_id")
  val sequenceNumber: String = cfg.as[String]("sequenceNumber", "sequence_number")
  val created: String = cfg.as[String]("created", "created")
  val snapshot: String = cfg.as[String]("snapshot", "snapshot")
  override def toString: String = s"SnapshotTableColumnNames($persistenceId,$sequenceNumber,$created,$snapshot)"
}

class SnapshotTableConfiguration(config: Config) {
  private val cfg = config.asConfig("tables.snapshot")
  val tableName: String = cfg.as[String]("tableName", "snapshot")
  val schemaName: Option[String] = cfg.as[String]("schemaName").trim
  val columnNames: SnapshotTableColumnNames = new SnapshotTableColumnNames(config)
  override def toString: String = s"SnapshotTableConfiguration($tableName,$schemaName,$columnNames)"
}

class JournalPluginConfig(config: Config) {
  val dao: String = config.as[String]("dao", "akka.persistence.jdbc.dao.bytea.journal.ByteArrayJournalDao")
  override def toString: String = s"JournalPluginConfig($dao)"
}

class BaseByteArrayJournalDaoConfig(config: Config) {
  val bufferSize: Int = config.asInt("bufferSize", 1000)
  val batchSize: Int = config.asInt("batchSize", 400)
  val replayBatchSize: Int = config.asInt("replayBatchSize", 400)
  val parallelism: Int = config.asInt("parallelism", 8)
  val logicalDelete: Boolean = config.asBoolean("logicalDelete", default = true)
  val partitioned: Boolean = config.asBoolean("partitioned", default = false)
  override def toString: String =
    s"BaseByteArrayJournalDaoConfig($bufferSize,$batchSize,$replayBatchSize,$parallelism,$logicalDelete,$partitioned)"
}

class ReadJournalPluginConfig(config: Config) {
  val dao: String = config.as[String]("dao", "akka.persistence.jdbc.dao.bytea.readjournal.ByteArrayReadJournalDao")
  override def toString: String = s"ReadJournalPluginConfig($dao)"
}

class SnapshotPluginConfig(config: Config) {
  val dao: String = config.as[String]("dao", "akka.persistence.jdbc.dao.bytea.snapshot.ByteArraySnapshotDao")
  override def toString: String = s"SnapshotPluginConfig($dao)"
}

class TagsConfig(config: Config) {
  private val cfg = config.asConfig("tags")
  val cacheTtl: FiniteDuration = cfg.asFiniteDuration("cacheTtl", 1.hour)
  val insertionRetryAttempts: Int =
    if (cfg.hasPath("insertionRetryAttempts")) cfg.getInt("insertionRetryAttempts")
    else 1
  override def toString: String = s"TagResolverConfig($cacheTtl, $insertionRetryAttempts)"
}

// aggregations

class JournalConfig(config: Config) {
  val journalTableConfiguration = new JournalTableConfiguration(config)
  val pluginConfig = new JournalPluginConfig(config)
  val daoConfig = new BaseByteArrayJournalDaoConfig(config)
  val tagsConfig = new TagsConfig(config)
  val useSharedDb: Option[String] = config.asOptionalNonEmptyString(ConfigKeys.useSharedDb)
  override def toString: String = s"JournalConfig($journalTableConfiguration,$pluginConfig,$tagsConfig,$useSharedDb)"
}

class SnapshotConfig(config: Config) {
  val snapshotTableConfiguration = new SnapshotTableConfiguration(config)
  val pluginConfig = new SnapshotPluginConfig(config)
  val useSharedDb: Option[String] = config.asOptionalNonEmptyString(ConfigKeys.useSharedDb)
  override def toString: String = s"SnapshotConfig($snapshotTableConfiguration,$pluginConfig,$useSharedDb)"
}

object JournalSequenceRetrievalConfig {
  def apply(config: Config): JournalSequenceRetrievalConfig =
    JournalSequenceRetrievalConfig(
      batchSize = config.asInt("journal-sequence-retrieval.batch-size", 10000),
      maxTries = config.asInt("journal-sequence-retrieval.max-tries", 10),
      queryDelay = config.asFiniteDuration("journal-sequence-retrieval.query-delay", 1.second),
      maxBackoffQueryDelay = config.asFiniteDuration("journal-sequence-retrieval.max-backoff-query-delay", 1.minute),
      askTimeout = config.asFiniteDuration("journal-sequence-retrieval.ask-timeout", 1.second))
}
case class JournalSequenceRetrievalConfig(
    batchSize: Int,
    maxTries: Int,
    queryDelay: FiniteDuration,
    maxBackoffQueryDelay: FiniteDuration,
    askTimeout: FiniteDuration)

class ReadJournalConfig(config: Config) {
  val journalTableConfiguration = new JournalTableConfiguration(config)
  val journalSequenceRetrievalConfiguration = JournalSequenceRetrievalConfig(config)
  val pluginConfig = new ReadJournalPluginConfig(config)
  val tagsConfig = new TagsConfig(config)
  val refreshInterval: FiniteDuration = config.asFiniteDuration("refresh-interval", 1.second)
  val maxBufferSize: Int = config.as[String]("max-buffer-size", "500").toInt
  val addShutdownHook: Boolean = config.asBoolean("add-shutdown-hook", true)
  val includeDeleted: Boolean = config.as[Boolean]("includeLogicallyDeleted", true)

  override def toString: String =
    s"ReadJournalConfig($journalTableConfiguration,$pluginConfig,$refreshInterval,$maxBufferSize,$addShutdownHook,$includeDeleted)"
}
