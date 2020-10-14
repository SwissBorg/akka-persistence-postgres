/*
 * Copyright (C) 2014 - 2019 Dennis Vriend <https://github.com/dnvriend>
 * Copyright (C) 2019 - 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.postgres.query

import java.util.concurrent.atomic.AtomicInteger

import akka.pattern.ask
import akka.persistence.postgres.query.EventAdapterTest._
import akka.persistence.postgres.query.EventsByTagTest._
import akka.persistence.postgres.query.scaladsl.PostgresReadJournal
import akka.persistence.postgres.util.Schema.{ NestedPartitions, Partitioned, Plain, SchemaType }
import akka.persistence.query.{ EventEnvelope, NoOffset, PersistenceQuery, Sequence }
import akka.stream.RestartSettings
import akka.stream.scaladsl.RestartSource
import com.typesafe.config.{ ConfigValue, ConfigValueFactory }

import scala.concurrent.duration._

object EventsByTagFailureTest {
  val maxBufferSize = 20
  val refreshInterval = 500.milliseconds

  val configOverrides: Map[String, ConfigValue] = Map(
    "postgres-read-journal.max-buffer-size" -> ConfigValueFactory.fromAnyRef(maxBufferSize.toString),
    "postgres-read-journal.refresh-interval" -> ConfigValueFactory.fromAnyRef(refreshInterval.toString()))
}

abstract class EventsByTagFailureTest(val schemaType: SchemaType)
    extends QueryTestSpec(schemaType.configName, configOverrides) {
  final val NoMsgTime: FiniteDuration = 100.millis

  it should "fail the returned source on event adapter errors" in withActorSystem { implicit system =>
    val readJournal = PersistenceQuery(system).readJournalFor[PostgresReadJournal](PostgresReadJournal.Identifier)

    withTestActors(replyToMessages = true) { (actor1, actor2, actor3) =>
      (actor1 ? withTags(1, "one", "1", "prime")).futureValue
      (actor2 ? withTags(BrokenEvent("2"), "two", "2", "prime")).futureValue
      (actor3 ? withTags(3, "three", "3", "prime")).futureValue
    }

    eventually {
      readJournal.persistenceIds().take(3).runFold(0L) { (acc, _) => acc + 1 }.futureValue shouldBe 3
    }
    val numOfRestarts = new AtomicInteger(0)
    val events = RestartSource
      .withBackoff(RestartSettings(10.milliseconds, 100.milliseconds, 0.1)) { () =>
        numOfRestarts.incrementAndGet()
        readJournal.eventsByTag("prime", NoOffset)
      }
      .take(3)
      .runFold(List.empty[EventEnvelope]) { _ :+ _ }
      .futureValue

    numOfRestarts.get() shouldBe TestFailingEventAdapter.NumberOfFailures + 1

    events should contain theSameElementsAs List(
      EventEnvelope(Sequence(1), "my-1", 1, 1),
      EventEnvelope(Sequence(2), "my-2", 1, BrokenEvent("2")),
      EventEnvelope(Sequence(3), "my-3", 1, 3))

  }

}

class NestedPartitionsScalaEventsByTagFailureTest extends EventsByTagFailureTest(NestedPartitions)

class PartitionedScalaEventsByTagFailureTest extends EventsByTagFailureTest(Partitioned)

class PlainScalaEventsByTagFailureTest extends EventsByTagFailureTest(Plain)
