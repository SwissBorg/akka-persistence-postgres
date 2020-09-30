package akka.persistence.postgres.migration.v2

import java.nio.charset.StandardCharsets

import akka.Done
import akka.actor.{ ActorRef, ExtendedActorSystem }
import akka.persistence.postgres.config.{ JournalConfig, SnapshotConfig }
import akka.persistence.postgres.db.ExtendedPostgresProfile
import akka.persistence.postgres.journal.dao._
import akka.serialization.{ Serialization, SerializerWithStringManifest }
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.typesafe.config.Config
import io.circe.{ Json, Printer }
import org.flywaydb.core.api.migration.{ BaseJavaMigration, Context }
import org.slf4j.{ Logger, LoggerFactory }
import slick.jdbc.{ GetResult, JdbcBackend, SetParameter }

import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.util.Failure

abstract class SlickMigration()(implicit ec: ExecutionContext, mat: Materializer)
    extends BaseJavaMigration
    with ExtendedPostgresProfile.MyAPI {

  lazy val log: Logger = LoggerFactory.getLogger(this.getClass)

  implicit val GetIntList: GetResult[List[Int]] = GetResult(_.nextArray[Int]().toList)
  implicit val GetByteArr: GetResult[Array[Byte]] = GetResult(_.nextBytes())
  implicit val SetByteArr: SetParameter[Array[Byte]] = SetParameter((arr, pp) => pp.setBytes(arr))
  implicit val SetJson: SetParameter[Json] = SetParameter((json, pp) => pp.setString(json.printWith(Printer.noSpaces)))

  def db: JdbcBackend.Database

}

// Class name must obey FlyWay naming rules (https://flywaydb.org/documentation/migrations#naming-1)
class V2__Extract_journal_metadata(config: Config, val db: JdbcBackend.Database, serialization: Serialization)(
    implicit ec: ExecutionContext,
    mat: Materializer)
    extends SlickMigration {

  private val journalConfig = new JournalConfig(config.getConfig("postgres-journal"))
  private val journalTableConfig = journalConfig.journalTableConfiguration
  private lazy val journalQueries: JournalMigrationQueries = {
    val daoFqcn = journalConfig.pluginConfig.dao

    import akka.persistence.postgres.journal.dao.FlatJournalDao

    val table: TableQuery[TempJournalTable] =
      if (daoFqcn == classOf[FlatJournalDao].getName) TempFlatJournalTable(journalTableConfig)
      else if (daoFqcn == classOf[PartitionedJournalDao].getName)
        TempPartitionedJournalTable(journalTableConfig)
      else if (daoFqcn == classOf[NestedPartitionsJournalDao].getName)
        NewNestedPartitionsJournalTable(journalTableConfig)
      else throw new IllegalStateException(s"Unsupported DAO class - '$daoFqcn'")

    new JournalMigrationQueries(table)
  }
  private val journalTableName = journalTableConfig.schemaName.map(_ + ".").getOrElse("") + journalTableConfig.tableName

  private val snapshotConfig = new SnapshotConfig(config.getConfig("postgres-snapshot-store"))
  private val snapshotTableConfig = snapshotConfig.snapshotTableConfiguration
  private lazy val snapshotQueries = new SnapshotMigrationQueries(snapshotTableConfig)
  private val snapshotTableName = snapshotTableConfig.schemaName.map(_ + ".").getOrElse("") + snapshotTableConfig.tableName

  private val migrationConf: Config = config.getConfig("akka-persistence-postgres.migration")
  private val migrationBatchSize: Long = migrationConf.getLong("v2.batchSize")

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

  def migrateJournal(db: JdbcBackend.Database, serialization: Serialization)(
      implicit ec: ExecutionContext,
      mat: Materializer): Future[Done] = {
    val deserializer = new OldDeserializer(serialization)
    val serializer = new NewJournalSerializer(serialization)

    log.info(s"Start migrating journal entries...")

    val ddl = for {
      _ <- db.run(
        sqlu"ALTER TABLE #$journalTableName ADD COLUMN IF NOT EXISTS #${journalTableConfig.columnNames.metadata} jsonb")
      _ <- db.run(sqlu"ALTER TABLE #$journalTableName ADD COLUMN IF NOT EXISTS message_raw bytea")
    } yield Done

    val eventsPublisher = {
      import journalTableConfig.columnNames._
      db.stream(
        sql"SELECT #$ordering, #$deleted, #$persistenceId, #$sequenceNumber, #$message, #$tags FROM #$journalTableName WHERE #$metadata IS NULL"
          .as[(Long, Boolean, String, Long, Array[Byte], List[Int])])
    }

    val dml = Source
      .fromPublisher(eventsPublisher)
      .mapAsync(8) {
        case (ordering, deleted, persistenceId, sequenceNumber, oldMessage, tags) =>
          for {
            pr <- Future.fromTry(deserializer.deserialize(oldMessage))
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

  def migrateSnapshots(db: JdbcBackend.Database, serialization: Serialization)(
      implicit ec: ExecutionContext,
      mat: Materializer): Future[Done] = {
    val deserializer = new OldSnapshotDeserializer(serialization)
    val serializer = new NewSnapshotSerializer(serialization)

    log.info(s"Start migrating snapshots...")

    val ddl = for {
      _ <- db.run(
        sqlu"ALTER TABLE #$snapshotTableName ADD COLUMN IF NOT EXISTS #${snapshotTableConfig.columnNames.metadata} jsonb")
      _ <- db.run(sqlu"ALTER TABLE #$snapshotTableName ADD COLUMN IF NOT EXISTS snapshot_raw bytea")
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
      .mapAsync(8) {
        case (persistenceId, sequenceNumber, created, serializedOldSnapshot) =>
          Future.fromTry {
            for {
              oldSnapshot <- deserializer.deserialize(serializedOldSnapshot)
              (newSnapshot, metadata) <- serializer.serialize(oldSnapshot)
            } yield TempSnapshotRow(persistenceId, sequenceNumber, created, serializedOldSnapshot, newSnapshot, metadata)
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
        _ <- sqlu"ALTER TABLE #$journalTableName ALTER COLUMN message_raw SET NOT NULL"
        _ <- sqlu"ALTER TABLE #$journalTableName ALTER COLUMN #$metadata SET NOT NULL"
        _ <- sqlu"ALTER TABLE #$journalTableName DROP COLUMN #$message"
        _ <- sqlu"ALTER TABLE #$journalTableName RENAME COLUMN message_raw TO #$message"
      } yield Done
    }

    val finishSnapshotMigration: DBIO[Done] = {
      import snapshotTableConfig.columnNames._
      for {
        _ <- sqlu"ALTER TABLE #$snapshotTableName ALTER COLUMN snapshot_raw SET NOT NULL"
        _ <- sqlu"ALTER TABLE #$snapshotTableName ALTER COLUMN #$metadata SET NOT NULL"
        _ <- sqlu"ALTER TABLE #$snapshotTableName DROP COLUMN #$snapshot"
        _ <- sqlu"ALTER TABLE #$snapshotTableName RENAME COLUMN snapshot_raw TO #$snapshot"
      } yield Done
    }

    for {
     _ <- db.run(DBIO.sequence(List(finishJournalMigration, finishSnapshotMigration)).transactionally)
    } yield Done
  }

}
