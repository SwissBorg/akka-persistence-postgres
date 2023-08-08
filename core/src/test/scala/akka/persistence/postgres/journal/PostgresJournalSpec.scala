/*
 * Copyright (C) 2014 - 2019 Dennis Vriend <https://github.com/dnvriend>
 * Copyright (C) 2019 - 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.postgres.journal

import akka.actor.{ Actor, ActorRef }
import akka.persistence.JournalProtocol.{ ReplayedMessage, WriteMessages, WriteMessagesFailed, WriteMessagesSuccessful }
import akka.persistence.journal.JournalSpec
import akka.persistence.postgres.config._
import akka.persistence.postgres.db.SlickExtension
import akka.persistence.postgres.journal.dao.JournalMetadataTable
import akka.persistence.postgres.query.ScalaPostgresReadJournalOperations
import akka.persistence.postgres.util.Schema._
import akka.persistence.postgres.util.{ ClasspathResources, DropCreate }
import akka.persistence.query.Sequence
import akka.persistence.{ AtomicWrite, CapabilityFlag, PersistentImpl, PersistentRepr }
import akka.testkit.TestProbe
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.concurrent.PatienceConfiguration.Timeout
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.{ Minute, Span }
import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach }

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

abstract class PostgresJournalSpec(config: String, schemaType: SchemaType)
    extends JournalSpec(ConfigFactory.load(config))
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with ScalaFutures
    with ClasspathResources
    with DropCreate {
  override protected def supportsRejectingNonSerializableObjects: CapabilityFlag = true

  implicit val pc: PatienceConfig = PatienceConfig(timeout = 10.seconds)

  implicit lazy val ec: ExecutionContext = system.dispatcher

  lazy val cfg: Config = system.settings.config.getConfig("postgres-journal")

  lazy val journalConfig = new JournalConfig(cfg)

  lazy val db = SlickExtension(system).database(cfg).database

  override def beforeAll(): Unit = {
    dropCreate(schemaType)
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    db.close()
    super.afterAll()
  }

  private def writeSingleMessage(seqNr: Int, pid: String, sender: ActorRef, writerUuid: String) = {
    val msg = AtomicWrite(
      PersistentRepr(
        payload = s"a-$seqNr",
        sequenceNr = seqNr,
        persistenceId = pid,
        sender = sender,
        writerUuid = writerUuid))
    val probe = TestProbe()
    journal ! WriteMessages(List(msg), probe.ref, actorInstanceId)
    probe.expectMsg(WriteMessagesSuccessful)
  }

  "A journal" must {
    "not allow to store events with sequence number lower than what is already stored for the same persistence id" in {
      // given
      val perId = "perId"
      val sender = TestProbe()
      val repeatedSnr = 5

      // when
      writeMessages(1, repeatedSnr + 1, perId, sender.ref, writerUuid)

      // then
      val msg = AtomicWrite(
        PersistentRepr(
          payload = s"a-$repeatedSnr",
          sequenceNr = repeatedSnr,
          persistenceId = perId,
          sender = sender.ref,
          writerUuid = writerUuid))

      val probe = TestProbe()
      journal ! WriteMessages(Seq(msg), probe.ref, actorInstanceId)
      probe.expectMsgType[WriteMessagesFailed]
    }
  }

  "An insert on the journal" must {
    import akka.persistence.postgres.db.ExtendedPostgresProfile.api._

    val metadataTable = JournalMetadataTable(journalConfig.journalMetadataTableConfiguration)
    val UNSET_MIN_ORDERING = -1

    "automatically insert journal metadata" in {
      // given
      val perId = "perId-meta-1"
      val sender = TestProbe()
      val prevMetadataExists = db.run(metadataTable.filter(_.persistenceId === perId).exists.result).futureValue

      // when
      writeSingleMessage(1, perId, sender.ref, writerUuid)

      // then
      val newMetadataExists = db.run(metadataTable.filter(_.persistenceId === perId).exists.result).futureValue
      val maxOrdering =
        db.run(metadataTable.filter(_.persistenceId === perId).map(_.maxOrdering).result.head).futureValue
      val minOrdering =
        db.run(metadataTable.filter(_.persistenceId === perId).map(_.minOrdering).result.head).futureValue

      prevMetadataExists shouldBe false
      newMetadataExists shouldBe true
      // when its the first event the insert should take the ordering value and set that on the min and max_ordering columns
      maxOrdering shouldBe minOrdering
      minOrdering > 0 shouldBe true
    }

    "upsert only max_sequence_number and max_ordering if metadata already exists" in {
      // given
      val perId = "perId-meta-2"
      val sender = TestProbe()
      writeSingleMessage(1, perId, sender.ref, writerUuid)
      val prevMaxSeqNr =
        db.run(metadataTable.filter(_.persistenceId === perId).map(_.maxSequenceNumber).result.head).futureValue
      val prevMaxOrdering =
        db.run(metadataTable.filter(_.persistenceId === perId).map(_.maxOrdering).result.head).futureValue
      val prevMinOrdering =
        db.run(metadataTable.filter(_.persistenceId === perId).map(_.minOrdering).result.head).futureValue

      // when
      writeSingleMessage(2, perId, sender.ref, writerUuid)

      // then
      val newMaxSeqNr =
        db.run(metadataTable.filter(_.persistenceId === perId).map(_.maxSequenceNumber).result.head).futureValue
      val newMaxOrdering =
        db.run(metadataTable.filter(_.persistenceId === perId).map(_.maxOrdering).result.head).futureValue
      val newMinOrdering =
        db.run(metadataTable.filter(_.persistenceId === perId).map(_.minOrdering).result.head).futureValue

      newMaxSeqNr shouldBe prevMaxSeqNr + 1
      newMaxOrdering shouldBe prevMaxOrdering + 1
      newMinOrdering shouldBe prevMinOrdering
      newMaxOrdering > 0 shouldBe true
    }

    "set min_ordering to UNSET_MIN_ORDERING when no metadata entry exists but the event being inserted is not the first one for the persistenceId (sequence_number > 1)" in {
      // given
      val perId = "perId-meta-3"
      val sender = TestProbe()
      writeSingleMessage(1, perId, sender.ref, writerUuid)
      val prevMaxSeqNr =
        db.run(metadataTable.filter(_.persistenceId === perId).map(_.maxSequenceNumber).result.head).futureValue
      val prevMaxOrdering =
        db.run(metadataTable.filter(_.persistenceId === perId).map(_.maxOrdering).result.head).futureValue

      // when
      // simulate case where metadata does not exist, but persistenceId already has events
      db.run(metadataTable.filter(_.persistenceId === perId).delete).futureValue
      // write new event of same persistenceId
      writeSingleMessage(2, perId, sender.ref, writerUuid)

      // then
      val newMaxSeqNr =
        db.run(metadataTable.filter(_.persistenceId === perId).map(_.maxSequenceNumber).result.head).futureValue
      val newMaxOrdering =
        db.run(metadataTable.filter(_.persistenceId === perId).map(_.maxOrdering).result.head).futureValue
      val newMinOrdering =
        db.run(metadataTable.filter(_.persistenceId === perId).map(_.minOrdering).result.head).futureValue

      newMaxSeqNr shouldBe prevMaxSeqNr + 1
      newMaxOrdering shouldBe prevMaxOrdering + 1
      newMinOrdering shouldBe UNSET_MIN_ORDERING
    }
  }
}

trait PartitionedJournalSpecTestCases {
  this: PostgresJournalSpec =>

  "A journal" must {
    "store events concurrently without any gaps or duplicates among ordering (offset) values" in {
      // given
      val perId = "perId-1"
      val numOfSenders = 5
      val batchSize = 1000
      val senders = List.fill(numOfSenders)(TestProbe()).zipWithIndex

      // when
      Future
        .sequence {
          senders.map { case (sender, idx) =>
            Future {
              writeMessages((idx * batchSize) + 1, (idx + 1) * batchSize, perId, sender.ref, writerUuid)
            }
          }
        }
        .futureValue(Timeout(Span(1, Minute)))

      // then
      val journalOps = new ScalaPostgresReadJournalOperations(system)
      journalOps.withCurrentEventsByPersistenceId()(perId) { tp =>
        tp.request(Long.MaxValue)
        val replayedMessages = (1 to batchSize * numOfSenders).map { _ =>
          tp.expectNext()
        }
        tp.expectComplete()
        val orderings = replayedMessages.map(_.offset).collect { case Sequence(value) =>
          value
        }
        orderings.size should equal(batchSize * numOfSenders)
        val minOrd = orderings.min
        val maxOrd = orderings.max
        val expectedOrderings = (minOrd to maxOrd).toList

        (orderings.sorted should contain).theSameElementsInOrderAs(expectedOrderings)
      }
    }
  }

  def replayedPostgresMessage(snr: Long, pid: String, deleted: Boolean = false): ReplayedMessage =
    ReplayedMessage(PersistentImpl(s"a-$snr", snr, pid, "", deleted, Actor.noSender, writerUuid, 0L, None))
}

class NestedPartitionsJournalSpec extends PostgresJournalSpec("nested-partitions-application.conf", NestedPartitions)
class NestedPartitionsJournalSpecSharedDb
    extends PostgresJournalSpec("nested-partitions-shared-db-application.conf", NestedPartitions)
class NestedPartitionsJournalSpecPhysicalDelete
    extends PostgresJournalSpec("nested-partitions-application-with-hard-delete.conf", NestedPartitions)

class NestedPartitionsJournalSpecUseJournalMetadata
    extends PostgresJournalSpec("nested-partitions-application-with-use-journal-metadata.conf", NestedPartitions)

class PartitionedJournalSpec
    extends PostgresJournalSpec("partitioned-application.conf", Partitioned)
    with PartitionedJournalSpecTestCases
class PartitionedJournalSpecSharedDb
    extends PostgresJournalSpec("partitioned-shared-db-application.conf", Partitioned)
    with PartitionedJournalSpecTestCases
class PartitionedJournalSpecPhysicalDelete
    extends PostgresJournalSpec("partitioned-application-with-hard-delete.conf", Partitioned)
    with PartitionedJournalSpecTestCases

class PartitionedJournalSpecUseJournalMetadata
    extends PostgresJournalSpec("partitioned-application-with-use-journal-metadata.conf", Partitioned)
    with PartitionedJournalSpecTestCases

class PlainJournalSpec extends PostgresJournalSpec("plain-application.conf", Plain)
class PlainJournalSpecSharedDb extends PostgresJournalSpec("plain-shared-db-application.conf", Plain)
class PlainJournalSpecPhysicalDelete extends PostgresJournalSpec("plain-application-with-hard-delete.conf", Plain)
class PlainJournalSpecUseJournalMetadata
    extends PostgresJournalSpec("plain-application-with-use-journal-metadata.conf", Plain)
