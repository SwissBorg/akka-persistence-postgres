package akka.persistence.postgres.migration.snapshot

import akka.Done
import akka.actor.ActorSystem
import akka.persistence.SnapshotMetadata
import akka.persistence.postgres.config.SnapshotConfig
import akka.persistence.postgres.db.SlickExtension
import akka.persistence.postgres.migration.PgSlickSupport
import akka.persistence.postgres.snapshot.dao.ByteArraySnapshotSerializer
import akka.persistence.postgres.snapshot.dao.SnapshotTables.SnapshotRow
import akka.serialization.{ Serialization, SerializationExtension }
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.typesafe.config.Config
import slick.jdbc.{ JdbcBackend, ResultSetConcurrency, ResultSetType }

import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, Future }
import scala.util.Failure

class Jdbc4SnapshotStoreMigration(globalConfig: Config, tempTableName: String = "tmp_snapshot")(
    implicit system: ActorSystem,
    mat: Materializer)
    extends PgSlickSupport {

  import akka.persistence.postgres.db.ExtendedPostgresProfile.api._
  import system.dispatcher

  private val db = {
    val slickDb = SlickExtension(system).database(globalConfig.getConfig("postgres-snapshot-store"))
    slickDb.database
  }
  private val snapshotConfig = new SnapshotConfig(globalConfig.getConfig("postgres-snapshot-store"))

  private lazy val serialization = SerializationExtension(system)

  private val snapshotTableConfig = snapshotConfig.snapshotTableConfiguration
  private lazy val snapshotQueries = new SnapshotMigrationQueries(snapshotTableConfig, tempTableName)

  private val migrationConf: Config = globalConfig.getConfig("akka-persistence-postgres.migration")
  private val migrationBatchSize: Int = migrationConf.getInt("batchSize")

  def run(): Future[Done] = {
    val migrationRes = migrateSnapshots(db, serialization)

    migrationRes.onComplete {
      case Failure(exception) => log.error(s"Metadata extraction has failed", exception)
      case _                  => log.info(s"Metadata extraction completed")
    }

    migrationRes
  }

  def migrateSnapshots(db: JdbcBackend.Database, serialization: Serialization): Future[Done] = {
    val deserializer = new OldSnapshotDeserializer(serialization)
    val serializer = new ByteArraySnapshotSerializer(serialization)

    log.info(s"Migrating snapshots...")

    import snapshotTableConfig.columnNames._
    val schema = snapshotTableConfig.schemaName.getOrElse("public")
    val fullTmpTableName = s"$schema.$tempTableName"
    val sourceTableName = snapshotTableConfig.tableName
    val fullSourceTableName = s"$schema.$sourceTableName"

    val createTable = for {
      _ <- sqlu"""CREATE TABLE IF NOT EXISTS #$fullTmpTableName (
              #$persistenceId  TEXT   NOT NULL,
              #$sequenceNumber BIGINT NOT NULL,
              #$created        BIGINT NOT NULL,
              #$snapshot       BYTEA  NOT NULL,
              #$metadata       jsonb  NOT NULL,
              PRIMARY KEY (#$persistenceId, #$sequenceNumber))"""
    } yield ()

    val swapSnapshotStores = for {
      _ <- sqlu"""ALTER TABLE #$fullSourceTableName RENAME TO old_#$sourceTableName"""
      _ <- sqlu"""ALTER INDEX #${fullSourceTableName}_pkey RENAME TO old_#${sourceTableName}_pkey"""
      _ <- sqlu"""ALTER TABLE #$fullTmpTableName RENAME TO #$sourceTableName"""
      _ <- sqlu"""ALTER INDEX #${fullTmpTableName}_pkey RENAME TO #${sourceTableName}_pkey"""
    } yield ()

    val eventsPublisher = {
      import snapshotTableConfig.columnNames._
      db.stream {
        sql"SELECT #$persistenceId, #$sequenceNumber, #$created, #$snapshot FROM #$fullSourceTableName"
          .as[(String, Long, Long, Array[Byte])]
          .withStatementParameters(
            rsType = ResultSetType.ForwardOnly,
            rsConcurrency = ResultSetConcurrency.ReadOnly,
            fetchSize = migrationBatchSize)
          .transactionally
      }
    }

    val dml = Source
      .fromPublisher(eventsPublisher)
      .mapAsync(1) { case (persistenceId, sequenceNumber, created, serializedOldSnapshot) =>
        Future.fromTry {
          for {
            oldSnapshot <- deserializer.deserialize(serializedOldSnapshot)
            sr <- serializer.serialize(SnapshotMetadata(persistenceId, sequenceNumber, created), oldSnapshot)
          } yield SnapshotRow(persistenceId, sequenceNumber, created, sr.snapshot, sr.metadata)
        }
      }
      .batch(migrationBatchSize, List(_))(_ :+ _)
      .map(snapshotQueries.insertOrUpdate)
      .foldAsync(0L) { (cnt, bulkUpdate) =>
        db.run(bulkUpdate).map { numOfEvents =>
          val total = cnt + numOfEvents
          log.info(s"Migrated $numOfEvents snapshots ($total total)...")
          total
        }
      }

    for {
      _ <- db.run(createTable)
      cnt <- dml.runReduce(_ + _)
      _ <- db.run(swapSnapshotStores)
    } yield {
      log.info(s"Snapshot migration completed - $cnt events have been successfully updated")
      Done
    }
  }
}
