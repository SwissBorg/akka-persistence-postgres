package akka.persistence.postgres.journal.dao

import java.util.concurrent.ConcurrentHashMap

import akka.persistence.postgres.JournalRow
import akka.persistence.postgres.config.JournalConfig
import akka.persistence.postgres.db.DbErrors.withHandledPartitionErrors
import akka.persistence.postgres.db.ExtendedPostgresProfile.api._
import akka.serialization.Serialization
import akka.stream.Materializer
import slick.jdbc.JdbcBackend.Database

import scala.collection.immutable.{ List, Nil, Seq }
import scala.concurrent.{ ExecutionContext, Future }

class NestedPartitionsJournalDao(db: Database, journalConfig: JournalConfig, serialization: Serialization)(
    implicit ec: ExecutionContext,
    mat: Materializer)
    extends FlatJournalDao(db, journalConfig, serialization) {
  override val queries = new JournalQueries(
    NestedPartitionsJournalTable(journalConfig.journalTableConfiguration),
    JournalPersistenceIdsTable(journalConfig.journalPersistenceIdsTableConfiguration))
  private val journalTableCfg = journalConfig.journalTableConfiguration
  private val partitionSize = journalConfig.partitionsConfig.size
  private val partitionPrefix = journalConfig.partitionsConfig.prefix

  override protected def writeJournalRows(xs: Seq[JournalRow]): Future[Unit] =
    attachJournalPartition(xs).flatMap(_ => super.writeJournalRows(xs))

  private val createdPartitions = new ConcurrentHashMap[String, List[Long]]()

  def attachJournalPartition(xs: Seq[JournalRow]): Future[Unit] = {
    val persistenceIdToMaxSequenceNumber =
      xs.groupBy(_.persistenceId).view.mapValues(_.map(_.sequenceNumber)).mapValues(sq => (sq.min, sq.max))
    val databaseOperations = persistenceIdToMaxSequenceNumber.toList.map { case (persistenceId, (minSeqNr, maxSeqNr)) =>
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
          withHandledPartitionErrors(logger, s"persistenceId = '$persistenceId'") {
            sqlu"""CREATE TABLE IF NOT EXISTS #${schema + tableName} PARTITION OF #${schema + journalTableCfg.tableName} FOR VALUES IN ('#$persistenceId') PARTITION BY RANGE (#${journalTableCfg.columnNames.sequenceNumber})"""
          }

        def createSequenceNumberPartitions(): DBIOAction[Unit, NoStream, Effect] = {
          DBIO
            .sequence {
              partitionsToCreate.map { partitionNumber =>
                val name = s"${tableName}_$partitionNumber"
                val minRange = partitionNumber * partitionSize
                val maxRange = minRange + partitionSize
                val partitionDetails =
                  s"persistenceId = '$persistenceId' and sequenceNr between $minRange and $maxRange"
                withHandledPartitionErrors(logger, partitionDetails) {
                  sqlu"""CREATE TABLE IF NOT EXISTS #${schema + name} PARTITION OF #${schema + tableName} FOR VALUES FROM (#$minRange) TO (#$maxRange)""".asTry
                }
              }
            }
            .map(_ => createdPartitions.put(persistenceId, existingPartitions ::: partitionsToCreate): Unit)
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
