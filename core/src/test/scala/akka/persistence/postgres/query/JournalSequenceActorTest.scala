/*
 * Copyright (C) 2014 - 2019 Dennis Vriend <https://github.com/dnvriend>
 * Copyright (C) 2019 - 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.postgres.query

import java.util.concurrent.atomic.AtomicLong

import akka.actor.{ ActorRef, ActorSystem }
import akka.pattern.ask
import akka.persistence.postgres.config.JournalSequenceRetrievalConfig
import akka.persistence.postgres.db.ExtendedPostgresProfile
import akka.persistence.postgres.query.JournalSequenceActor.{ GetMaxOrderingId, MaxOrderingId }
import akka.persistence.postgres.query.dao.{ ByteArrayReadJournalDao, TestProbeReadJournalDao }
import akka.persistence.postgres.tag.{ CachedTagIdResolver, SimpleTagDao }
import akka.persistence.postgres.util.Schema.{ NestedPartitions, Partitioned, Plain, SchemaType }
import akka.persistence.postgres.{ JournalRow, SharedActorSystemTestSpec }
import akka.serialization.SerializationExtension
import akka.stream.scaladsl.{ Sink, Source }
import akka.stream.{ Materializer, SystemMaterializer }
import akka.testkit.TestProbe
import org.scalatest.time.Span
import org.slf4j.LoggerFactory
import slick.jdbc.{ JdbcBackend, JdbcCapabilities }

import scala.concurrent.Future
import scala.concurrent.duration._

abstract class JournalSequenceActorTest(val schemaType: SchemaType) extends QueryTestSpec(schemaType.configName) {
  private val log = LoggerFactory.getLogger(classOf[JournalSequenceActorTest])

  private val journalSequenceActorConfig = readJournalConfig.journalSequenceRetrievalConfiguration
  private val journalTable = schemaType.table(journalConfig.journalTableConfiguration)

  import akka.persistence.postgres.db.ExtendedPostgresProfile.api._

  implicit val askTimeout: FiniteDuration = 50.millis

  private val orderingSeq = new AtomicLong(0L)
  def generateId: Long = orderingSeq.incrementAndGet()

  behavior.of("JournalSequenceActor")

  it should "recover normally" in {
    withActorSystem { implicit system: ActorSystem =>
      withDatabase { db =>
        val numberOfRows = 15000
        val rows =
          for (i <- 1 to numberOfRows) yield JournalRow(generateId, deleted = false, "id", i, Array(0.toByte), Nil)
        db.run(journalTable ++= rows).futureValue
        withJournalSequenceActor(db, maxTries = 100) { actor =>
          eventually {
            actor.ask(GetMaxOrderingId).mapTo[MaxOrderingId].futureValue shouldBe MaxOrderingId(numberOfRows)
          }
        }
      }
    }
  }

  private def canForceInsert: Boolean = ExtendedPostgresProfile.capabilities.contains(JdbcCapabilities.forceInsert)

  if (canForceInsert) {
    it should s"recover one hundred thousand events quickly if no ids are missing" in {
      withActorSystem { implicit system: ActorSystem =>
        withDatabase { db =>
          implicit val materializer: Materializer = SystemMaterializer(system).materializer
          val elements = 100000
          Source
            .fromIterator(() => (1 to elements).iterator)
            .map(id => JournalRow(id, deleted = false, "id", id, Array(0.toByte), Nil))
            .grouped(10000)
            .mapAsync(4) { rows =>
              db.run(journalTable.forceInsertAll(rows))
            }
            .runWith(Sink.ignore)
            .futureValue

          val startTime = System.currentTimeMillis()
          withJournalSequenceActor(db, maxTries = 100) { actor =>
            val patienceConfig = PatienceConfig(10.seconds, Span(200, org.scalatest.time.Millis))
            eventually {
              val currentMax = actor.ask(GetMaxOrderingId).mapTo[MaxOrderingId].futureValue.maxOrdering
              currentMax shouldBe elements
            }(patienceConfig, implicitly, implicitly)
          }
          val timeTaken = System.currentTimeMillis() - startTime
          log.info(s"Recovered all events in $timeTaken ms")
        }
      }
    }
  }

  if (canForceInsert) {
    it should "recover after the specified max number if tries if the first event has a very high sequence number and lots of large gaps exist" in {
      withActorSystem { implicit system: ActorSystem =>
        withDatabase { db =>
          implicit val materializer: Materializer = SystemMaterializer(system).materializer
          val numElements = 1000
          val gapSize = 10000
          val firstElement = 100000000
          val lastElement = firstElement + (numElements * gapSize)
          Source
            .fromIterator(() => (firstElement to lastElement by gapSize).iterator)
            .map(id => JournalRow(id, deleted = false, "id", id, Array(0.toByte), Nil))
            .grouped(10000)
            .mapAsync(4) { rows =>
              db.run(journalTable.forceInsertAll(rows))
            }
            .runWith(Sink.ignore)
            .futureValue

          withJournalSequenceActor(db, maxTries = 2) { actor =>
            // Should normally recover after `maxTries` seconds
            val patienceConfig = PatienceConfig(10.seconds, Span(200, org.scalatest.time.Millis))
            eventually {
              val currentMax = actor.ask(GetMaxOrderingId).mapTo[MaxOrderingId].futureValue.maxOrdering
              currentMax shouldBe lastElement
            }(patienceConfig, implicitly, implicitly)
          }
        }
      }
    }
  }

  if (canForceInsert) {
    it should s"assume that the max ordering id in the database on startup is the max after (queryDelay * maxTries)" in {
      withActorSystem { implicit system: ActorSystem =>
        withDatabase { db =>
          implicit val materializer: Materializer = SystemMaterializer(system).materializer
          val maxElement = 100000
          // only even numbers, odd numbers are missing
          val idSeq = 2 to maxElement by 2
          Source
            .fromIterator(() => idSeq.iterator)
            .map(id => JournalRow(id, deleted = false, "id", id, Array(0.toByte), Nil))
            .grouped(10000)
            .mapAsync(4) { rows =>
              db.run(journalTable.forceInsertAll(rows))
            }
            .runWith(Sink.ignore)
            .futureValue

          val highestValue = maxElement

          withJournalSequenceActor(db, maxTries = 2) { actor =>
            // The actor should assume the max after 2 seconds
            val patienceConfig = PatienceConfig(3.seconds)
            eventually {
              val currentMax = actor.ask(GetMaxOrderingId).mapTo[MaxOrderingId].futureValue.maxOrdering
              currentMax shouldBe highestValue
            }(patienceConfig, implicitly, implicitly)
          }
        }
      }
    }
  }

  /**
   * @param maxTries The number of tries before events are assumed missing
   *                 (since the actor queries every second by default,
   *                 this is effectively the number of seconds after which events are assumed missing)
   */
  def withJournalSequenceActor(db: JdbcBackend.Database, maxTries: Int)(f: ActorRef => Unit)(
      implicit system: ActorSystem): Unit = {
    import system.dispatcher
    implicit val mat: Materializer = SystemMaterializer(system).materializer
    val readJournalDao =
      new ByteArrayReadJournalDao(
        db,
        readJournalConfig,
        SerializationExtension(system),
        new CachedTagIdResolver(
          new SimpleTagDao(db, readJournalConfig.tagsTableConfiguration),
          readJournalConfig.tagsConfig))
    val actor =
      system.actorOf(JournalSequenceActor.props(readJournalDao, journalSequenceActorConfig.copy(maxTries = maxTries)))
    try f(actor)
    finally system.stop(actor)
  }
}

class MockDaoJournalSequenceActorTest extends SharedActorSystemTestSpec {
  def fetchMaxOrderingId(journalSequenceActor: ActorRef): Future[Long] = {
    journalSequenceActor.ask(GetMaxOrderingId)(20.millis).mapTo[MaxOrderingId].map(_.maxOrdering)
  }

  it should "re-query with delay only when events are missing." in {
    val batchSize = 100
    val maxTries = 5
    val queryDelay = 200.millis

    val almostQueryDelay = queryDelay - 50.millis
    val almostImmediately = 50.millis
    withTestProbeJournalSequenceActor(batchSize, maxTries, queryDelay) { (daoProbe, _) =>
      daoProbe.expectMsg(almostImmediately, TestProbeReadJournalDao.JournalSequence(0, batchSize))
      val firstBatch = (1L to 40L) ++ (51L to 110L)
      daoProbe.reply(firstBatch)
      withClue(s"when events are missing, the actor should wait for $queryDelay before querying again") {
        daoProbe.expectNoMessage(almostQueryDelay)
        daoProbe.expectMsg(almostQueryDelay, TestProbeReadJournalDao.JournalSequence(40, batchSize))
      }
      // number 41 is still missing after this batch
      val secondBatch = 42L to 110L
      daoProbe.reply(secondBatch)
      withClue(s"when events are missing, the actor should wait for $queryDelay before querying again") {
        daoProbe.expectNoMessage(almostQueryDelay)
        daoProbe.expectMsg(almostQueryDelay, TestProbeReadJournalDao.JournalSequence(40, batchSize))
      }
      val thirdBatch = 41L to 110L
      daoProbe.reply(thirdBatch)
      withClue(
        s"when no more events are missing, but less that batchSize elemens have been received, " +
        s"the actor should wait for $queryDelay before querying again") {
        daoProbe.expectNoMessage(almostQueryDelay)
        daoProbe.expectMsg(almostQueryDelay, TestProbeReadJournalDao.JournalSequence(110, batchSize))
      }

      val fourthBatch = 111L to 210L
      daoProbe.reply(fourthBatch)
      withClue(
        "When no more events are missing and the number of events received is equal to batchSize, " +
        "the actor should query again immediately") {
        daoProbe.expectMsg(almostImmediately, TestProbeReadJournalDao.JournalSequence(210, batchSize))
      }

      // Reply to prevent a dead letter warning on the timeout
      daoProbe.reply(Seq.empty)
      daoProbe.expectNoMessage(almostImmediately)
    }
  }

  it should "Assume an element missing after the configured amount of maxTries" in {
    val batchSize = 100
    val maxTries = 5
    val queryDelay = 150.millis

    val slightlyMoreThanQueryDelay = queryDelay + 50.millis
    val almostImmediately = 20.millis

    val allIds = (1L to 40L) ++ (43L to 200L)

    withTestProbeJournalSequenceActor(batchSize, maxTries, queryDelay) { (daoProbe, actor) =>
      daoProbe.expectMsg(almostImmediately, TestProbeReadJournalDao.JournalSequence(0, batchSize))
      daoProbe.reply(allIds.take(100))

      val idsLargerThan40 = allIds.dropWhile(_ <= 40)
      val retryResponse = idsLargerThan40.take(100)
      for (i <- 1 to maxTries) withClue(s"should retry $maxTries times (attempt $i)") {
        daoProbe.expectMsg(slightlyMoreThanQueryDelay, TestProbeReadJournalDao.JournalSequence(40, batchSize))
        daoProbe.reply(retryResponse)
      }

      // sanity check
      retryResponse.last shouldBe 142
      withClue(
        "The elements 41 and 42 should be assumed missing, " +
        "the actor should query again immediately since a full batch has been received") {
        daoProbe.expectMsg(almostImmediately, TestProbeReadJournalDao.JournalSequence(142, batchSize))
        fetchMaxOrderingId(actor).futureValue shouldBe 142
      }

      // Reply to prevent a dead letter warning on the timeout
      daoProbe.reply(Seq.empty)
      daoProbe.expectNoMessage(almostImmediately)
    }
  }

  def withTestProbeJournalSequenceActor(batchSize: Int, maxTries: Int, queryDelay: FiniteDuration)(
      f: (TestProbe, ActorRef) => Unit)(implicit system: ActorSystem): Unit = {
    val testProbe = TestProbe()
    val config = JournalSequenceRetrievalConfig(
      batchSize = batchSize,
      maxTries = maxTries,
      queryDelay = queryDelay,
      maxBackoffQueryDelay = 4.seconds,
      askTimeout = 100.millis)
    val mockDao = new TestProbeReadJournalDao(testProbe)
    val actor = system.actorOf(JournalSequenceActor.props(mockDao, config))
    try f(testProbe, actor)
    finally system.stop(actor)
  }
}

class NestedPartitionsJournalSequenceActorTest extends JournalSequenceActorTest(NestedPartitions) {
  override def beforeEach(): Unit = {
    super.beforeEach()
    import akka.persistence.postgres.db.ExtendedPostgresProfile.api._
    withActorSystem { implicit system: ActorSystem =>
      withDatabase { db =>
        db.run(sqlu"""
              CREATE TABLE IF NOT EXISTS j_id PARTITION OF journal FOR VALUES IN ('id') PARTITION BY RANGE (sequence_number);
              CREATE TABLE IF NOT EXISTS j_id_1 PARTITION OF j_id FOR VALUES FROM (0) TO (1000000000);""")
      }
    }
  }
}

class PartitionedJournalSequenceActorTest extends JournalSequenceActorTest(Partitioned) {
  override def beforeEach(): Unit = {
    super.beforeEach()
    import akka.persistence.postgres.db.ExtendedPostgresProfile.api._
    withActorSystem { implicit system: ActorSystem =>
      withDatabase { db =>
        db.run(sqlu"""CREATE TABLE IF NOT EXISTS j_1 PARTITION OF journal FOR VALUES FROM (0) TO (1000000000);""")
      }
    }
  }
}

class PlainJournalSequenceActorTest extends JournalSequenceActorTest(Plain)
