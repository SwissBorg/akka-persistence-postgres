package akka.persistence.postgres.journal

import java.util.UUID

import akka.actor.{ Actor, ActorRef, ActorSystem }
import akka.persistence.JournalProtocol._
import akka.persistence.postgres.config._
import akka.persistence.postgres.db.SlickExtension
import akka.persistence.postgres.journal.JournalPartitioningSpec.HugeBatchSmallPartitionConfig
import akka.persistence.postgres.util.Schema._
import akka.persistence.postgres.util.{ ClasspathResources, DropCreate }
import akka.persistence.{ AtomicWrite, Persistence, PersistentImpl, PersistentRepr }
import akka.testkit.TestProbe
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach }

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

object JournalPartitioningSpec {
  val HugeBatchSmallPartitionConfig: Config = ConfigFactory.parseString {
    """postgres-journal {
      |  batchSize = 300
      |  tables.journal.partitions {
      |    size = 200
      |  }
      |}""".stripMargin
  }
}

abstract class JournalPartitioningSpec(schemaType: SchemaType)
    extends AnyWordSpec
    with Matchers
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with ScalaFutures
    with ClasspathResources
    with DropCreate {

  val config: Config = HugeBatchSmallPartitionConfig.withFallback(ConfigFactory.load(schemaType.configName))

  implicit lazy val system: ActorSystem = ActorSystem("JournalPartitioningSpec", config)

  implicit val pc: PatienceConfig = PatienceConfig(timeout = 10.seconds)

  implicit lazy val ec: ExecutionContext = system.dispatcher

  lazy val cfg: Config = system.settings.config.getConfig("postgres-journal")

  lazy val journalConfig = new JournalConfig(cfg)

  lazy val db = SlickExtension(system).database(cfg).database

  def withFreshSchemaAndPlugin(f: (ActorRef, String) => Unit): Unit = {
    import akka.testkit._
    dropCreate(schemaType)
    implicit val system: ActorSystem = ActorSystem("JournalPartitioningSpec", config)
    val journal = Persistence(system).journalFor(null)
    val writerUuid = UUID.randomUUID().toString
    f(journal, writerUuid)
    TestKit.shutdownActorSystem(system, 10.seconds.dilated.min(10.seconds))
  }

  override def afterAll(): Unit = {
    db.close()
    super.afterAll()
  }

  def supportsAtomicPersistAllOfSeveralEvents: Boolean = true

  def writeMessages(fromSnr: Int, toSnr: Int, pid: String, sender: ActorRef, writerUuid: String)(
      journal: ActorRef): Unit = {

    def persistentRepr(sequenceNr: Long) =
      PersistentRepr(
        payload = s"a-$sequenceNr",
        sequenceNr = sequenceNr,
        persistenceId = pid,
        sender = sender,
        writerUuid = writerUuid)

    val messages =
      if (supportsAtomicPersistAllOfSeveralEvents) {
        (fromSnr until toSnr).map { i =>
          if (i == toSnr - 1)
            AtomicWrite(List(persistentRepr(i), persistentRepr(i + 1)))
          else
            AtomicWrite(persistentRepr(i))
        }
      } else {
        (fromSnr to toSnr).map { i =>
          AtomicWrite(persistentRepr(i))
        }
      }

    val probe = TestProbe()

    journal ! WriteMessages(messages, probe.ref, 1)

    probe.expectMsg(WriteMessagesSuccessful)
    (fromSnr to toSnr).foreach { i =>
      probe.expectMsgPF() {
        case WriteMessageSuccess(PersistentImpl(payload, `i`, `pid`, _, _, `sender`, `writerUuid`, _), _) =>
          payload should be(s"a-$i")
      }
    }
  }

  "A journal" must {
    "not give up on creating many partitions when the 1st one already exists" in withFreshSchemaAndPlugin {
      (journal, writerUuid) =>
        //given
        val numOfEvents = journalConfig.daoConfig.batchSize * 4

        val perIds: List[String] = (0 to 10).map(n => s"perId-$n").toList

        //when
        Future.sequence {
          perIds.map(writeBatchOfMessages)
        }.futureValue

        //then
        perIds.foreach(assertRecovery)

        def writeBatchOfMessages(perId: String): Future[Unit] = {
          val sender = TestProbe()
          Future {
            writeMessages(1, numOfEvents, perId, sender.ref, writerUuid)(journal)
          }
        }

        def assertRecovery(perId: String): Unit = {
          val receiverProbe = TestProbe()
          journal ! ReplayMessages(1, Long.MaxValue, Long.MaxValue, perId, receiverProbe.ref)
          (1 to numOfEvents).foreach { i =>
            receiverProbe.expectMsg(replayedPostgresMessage(i, perId, writerUuid))
          }
          receiverProbe.expectMsg(RecoverySuccess(highestSequenceNr = numOfEvents))
        }

    }

  }

  protected def replayedPostgresMessage(
      snr: Long,
      pid: String,
      writerUuid: String,
      deleted: Boolean = false): ReplayedMessage =
    ReplayedMessage(PersistentImpl(s"a-$snr", snr, pid, "", deleted, Actor.noSender, writerUuid, 0L))

}

class NestedPartitionsJournalPartitioningSpec extends JournalPartitioningSpec(NestedPartitions) {
  "A journal" must {
    "allow to store concurrently events for different persistenceId" in withFreshSchemaAndPlugin {
      (journal, writerUuid) =>
        //given
        val pId1 = "persist1"
        val pId2 = "persist2"
        val sender1 = TestProbe()
        val sender2 = TestProbe()
        val receiverProbe = TestProbe()
        //when
        writeMessages(1, 1000, pId1, sender1.ref, writerUuid)(journal)
        writeMessages(1, 1000, pId2, sender2.ref, writerUuid)(journal)

        //then
        journal ! ReplayMessages(1, Long.MaxValue, Long.MaxValue, pId1, receiverProbe.ref)
        (1 to 1000).foreach { i =>
          receiverProbe.expectMsg(replayedPostgresMessage(i, pId1, writerUuid))
        }
        receiverProbe.expectMsg(RecoverySuccess(highestSequenceNr = 1000L))

        journal ! ReplayMessages(1, Long.MaxValue, Long.MaxValue, pId2, receiverProbe.ref)
        (1 to 1000).foreach { i =>
          receiverProbe.expectMsg(replayedPostgresMessage(i, pId2, writerUuid))
        }
        receiverProbe.expectMsg(RecoverySuccess(highestSequenceNr = 1000L))
    }

    "create new sub-partition for new events" in withFreshSchemaAndPlugin { (journal, writerUuid) =>
      //given
      val pId = "persist3"
      val sender = TestProbe()
      val receiverProbe = TestProbe()
      //when
      writeMessages(1, 1000, pId, sender.ref, writerUuid)(journal)

      // Assuming that partition's capacity is 2000 rows.
      writeMessages(1001, 2500, pId, sender.ref, writerUuid)(journal)

      //then
      journal ! ReplayMessages(1, Long.MaxValue, Long.MaxValue, pId, receiverProbe.ref)
      (1 to 2500).foreach { i =>
        receiverProbe.expectMsg(replayedPostgresMessage(i, pId, writerUuid))
      }
      receiverProbe.expectMsg(RecoverySuccess(highestSequenceNr = 2500L))
    }
  }

}

class PartitionedJournalPartitioningSpec extends JournalPartitioningSpec(Partitioned) {
  "A Journal" must {
    "create new partition for new events" in withFreshSchemaAndPlugin { (journal, writerUuid) =>
      //given
      val pId = "persist3"
      val sender = TestProbe()
      val receiverProbe = TestProbe()
      //when
      writeMessages(1, 2000, pId, sender.ref, writerUuid)(journal)

      // Assuming that partition's capacity is 2000 rows.
      writeMessages(2001, 2500, pId, sender.ref, writerUuid)(journal)

      //then
      journal ! ReplayMessages(1, Long.MaxValue, Long.MaxValue, pId, receiverProbe.ref)
      (1 to 2500).foreach { i =>
        receiverProbe.expectMsg(replayedPostgresMessage(i, pId, writerUuid))
      }
      receiverProbe.expectMsg(RecoverySuccess(highestSequenceNr = 2500L))
    }
  }
}
