package akka.persistence.postgres.journal.dao

import java.util.concurrent.ConcurrentHashMap

import akka.persistence.postgres.JournalRow
import akka.persistence.postgres.config.JournalConfig
import akka.persistence.postgres.db.DbErrors
import akka.persistence.postgres.db.ExtendedPostgresProfile.api._
import akka.serialization.Serialization
import akka.stream.Materializer
import slick.jdbc.JdbcBackend.Database

import scala.collection.immutable.{ List, Nil, Seq }
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

class NestedPartitionsJournalDao(db: Database, journalConfig: JournalConfig, serialization: Serialization)(
    implicit ec: ExecutionContext,
    mat: Materializer)
    extends FlatJournalDao(db, journalConfig, serialization) {
  override val queries = new JournalQueries(NestedPartitionsJournalTable(journalConfig.journalTableConfiguration))
  private val journalTableCfg = journalConfig.journalTableConfiguration
  private val partitionSize = journalConfig.partitionsConfig.size
  private val partitionPrefix = journalConfig.partitionsConfig.prefix

  override protected def writeJournalRows(xs: Seq[JournalRow]): Future[Unit] =
    attachJournalPartition(xs).flatMap(_ => super.writeJournalRows(xs))

  private val createdPartitions = new ConcurrentHashMap[String, List[Long]]()

  def attachJournalPartition(xs: Seq[JournalRow]): Future[Unit] = {
    val persistenceIdToMaxSequenceNumber =
      xs.groupBy(_.persistenceId).mapValues(_.map(_.sequenceNumber)).mapValues(sq => (sq.min, sq.max))
    val databaseOperations = persistenceIdToMaxSequenceNumber.toList.map {
      case (persistenceId, (minSeqNr, maxSeqNr)) =>
        val requiredPartitions = minSeqNr / partitionSize to maxSeqNr / partitionSize
        val existingPartitions = createdPartitions.getOrDefault(persistenceId, Nil)
        val partitionsToCreate = requiredPartitions.toList.filter(!existingPartitions.contains(_))

        if (partitionsToCreate.nonEmpty) {
          logger.debug(s"Adding missing journal partition for persistenceId = '$persistenceId'...")
          // tableName can contain only digits, letters and _ (underscore), all other characters will be replaced with _ (underscore)
          val sanitizedPersistenceId = persistenceId.replaceAll("\\W", "_")
          val tableName = s"${partitionPrefix}_$sanitizedPersistenceId"
          val schema = journalTableCfg.schemaName.map(_ + ".").getOrElse("")

          def createPersistenceIdPartition(): DBIOAction[Unit, NoStream, Effect] =
            sqlu"""CREATE TABLE IF NOT EXISTS #${schema + tableName} PARTITION OF #${schema + journalTableCfg.tableName} FOR VALUES IN ('#$persistenceId') PARTITION BY RANGE (#${journalTableCfg.columnNames.sequenceNumber})""".asTry
              .flatMap(swallowPartitionAlreadyExistsError)

          def createSequenceNumberPartitions(): DBIOAction[List[Unit], NoStream, Effect] = {
            DBIO.sequence {
              partitionsToCreate.map { partitionNumber =>
                val name = s"${tableName}_$partitionNumber"
                val minRange = partitionNumber * partitionSize
                val maxRange = minRange + partitionSize
                sqlu"""CREATE TABLE IF NOT EXISTS #${schema + name} PARTITION OF #${schema + tableName} FOR VALUES FROM (#$minRange) TO (#$maxRange)""".asTry
                  .flatMap(swallowPartitionAlreadyExistsError)
                  .andThen {
                    createdPartitions.put(persistenceId, existingPartitions ::: partitionsToCreate)
                    DBIO.successful(())
                  }
              }
            }
          }

          lazy val swallowPartitionAlreadyExistsError: Try[_] => DBIOAction[Unit, NoStream, Effect] = {
            case Failure(exception) =>
              logger.debug(s"Partition for persistenceId '$persistenceId' already exists")
              DBIO.from(DbErrors.SwallowPartitionAlreadyExistsError.applyOrElse(exception, Future.failed))
            case Success(_) =>
              logger.debug(s"Created missing journal partition for persistenceId '$persistenceId'")
              DBIO.successful(())
          }

          for {
            _ <- createPersistenceIdPartition()
            _ <- createSequenceNumberPartitions()
          } yield ()
        } else {
          DBIO.successful(())
        }
    }

    db.run(DBIO.sequence(databaseOperations)).map(_ => ())
  }
}
