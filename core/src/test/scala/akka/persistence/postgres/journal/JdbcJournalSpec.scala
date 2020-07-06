/*
 * Copyright (C) 2014 - 2019 Dennis Vriend <https://github.com/dnvriend>
 * Copyright (C) 2019 - 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.postgres.journal

import akka.actor.Actor
import akka.persistence.JournalProtocol.{ RecoverySuccess, ReplayMessages, ReplayedMessage }
import akka.persistence.postgres.config._
import akka.persistence.postgres.db.SlickExtension
import akka.persistence.postgres.util.Schema._
import akka.persistence.postgres.util.{ ClasspathResources, DropCreate }
import akka.persistence.journal.JournalSpec
import akka.persistence.{ CapabilityFlag, PersistentImpl }
import akka.testkit.TestProbe
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach }

import scala.concurrent.duration._

abstract class JdbcJournalSpec(config: Config, schemaType: SchemaType)
    extends JournalSpec(config)
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with ScalaFutures
    with ClasspathResources
    with DropCreate {
  override protected def supportsRejectingNonSerializableObjects: CapabilityFlag = true

  implicit val pc: PatienceConfig = PatienceConfig(timeout = 10.seconds)

  implicit lazy val ec = system.dispatcher

  lazy val cfg = system.settings.config.getConfig("postgres-journal")

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

abstract class BasePartitionedJournalSpec(config: String)
    extends JdbcJournalSpec(ConfigFactory.load(config), Partitioned()) {

  "A journal" must {
    "allow to store concurrently events for different persistenceId" in {
      //given
      val pId1 = "persist1"
      val pId2 = "persist2"
      val sender1 = TestProbe()
      val sender2 = TestProbe()
      val receiverProbe = TestProbe()
      //when
      writeMessages(1, 1000, pId1, sender1.ref, writerUuid)
      writeMessages(1, 1000, pId2, sender2.ref, writerUuid)

      //then
      journal ! ReplayMessages(1, Long.MaxValue, Long.MaxValue, pId1, receiverProbe.ref)
      (1 to 1000).foreach { i =>
        receiverProbe.expectMsg(replayedPostgreSQLMessage(i, pId1))
      }
      receiverProbe.expectMsg(RecoverySuccess(highestSequenceNr = 1000L))

      journal ! ReplayMessages(1, Long.MaxValue, Long.MaxValue, pId2, receiverProbe.ref)
      (1 to 1000).foreach { i =>
        receiverProbe.expectMsg(replayedPostgreSQLMessage(i, pId2))
      }
      receiverProbe.expectMsg(RecoverySuccess(highestSequenceNr = 1000L))
    }

    "create new sub-partition for new events" in {
      //given
      val pId = "persist3"
      val sender = TestProbe()
      val receiverProbe = TestProbe()
      //when
      writeMessages(1, 1000, pId, sender.ref, writerUuid)

      // TODO we are assuming that sub-partition will be created for 2000 events, change it when will make parameter for number of events per partition
      writeMessages(1001, 2500, pId, sender.ref, writerUuid)

      //then
      journal ! ReplayMessages(1, Long.MaxValue, Long.MaxValue, pId, receiverProbe.ref)
      (1 to 2500).foreach { i =>
        receiverProbe.expectMsg(replayedPostgreSQLMessage(i, pId))
      }
      receiverProbe.expectMsg(RecoverySuccess(highestSequenceNr = 2500L))
    }
  }

  def replayedPostgreSQLMessage(snr: Long, pid: String, deleted: Boolean = false): ReplayedMessage =
    ReplayedMessage(PersistentImpl(s"a-${snr}", snr, pid, "", deleted, Actor.noSender, writerUuid, 0L))
}

class PartitionedJournalSpec extends BasePartitionedJournalSpec("partitioned-application.conf")
class PartitionedJournalSpecSharedDb
    extends BasePartitionedJournalSpec("partitioned-shared-db-application.conf")
class PartitionedJournalSpecPhysicalDelete
    extends BasePartitionedJournalSpec("partitioned-application-with-hard-delete.conf")

class PlainJournalSpec extends JdbcJournalSpec(ConfigFactory.load("plain-application.conf"), Plain())
class PlainJournalSpecSharedDb
    extends JdbcJournalSpec(ConfigFactory.load("plain-shared-db-application.conf"), Plain())
class PlainJournalSpecPhysicalDelete
    extends JdbcJournalSpec(ConfigFactory.load("plain-application-with-hard-delete.conf"), Plain())
