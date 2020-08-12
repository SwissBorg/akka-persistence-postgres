package akka.persistence.postgres.journal.dao

import java.sql.SQLException
import java.util
import java.util.concurrent.atomic.{ AtomicLongArray, AtomicReference }
import java.util.concurrent.{ ConcurrentHashMap, ConcurrentLinkedQueue }

import akka.persistence.postgres.JournalRow
import akka.persistence.postgres.config.JournalConfig
import akka.persistence.postgres.db.{ DbErrorCodes, ExtendedPostgresProfile }
import akka.serialization.Serialization
import akka.stream.Materializer
import slick.jdbc.JdbcBackend.Database

import scala.collection.immutable.{ List, Nil, Seq }
import scala.concurrent.{ ExecutionContext, Future }

class PartitionedJournalDao(db: Database, journalConfig: JournalConfig, serialization: Serialization)(
    implicit ec: ExecutionContext,
    mat: Materializer)
    extends FlatJournalDao(db, journalConfig, serialization) {
  private val journalTableCfg = journalConfig.journalTableConfiguration
  private val partitionSize = journalConfig.partitionsConfig.size
  private val partitionPrefix = journalConfig.partitionsConfig.prefix

  override protected def writeJournalRows(xs: Seq[JournalRow]): Future[Unit] =
    attachOrderingPartition(xs).flatMap(_ => super.writeJournalRows(xs))

  private val createdPartitions: AtomicReference[List[Long]] = new AtomicReference[List[Long]](Nil)

  def attachOrderingPartition(xs: Seq[JournalRow])(implicit ec: ExecutionContext): Future[Unit] = {
    import akka.persistence.postgres.db.ExtendedPostgresProfile.api._

    val schema = journalTableCfg.schemaName.map(_ + ".").getOrElse("")

    val estimatedMaxOrdering: Future[Long] = db
      .run(
        sql"""SELECT last_value from #${schema + journalTableCfg.tableName}_#${journalTableCfg.columnNames.ordering}_seq"""
          .as[Long])
      .map(_.head)

    estimatedMaxOrdering
      .map { minOrdering =>
        val maxOrdering = minOrdering + xs.length
        val requiredPartitions = minOrdering / partitionSize to maxOrdering / partitionSize
        val existingPartitions = createdPartitions.get()
        requiredPartitions.toList.filter(!existingPartitions.contains(_))
      }
      .flatMap { partitionsToCreate =>

        if (partitionsToCreate.nonEmpty) {
          val actions = partitionsToCreate.map { partitionNumber =>
            val name = s"${partitionPrefix}_$partitionNumber"
            val minRange = partitionNumber * partitionSize
            val maxRange = minRange + partitionSize
            logger.info(s"Adding missing journal partition for ordering between $minRange and $maxRange...")
            sqlu"""CREATE TABLE IF NOT EXISTS #${schema + name} PARTITION OF #${schema + journalTableCfg.tableName} FOR VALUES FROM (#$minRange) TO (#$maxRange)"""
          }
          db.run(DBIO.sequence(actions))
            .recoverWith {
              case ex: SQLException if ex.getSQLState == DbErrorCodes.PgDuplicateTable =>
                // Partition already created from another session, all good, recovery succeeded
                Future.successful(())
            }
            .map(_ => {
              createdPartitions.updateAndGet(_ ::: partitionsToCreate)
            })
            .map(_ => ())
        } else {
          Future.successful(())
        }
      }
  }
}
