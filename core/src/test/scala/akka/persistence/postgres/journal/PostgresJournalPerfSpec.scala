/*
 * Copyright (C) 2014 - 2019 Dennis Vriend <https://github.com/dnvriend>
 * Copyright (C) 2019 - 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.postgres.journal

import akka.actor.Props
import akka.persistence.CapabilityFlag
import akka.persistence.postgres.config._
import akka.persistence.postgres.util.Schema._
import akka.persistence.postgres.util.{ ClasspathResources, DropCreate }
import akka.persistence.postgres.db.SlickExtension
import akka.persistence.journal.JournalPerfSpec
import akka.persistence.journal.JournalPerfSpec.{ BenchActor, Cmd, ResetCounter }
import akka.testkit.TestProbe
import com.typesafe.config.{ Config, ConfigFactory, ConfigValueFactory }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach }

import scala.concurrent.duration._

abstract class PostgresJournalPerfSpec(config: Config, schemaType: SchemaType)
    extends JournalPerfSpec(config)
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with ScalaFutures
    with ClasspathResources
    with DropCreate {
  override protected def supportsRejectingNonSerializableObjects: CapabilityFlag = true

  implicit lazy val ec = system.dispatcher

  implicit def pc: PatienceConfig = PatienceConfig(timeout = 10.minutes)

  override def eventsCount: Int = 100

  override def awaitDurationMillis: Long = 10.minutes.toMillis

  override def measurementIterations: Int = 1

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

  def actorCount = 100

  private val commands = Vector(1 to eventsCount: _*)

  "A PersistentActor's performance" must {
    s"measure: persist()-ing $eventsCount events for $actorCount actors" in {
      val testProbe = TestProbe()
      val replyAfter = eventsCount
      def createBenchActor(actorNumber: Int) =
        system.actorOf(Props(classOf[BenchActor], s"$pid--$actorNumber", testProbe.ref, replyAfter))
      val actors = 1.to(actorCount).map(createBenchActor)

      measure(d => s"Persist()-ing $eventsCount * $actorCount took ${d.toMillis} ms") {
        for (cmd <- commands; actor <- actors) {
          actor ! Cmd("p", cmd)
        }
        for (_ <- actors) {
          testProbe.expectMsg(awaitDurationMillis.millis, commands.last)
        }
        for (actor <- actors) {
          actor ! ResetCounter
        }
      }
    }
  }

  "A PersistentActor's performance" must {
    s"measure: persistAsync()-ing $eventsCount events for $actorCount actors" in {
      val testProbe = TestProbe()
      val replyAfter = eventsCount
      def createBenchActor(actorNumber: Int) =
        system.actorOf(Props(classOf[BenchActor], s"$pid--$actorNumber", testProbe.ref, replyAfter))
      val actors = 1.to(actorCount).map(createBenchActor)

      measure(d => s"persistAsync()-ing $eventsCount * $actorCount took ${d.toMillis} ms") {
        for (cmd <- commands; actor <- actors) {
          actor ! Cmd("pa", cmd)
        }
        for (_ <- actors) {
          testProbe.expectMsg(awaitDurationMillis.millis, commands.last)
        }
        for (actor <- actors) {
          actor ! ResetCounter
        }
      }
    }
  }
}

class NestedPartitionsJournalPerfSpec extends PostgresJournalPerfSpec(ConfigFactory.load("nested-partitions-application.conf"), NestedPartitions())

class NestedPartitionsJournalPerfSpecSharedDb
    extends PostgresJournalPerfSpec(ConfigFactory.load("nested-partitions-shared-db-application.conf"), NestedPartitions())

class NestedPartitionsJournalPerfSpecPhysicalDelete
  extends PostgresJournalPerfSpec(ConfigFactory.load("nested-partitions-application-with-hard-delete.conf"), NestedPartitions())

class PartitionedJournalPerfSpec extends PostgresJournalPerfSpec(ConfigFactory.load("partitioned-application.conf"), Partitioned())

class PartitionedJournalPerfSpecSharedDb
  extends PostgresJournalPerfSpec(ConfigFactory.load("partitioned-shared-db-application.conf"), Partitioned())

class PartitionedJournalPerfSpecPhysicalDelete
  extends PostgresJournalPerfSpec(ConfigFactory.load("partitioned-application-with-hard-delete.conf"), Partitioned())


class PlainJournalPerfSpec extends PostgresJournalPerfSpec(ConfigFactory.load("plain-application.conf"), Plain())

class PlainJournalPerfSpecSharedDb
  extends PostgresJournalPerfSpec(ConfigFactory.load("plain-shared-db-application.conf"), Plain())

class PlainJournalPerfSpecPhysicalDelete
  extends PostgresJournalPerfSpec(ConfigFactory.load("plain-application-with-hard-delete.conf"), Plain())
