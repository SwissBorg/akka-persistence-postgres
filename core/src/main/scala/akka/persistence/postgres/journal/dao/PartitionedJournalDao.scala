package akka.persistence.postgres.journal.dao

import akka.NotUsed
import akka.persistence.PersistentRepr
import akka.persistence.postgres.JournalRow
import akka.persistence.postgres.config.JournalConfig
import akka.persistence.postgres.db.DbErrors.{ withHandledIndexErrors, withHandledPartitionErrors }
import akka.serialization.Serialization
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import slick.jdbc.JdbcBackend.Database

import java.util.concurrent.atomic.AtomicReference
import scala.collection.immutable.{ Nil, Seq }
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

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
        db.run(actions).recoverWith { case ex =>
          logger.error(s"Cannot write journal rows.", ex)
          Future.failed(ex)
        }

    }

  private val createdPartitions: AtomicReference[Set[Long]] = new AtomicReference[Set[Long]](Set.empty)

  def attachOrderingValues(xs: Seq[JournalRow]): DBIOAction[Seq[JournalRow], NoStream, Effect] = {
    for {
      orderingsSeq <- sql"""select nextval('#$seqName') from generate_series(1, #${xs.length}) n""".as[Long]
    } yield {
      xs.sortBy(_.sequenceNumber).zip(orderingsSeq).map { case (row, ord) =>
        row.copy(ordering = ord)
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
          val partitionName = s"${schema + name}"
          val indexName = s"${name}_persistence_sequence_idx"
          withHandledPartitionErrors(logger, s"$partitionName (ordering between $minRange and $maxRange)") {
            sqlu"""CREATE TABLE IF NOT EXISTS #$partitionName PARTITION OF #${schema + journalTableCfg.tableName} FOR VALUES FROM (#$minRange) TO (#$maxRange)"""
          }.andThen(withHandledIndexErrors(logger, s"$indexName for partition $partitionName") {
            sqlu"""CREATE UNIQUE INDEX IF NOT EXISTS #$indexName ON #$partitionName USING BTREE (#${journalTableCfg.columnNames.persistenceId}, #${journalTableCfg.columnNames.sequenceNumber});"""
          })
        }
      }
      DBIO
        .sequence(actions)
        .map(_ => createdPartitions.compareAndSet(existingPartitions, existingPartitions ++ partitionsToCreate): Unit)
    } else {
      DBIO.successful(())
    }
  }

  override def messages(
      persistenceId: String,
      fromSequenceNr: Long,
      toSequenceNr: Long,
      max: Long): Source[Try[(PersistentRepr, Long)], NotUsed] = {

    // This behaviour override is only applied here, because it is only useful on the PartitionedJournal strategy.
    val query = if (journalConfig.useJournalMetadata) {
      metadataQueries.minAndMaxOrderingForPersistenceId(persistenceId).result.headOption.flatMap {
        case Some((minOrdering, _)) =>
          // if journal_metadata knows the min ordering of a persistenceId,
          // use it to help the query planner to avoid scanning unnecessary partitions.
          queries
            .messagesMinOrderingBoundedQuery(persistenceId, fromSequenceNr, toSequenceNr, max, minOrdering)
            .result
        case None =>
          // fallback to standard behaviour
          queries.messagesQuery(persistenceId, fromSequenceNr, toSequenceNr, max).result
      }
    } else
      queries.messagesQuery(persistenceId, fromSequenceNr, toSequenceNr, max).result

    Source.fromPublisher(db.stream(query)).via(serializer.deserializeFlow)
  }
}
