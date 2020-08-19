package akka.persistence.postgres.journal.dao

import java.sql.SQLException
import java.util.concurrent.atomic.AtomicReference

import akka.persistence.postgres.JournalRow
import akka.persistence.postgres.config.JournalConfig
import akka.persistence.postgres.db.DbErrorCodes
import akka.serialization.Serialization
import akka.stream.Materializer
import slick.jdbc.JdbcBackend.Database

import scala.collection.immutable.{List, Nil, Seq}
import scala.concurrent.{ExecutionContext, Future}

class PartitionedJournalDao(db: Database, journalConfig: JournalConfig, serialization: Serialization)(
    implicit ec: ExecutionContext,
    mat: Materializer)
    extends FlatJournalDao(db, journalConfig, serialization) {
  override val queries = new JournalQueries(PartitionedJournalTable(journalConfig.journalTableConfiguration))
  private val journalTableCfg = journalConfig.journalTableConfiguration
  private val partitionSize = journalConfig.partitionsConfig.size
  private val partitionPrefix = journalConfig.partitionsConfig.prefix
  private val schema = journalTableCfg.schemaName.map(_ + ".").getOrElse("")
  private val seqName = s"$schema${journalTableCfg.tableName}_${journalTableCfg.columnNames.ordering}_seq"

  import akka.persistence.postgres.db.ExtendedPostgresProfile.api._

  override protected def writeJournalRows(xs: Seq[JournalRow]): Future[Unit] =
  xs match {
    case Nil => Future.successful(())
    case _ =>for {
      ordered <- attachOrderingValues(xs)
      _ <- attachJournalPartition(ordered)
      res <- super.writeJournalRows(ordered)
    } yield res
  }

  private val createdPartitions: AtomicReference[List[Long]] = new AtomicReference[List[Long]](Nil)

  def attachOrderingValues(xs: Seq[JournalRow]): Future[Seq[JournalRow]] = {
    for {
      orderingsSeq <- db.run(sql"""select nextval('#$seqName') from generate_series(1, #${xs.length}) n""".as[Long])
    } yield {
      xs.sortBy(_.sequenceNumber).zip(orderingsSeq).map {
        case (row, ord) => row.copy(ordering = ord)
      }
    }
  }

  def attachJournalPartition(xs: Seq[JournalRow]): Future[Unit] = {
    val (minOrd, maxOrd) = {
      val os = xs.map(_.ordering)
      (os.min, os.max)
    }

    val requiredPartitions = minOrd / partitionSize to maxOrd / partitionSize
    val existingPartitions = createdPartitions.get()
    val partitionsToCreate = requiredPartitions.toList.filter(!existingPartitions.contains(_))

    if (partitionsToCreate.nonEmpty) {
      val actions = DBIO.sequence {
        for (partitionNumber <- partitionsToCreate) yield {
          val name = s"${partitionPrefix}_$partitionNumber"
          val minRange = partitionNumber * partitionSize
          val maxRange = minRange + partitionSize
          logger.info(s"Adding missing journal partition for ordering between $minRange and $maxRange...")
          sqlu"""CREATE TABLE IF NOT EXISTS #${schema + name} PARTITION OF #${schema + journalTableCfg.tableName} FOR VALUES FROM (#$minRange) TO (#$maxRange)"""
        }
      }
      db.run(actions)
        .recoverWith {
          case ex: SQLException if ex.getSQLState == DbErrorCodes.PgDuplicateTable =>
            // Partition already created from another session, all good, recovery succeeded
            Future.successful(())
        }
        .map(_ => {
          createdPartitions.updateAndGet(_ ::: partitionsToCreate)
        })
    } else {
      Future.successful(())
    }
  }
}
