package akka.persistence.postgres.migration.journal

import akka.Done
import akka.actor.ActorSystem
import akka.persistence.postgres.JournalRow
import akka.persistence.postgres.config.JournalConfig
import akka.persistence.postgres.db.SlickExtension
import akka.persistence.postgres.journal.dao._
import akka.persistence.postgres.migration.PgSlickSupport
import akka.persistence.postgres.tag.{ CachedTagIdResolver, SimpleTagDao }
import akka.serialization.{ Serialization, SerializationExtension }
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.typesafe.config.Config
import slick.jdbc.{ JdbcBackend, ResultSetConcurrency, ResultSetType }

import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, Future }
import scala.util.Failure

class Jdbc4JournalMigration(globalConfig: Config, tempTableName: String = "tmp_journal")(
    implicit system: ActorSystem,
    mat: Materializer)
    extends PgSlickSupport {

  import akka.persistence.postgres.db.ExtendedPostgresProfile.api._
  import system.dispatcher

  private val db = {
    val slickDb = SlickExtension(system).database(globalConfig.getConfig("postgres-journal"))
    slickDb.database
  }
  private val journalConfig = new JournalConfig(globalConfig.getConfig("postgres-journal"))

  private lazy val serialization = SerializationExtension(system)

  private val journalTableConfig = journalConfig.journalTableConfiguration
  private lazy val journalSchema = JournalSchema(journalConfig, tempTableName)

  private lazy val journalQueries = new JournalMigrationQueries(journalSchema.getTable)

  private val journalTableName = journalTableConfig.schemaName.map(_ + ".").getOrElse("") + journalTableConfig.tableName
  private val tagResolver = {
    val tagDao = new SimpleTagDao(db, journalConfig.tagsTableConfiguration)
    new CachedTagIdResolver(tagDao, journalConfig.tagsConfig)
  }

  private val migrationConf: Config = globalConfig.getConfig("akka-persistence-postgres.migration")
  private val migrationBatchSize: Int = migrationConf.getInt("batchSize")

  def run(): Future[Done] = {
    val migrationRes = migrateJournal(db, serialization)

    migrationRes.onComplete {
      case Failure(exception) => log.error(s"Metadata extraction has failed", exception)
      case _                  => log.info(s"Metadata extraction completed")
    }

    migrationRes
  }

  def migrateJournal(db: JdbcBackend.Database, serialization: Serialization): Future[Done] = {
    val deserializer = new OldJournalDeserializer(serialization)
    val serializer = new ByteArrayJournalSerializer(serialization, tagResolver)

    log.info(s"Migrating journal entries...")

    val createTables: DBIOAction[Unit, NoStream, Effect.Write] = {
      for {
        _ <- journalSchema.createTable
        _ <- journalSchema.createTagsTable
        _ <- journalSchema.createJournalPersistenceIdsTable
      } yield ()
    }

    val tagsSeparator = ','

    val eventsPublisher = {
      import journalTableConfig.columnNames._
      db.stream(
        sql"SELECT #$ordering, #$deleted, #$persistenceId, #$sequenceNumber, #$message, #$tags FROM #$journalTableName ORDER BY #$ordering ASC"
          .as[(Long, Boolean, String, Long, Array[Byte], Option[String])]
          .withStatementParameters(
            rsType = ResultSetType.ForwardOnly,
            rsConcurrency = ResultSetConcurrency.ReadOnly,
            fetchSize = migrationBatchSize)
          .transactionally)
    }

    val dml = Source
      .fromPublisher(eventsPublisher)
      .mapAsync(1) { case (ordering, deleted, persistenceId, sequenceNumber, oldMessage, tags) =>
        for {
          pr <- Future.fromTry(deserializer.deserialize(oldMessage))
          jr <- serializer.serialize(pr, tags.map(_.split(tagsSeparator).toSet).getOrElse(Set.empty))
        } yield JournalRow(ordering, deleted, persistenceId, sequenceNumber, jr.message, jr.tags, jr.metadata)
      }
      .batch(migrationBatchSize, List(_))(_ :+ _)
      .map(journalQueries.insertAll)
      .foldAsync(0L) { (cnt, bulkUpdate) =>
        db.run(bulkUpdate).map { numOfEvents =>
          val total = cnt + numOfEvents
          log.info(s"Migrated $numOfEvents journal events ($total total)...")
          total
        }
      }

    val fut = for {
      _ <- db.run(createTables.transactionally)
      _ <- db.run(journalSchema.createTriggers.transactionally)
      cnt <- dml.runReduce(_ + _)
      _ <- db.run(journalSchema.createSequence.transactionally)
      _ <- db.run(journalSchema.createIndexes.transactionally)
      _ <- db.run(journalSchema.swapJournals.transactionally)
    } yield {
      log.info(s"Journal migration completed - $cnt events have been successfully updated")
      Done
    }
    fut.onComplete(_ => db.close())
    fut
  }

}
