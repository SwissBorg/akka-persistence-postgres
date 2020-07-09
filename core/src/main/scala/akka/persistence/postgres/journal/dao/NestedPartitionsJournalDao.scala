package akka.persistence.postgres.journal.dao

import java.sql.SQLException
import java.util.concurrent.ConcurrentHashMap

import akka.persistence.postgres.JournalRow
import akka.persistence.postgres.config.JournalConfig
import akka.persistence.postgres.db.DbErrorCodes
import akka.serialization.Serialization
import akka.stream.Materializer
import slick.jdbc.JdbcBackend.Database

import scala.collection.immutable.{ List, Nil, Seq }
import scala.concurrent.{ ExecutionContext, Future }

class NestedPartitionsJournalDao(db: Database, journalConfig: JournalConfig, serialization: Serialization)(
    implicit ec: ExecutionContext,
    mat: Materializer)
    extends FlatJournalDao(db, journalConfig, serialization) {
  private val journalTableCfg = journalConfig.journalTableConfiguration
  private val partitionSize = journalConfig.partitionsConfig.size
  private val partitionPrefix = journalConfig.partitionsConfig.prefix

  override protected def writeJournalRows(xs: Seq[JournalRow]): Future[Unit] =
    attachJournalPartition(xs).flatMap(_ => super.writeJournalRows(xs))

  private val createdPartitions = new ConcurrentHashMap[String, List[Long]]()

  def attachJournalPartition(xs: Seq[JournalRow])(implicit ec: ExecutionContext): Future[Unit] = {
    import akka.persistence.postgres.db.ExtendedPostgresProfile.api._
    val persistenceIdToMaxSequenceNumber =
      xs.groupBy(_.persistenceId).mapValues(_.map(_.sequenceNumber)).mapValues(sq => (sq.min, sq.max))
    val databaseOperations = persistenceIdToMaxSequenceNumber.toList.map {
      case (persistenceId, (minSeqNr, maxSeqNr)) =>
        val requiredPartitions = minSeqNr / partitionSize to maxSeqNr / partitionSize
        val existingPartitions = createdPartitions.getOrDefault(persistenceId, Nil)
        val partitionsToCreate = requiredPartitions.toList.filter(!existingPartitions.contains(_))

        if (partitionsToCreate.nonEmpty) {
          logger.info(s"Adding missing journal partition...")
          // tableName can contain only digits, letters and _ (underscore), all other characters will be replaced with _ (underscore)
          val sanitizedPersistenceId = persistenceId.replaceAll("\\W", "_")
          val tableName = s"${partitionPrefix}_$sanitizedPersistenceId"
          val schema = journalTableCfg.schemaName.map(_ + ".").getOrElse("")
          val actions = for {
            _ <- sqlu"""CREATE TABLE IF NOT EXISTS #${schema + tableName} PARTITION OF #${schema + journalTableCfg.tableName} FOR VALUES IN ('#$persistenceId') PARTITION BY RANGE (#${journalTableCfg.columnNames.sequenceNumber})"""
            _ <- slick.jdbc.PostgresProfile.api.DBIO.sequence {
              for (partitionNumber <- partitionsToCreate) yield {
                val name = s"${tableName}_$partitionNumber"
                val minRange = partitionNumber * partitionSize
                val maxRange = minRange + partitionSize
                sqlu"""CREATE TABLE IF NOT EXISTS #${schema + name} PARTITION OF #${schema + tableName} FOR VALUES FROM (#$minRange) TO (#$maxRange)"""
              }
            }
          } yield ()
          db.run(actions)
            .recoverWith {
              case ex: SQLException if ex.getSQLState == DbErrorCodes.PgDuplicateTable =>
                // Partition already created from another session, all good, recovery succeeded
                Future.successful(())
            }
            .map(_ => {
              createdPartitions.put(persistenceId, existingPartitions ::: partitionsToCreate)
            })
        } else {
          Future.successful(())
        }
    }

    Future.sequence(databaseOperations).map(_ => ())
  }
}
