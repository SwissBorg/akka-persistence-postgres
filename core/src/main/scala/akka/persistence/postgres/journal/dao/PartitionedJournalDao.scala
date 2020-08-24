package akka.persistence.postgres.journal.dao

import java.util.concurrent.atomic.AtomicReference

import akka.persistence.postgres.JournalRow
import akka.persistence.postgres.config.JournalConfig
import akka.persistence.postgres.db.DbErrors
import akka.serialization.Serialization
import akka.stream.Materializer
import slick.jdbc.JdbcBackend.Database
import slick.sql.SqlAction

import scala.collection.immutable.{ Nil, Seq }
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

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
      case _ =>
        val actions = for {
          ordered <- attachOrderingValues(xs)
          _ <- attachJournalPartition(ordered)
          _ <- queries.writeJournalRows(ordered).transactionally
        } yield ()
        db.run(actions).recoverWith {
          case ex =>
            logger.error(s"Cannot write journal rows.", ex)
            Future.failed(ex)
        }

    }

  private val createdPartitions: AtomicReference[Set[Long]] = new AtomicReference[Set[Long]](Set.empty)

  def attachOrderingValues(xs: Seq[JournalRow]): DBIOAction[Seq[JournalRow], NoStream, Effect] = {
    for {
      orderingsSeq <- sql"""select nextval('#$seqName') from generate_series(1, #${xs.length}) n""".as[Long]
    } yield {
      xs.sortBy(_.sequenceNumber).zip(orderingsSeq).map {
        case (row, ord) => row.copy(ordering = ord)
      }
    }
  }

  def attachJournalPartition(xs: Seq[JournalRow]): DBIOAction[Unit, NoStream, Effect] = {
    val (minOrd, maxOrd) = {
      val os = xs.map(_.ordering)
      (os.min, os.max)
    }

    val requiredPartitions = minOrd / partitionSize to maxOrd / partitionSize
    val existingPartitions = createdPartitions.get()
    val partitionsToCreate = requiredPartitions.toList.filter(!existingPartitions.contains(_))

    if (partitionsToCreate.nonEmpty) {
      val actions = {
        for (partitionNumber <- partitionsToCreate) yield {
          val name = s"${partitionPrefix}_$partitionNumber"
          val minRange = partitionNumber * partitionSize
          val maxRange = minRange + partitionSize
          val query: SqlAction[Int, NoStream, Effect] = {
            sqlu"""CREATE TABLE IF NOT EXISTS #${schema + name} PARTITION OF #${schema + journalTableCfg.tableName} FOR VALUES FROM (#$minRange) TO (#$maxRange)"""
          }
          query.asTry.flatMap {
            case Failure(exception) =>
              logger.debug(s"Partition for ordering between $minRange and $maxRange already exists")
              DBIO.from(DbErrors.SwallowPartitionAlreadyExistsError.applyOrElse(exception, Future.failed))
            case Success(_) =>
              createdPartitions.updateAndGet(_ + partitionNumber)
              logger.debug(s"Created missing journal partition for ordering between $minRange and $maxRange")
              DBIO.successful(())
          }
        }
      }
      DBIO.sequence(actions).map(_ => ())
    } else {
      DBIO.successful(())
    }
  }
}
