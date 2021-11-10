/*
 * Copyright (C) 2014 - 2019 Dennis Vriend <https://github.com/dnvriend>
 * Copyright (C) 2019 - 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.postgres.journal

import akka.actor.Actor
import akka.persistence.JournalProtocol.ReplayedMessage
import akka.persistence.journal.JournalSpec
import akka.persistence.postgres.config._
import akka.persistence.postgres.db.SlickExtension
import akka.persistence.postgres.query.ScalaPostgresReadJournalOperations
import akka.persistence.postgres.util.Schema._
import akka.persistence.postgres.util.{ ClasspathResources, DropCreate }
import akka.persistence.query.Sequence
import akka.persistence.{ CapabilityFlag, PersistentImpl }
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

}

trait PartitionedJournalSpecTestCases {
  this: PostgresJournalSpec =>

  "A journal" must {
    "store events concurrently for different persistence ids without creating duplicates or gaps among journal ordering (offset)" in {
      // given
      val numOfSenders = 5
      val batchSize = 1000
      val senders = List.fill(numOfSenders)(TestProbe()).zipWithIndex

      // when
      Future
        .sequence {
          senders.map { case (sender, idx) =>
            Future {
              writeMessages((idx * batchSize) + 1, (idx + 1) * batchSize, s"perId-${idx + 1}", sender.ref, writerUuid)
            }
          }
        }
        .futureValue(Timeout(Span(1, Minute)))

      val journalOps = new ScalaPostgresReadJournalOperations(system)
      var orderings: IndexedSeq[Long] = IndexedSeq.empty

      senders.foreach { case (_, idx) =>
        journalOps.withCurrentEventsByPersistenceId()(s"perId-${idx + 1}") { tp =>
          tp.request(Long.MaxValue)
          val replayedMessages = (1 to batchSize).map { _ =>
            tp.expectNext()
          }
          tp.expectComplete()
          orderings = orderings ++ replayedMessages.map(_.offset).collect { case Sequence(value) =>
            value
          }
        }
      }

      // then
      orderings.size should equal(batchSize * numOfSenders)
      val minOrd = orderings.min
      val maxOrd = orderings.max
      val expectedOrderings = (minOrd to maxOrd).toList

      (orderings.sorted should contain).theSameElementsInOrderAs(expectedOrderings)
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

class PartitionedJournalSpec
    extends PostgresJournalSpec("partitioned-application.conf", Partitioned)
    with PartitionedJournalSpecTestCases
class PartitionedJournalSpecSharedDb
    extends PostgresJournalSpec("partitioned-shared-db-application.conf", Partitioned)
    with PartitionedJournalSpecTestCases
class PartitionedJournalSpecPhysicalDelete
    extends PostgresJournalSpec("partitioned-application-with-hard-delete.conf", Partitioned)
    with PartitionedJournalSpecTestCases

class PlainJournalSpec extends PostgresJournalSpec("plain-application.conf", Plain)
class PlainJournalSpecSharedDb extends PostgresJournalSpec("plain-shared-db-application.conf", Plain)
class PlainJournalSpecPhysicalDelete extends PostgresJournalSpec("plain-application-with-hard-delete.conf", Plain)
