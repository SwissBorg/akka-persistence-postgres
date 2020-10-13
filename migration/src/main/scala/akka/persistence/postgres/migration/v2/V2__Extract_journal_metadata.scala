package akka.persistence.postgres.migration.v2

import akka.Done
import akka.actor.{ ActorSystem, ExtendedActorSystem }
import akka.persistence.postgres.config.{ JournalConfig, SnapshotConfig }
import akka.persistence.postgres.journal.dao._
import akka.persistence.postgres.migration.SlickMigration
import akka.persistence.postgres.migration.v2.journal._
import akka.persistence.postgres.migration.v2.snapshot.{
  NewSnapshotSerializer,
  OldSnapshotDeserializer,
  SnapshotMigrationQueries,
  TempSnapshotRow
}
import akka.serialization.{ Serialization, SerializationExtension }
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.typesafe.config.Config
import org.flywaydb.core.api.migration.Context
import slick.jdbc.JdbcBackend

import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, Future }
import scala.util.Failure

// Class name must obey FlyWay naming rules (https://flywaydb.org/documentation/migrations#naming-1)
private[migration] class V2__Extract_journal_metadata(globalConfig: Config, db: JdbcBackend.Database)(
    implicit system: ActorSystem,
    mat: Materializer)
    extends SlickMigration {

  import system.dispatcher

  private lazy val serialization = SerializationExtension(system)

  private val journalConfig = new JournalConfig(globalConfig.getConfig("postgres-journal"))
  private val journalTableConfig = journalConfig.journalTableConfiguration
  private lazy val journalQueries = {
    val fqcn = journalConfig.pluginConfig.dao
    val daoClass =
      system.asInstanceOf[ExtendedActorSystem].dynamicAccess.getClassFor[JournalDao](fqcn).fold(throw _, identity)
    // DAO matters - different implementations might be using different schemas with different compound primary keys.
    val journalTable = if (classOf[PartitionedJournalDao].isAssignableFrom(daoClass)) {
      log.info(s"Using Partitioned journal schema (dao = '$fqcn')")
      TempPartitionedJournalTable(journalTableConfig)
    } else if (classOf[NestedPartitionsJournalDao].isAssignableFrom(daoClass)) {
      log.info(s"Using Nested Partitions journal schema (dao = '$fqcn')")
      TempNestedPartitionsJournalTable(journalTableConfig)
    } else {
      log.info(s"Using default (flat) journal schema (dao = '$fqcn')")
      TempFlatJournalTable(journalTableConfig)
    }

    new JournalMigrationQueries(journalTable)
  }
  private val journalTableName = journalTableConfig.schemaName.map(_ + ".").getOrElse("") + journalTableConfig.tableName

  private val snapshotConfig = new SnapshotConfig(globalConfig.getConfig("postgres-snapshot-store"))
  private val snapshotTableConfig = snapshotConfig.snapshotTableConfiguration
  private lazy val snapshotQueries = new SnapshotMigrationQueries(snapshotTableConfig)
  private val snapshotTableName =
    snapshotTableConfig.schemaName.map(_ + ".").getOrElse("") + snapshotTableConfig.tableName

  private val migrationConf: Config = globalConfig.getConfig("akka-persistence-postgres.migration")
  private val migrationBatchSize: Long = migrationConf.getLong("v2.batchSize")
  private val conversionParallelism: Int = migrationConf.getInt("v2.parallelism")

  @throws[Exception]
  override def migrate(context: Context): Unit = {
    val migrationRes = for {
      _ <- migrateJournal(db, serialization)
      _ <- migrateSnapshots(db, serialization)
      _ <- finishMigration()
    } yield Done

    migrationRes.onComplete {
      case Failure(exception) => log.error(s"Metadata extraction has failed", exception)
      case _                  => log.info(s"Metadata extraction is now completed")
    }

    Await.result(migrationRes, Duration.Inf)
  }

  def migrateJournal(db: JdbcBackend.Database, serialization: Serialization): Future[Done] = {
    val deserializer = new OldJournalDeserializer(serialization)
    val serializer = new NewJournalSerializer(serialization)

    log.info(s"Start migrating journal entries...")

    val ddl = for {
      _ <- db.run(
        sqlu"ALTER TABLE #$journalTableName ADD COLUMN IF NOT EXISTS #${journalTableConfig.columnNames.metadata} jsonb")
      _ <- db.run(sqlu"ALTER TABLE #$journalTableName ADD COLUMN IF NOT EXISTS temp_message bytea")
    } yield Done

    val eventsPublisher = {
      import journalTableConfig.columnNames._
      db.stream(
        sql"SELECT #$ordering, #$deleted, #$persistenceId, #$sequenceNumber, #$message, #$tags FROM #$journalTableName WHERE #$metadata IS NULL"
          .as[(Long, Boolean, String, Long, Array[Byte], List[Int])])
    }

    val dml = Source
      .fromPublisher(eventsPublisher)
      .mapAsync(conversionParallelism) {
        case (ordering, deleted, persistenceId, sequenceNumber, oldMessage, tags) =>
          Future.fromTry {
            for {
              pr <- deserializer.deserialize(oldMessage)
              (newMessage, metadata) <- serializer.serialize(pr)
            } yield TempJournalRow(
              ordering,
              deleted,
              persistenceId,
              sequenceNumber,
              oldMessage,
              newMessage,
              tags,
              metadata)
          }
      }
      .batch(migrationBatchSize, List(_))(_ :+ _)
      .map(journalQueries.updateAll)
      .foldAsync(0L) { (cnt, bulkUpdate) =>
        db.run(bulkUpdate).map { numOfEvents =>
          val total = cnt + numOfEvents
          log.info(s"Updated a batch of $numOfEvents events ($total total)...")
          total
        }
      }

    for {
      _ <- ddl
      cnt <- dml.runReduce(_ + _)
    } yield {
      log.info(s"Journal metadata extraction completed - $cnt events have been successfully updated")
      Done
    }
  }

  def migrateSnapshots(db: JdbcBackend.Database, serialization: Serialization): Future[Done] = {
    val deserializer = new OldSnapshotDeserializer(serialization)
    val serializer = new NewSnapshotSerializer(serialization)

    log.info(s"Start migrating snapshots...")

    val ddl = for {
      _ <- db.run(
        sqlu"ALTER TABLE #$snapshotTableName ADD COLUMN IF NOT EXISTS #${snapshotTableConfig.columnNames.metadata} jsonb")
      _ <- db.run(sqlu"ALTER TABLE #$snapshotTableName ADD COLUMN IF NOT EXISTS temp_snapshot bytea")
    } yield Done

    val eventsPublisher = {
      import snapshotTableConfig.columnNames._
      db.stream {
        sql"SELECT #$persistenceId, #$sequenceNumber, #$created, #$snapshot FROM #$snapshotTableName WHERE #$metadata IS NULL"
          .as[(String, Long, Long, Array[Byte])]
      }
    }

    val dml = Source
      .fromPublisher(eventsPublisher)
      .mapAsync(conversionParallelism) {
        case (persistenceId, sequenceNumber, created, serializedOldSnapshot) =>
          Future.fromTry {
            for {
              oldSnapshot <- deserializer.deserialize(serializedOldSnapshot)
              (newSnapshot, metadata) <- serializer.serialize(oldSnapshot)
            } yield TempSnapshotRow(
              persistenceId,
              sequenceNumber,
              created,
              serializedOldSnapshot,
              newSnapshot,
              metadata)
          }
      }
      .batch(migrationBatchSize, List(_))(_ :+ _)
      .map(snapshotQueries.insertOrUpdate)
      .foldAsync(0L) { (cnt, bulkUpdate) =>
        db.run(bulkUpdate).map { numOfEvents =>
          val total = cnt + numOfEvents
          log.info(s"Updated $numOfEvents events ($total total)...")
          total
        }
      }

    for {
      _ <- ddl
      cnt <- dml.runReduce(_ + _)
    } yield {
      log.info(s"Snapshot metadata extraction completed - $cnt events have been successfully updated")
      Done
    }
  }

  def finishMigration(): Future[Done] = {
    log.info(s"Cleaning up temporary migration columns...")
    val finishJournalMigration: DBIO[Done] = {
      import journalTableConfig.columnNames._
      for {
        _ <- sqlu"ALTER TABLE #$journalTableName ALTER COLUMN temp_message SET NOT NULL"
        _ <- sqlu"ALTER TABLE #$journalTableName ALTER COLUMN #$metadata SET NOT NULL"
        _ <- sqlu"ALTER TABLE #$journalTableName DROP COLUMN #$message"
        _ <- sqlu"ALTER TABLE #$journalTableName RENAME COLUMN temp_message TO #$message"
      } yield Done
    }

    val finishSnapshotMigration: DBIO[Done] = {
      import snapshotTableConfig.columnNames._
      for {
        _ <- sqlu"ALTER TABLE #$snapshotTableName ALTER COLUMN temp_snapshot SET NOT NULL"
        _ <- sqlu"ALTER TABLE #$snapshotTableName ALTER COLUMN #$metadata SET NOT NULL"
        _ <- sqlu"ALTER TABLE #$snapshotTableName DROP COLUMN #$snapshot"
        _ <- sqlu"ALTER TABLE #$snapshotTableName RENAME COLUMN temp_snapshot TO #$snapshot"
      } yield Done
    }

    for {
      _ <- db.run(DBIO.sequence(List(finishJournalMigration, finishSnapshotMigration)).transactionally)
    } yield Done
  }

}
