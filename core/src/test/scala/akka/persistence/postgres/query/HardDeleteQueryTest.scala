/*
 * Copyright (C) 2014 - 2019 Dennis Vriend <https://github.com/dnvriend>
 * Copyright (C) 2019 - 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.postgres.query

import akka.pattern._
import akka.persistence.postgres.util.Schema.{ NestedPartitions, Partitioned, Plain, SchemaType }
import akka.persistence.query.NoOffset
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

abstract class HardDeleteQueryTest(val schemaType: SchemaType)
    extends QueryTestSpec(s"${schemaType.resourceNamePrefix}-application-with-hard-delete.conf")
    with Matchers {
  implicit val askTimeout: FiniteDuration = 500.millis

  it should "not return deleted events when using CurrentEventsByTag" in withActorSystem { implicit system =>
    val journalOps = new ScalaPostgresReadJournalOperations(system)
    withTestActors(replyToMessages = true) { (actor1, _, _) =>
      (actor1 ? withTags(1, "number")).futureValue
      (actor1 ? withTags(2, "number")).futureValue
      (actor1 ? withTags(3, "number")).futureValue

      // delete all three events and wait for confirmations
      (actor1 ? DeleteCmd(1)).futureValue shouldBe "deleted-1"
      (actor1 ? DeleteCmd(2)).futureValue shouldBe "deleted-2"
      (actor1 ? DeleteCmd(3)).futureValue shouldBe "deleted-3"

      // check that nothing gets delivered
      journalOps.withCurrentEventsByTag()("number", NoOffset) { tp =>
        tp.request(Int.MaxValue)
        tp.expectComplete()
      }
    }
  }

  it should "not return deleted events when using EventsByTag" in withActorSystem { implicit system =>
    val journalOps = new ScalaPostgresReadJournalOperations(system)
    withTestActors(replyToMessages = true) { (actor1, _, _) =>
      (actor1 ? withTags(1, "number")).futureValue
      (actor1 ? withTags(2, "number")).futureValue
      (actor1 ? withTags(3, "number")).futureValue

      // delete all three events and wait for confirmations
      (actor1 ? DeleteCmd(1)).futureValue shouldBe "deleted-1"
      (actor1 ? DeleteCmd(2)).futureValue shouldBe "deleted-2"
      (actor1 ? DeleteCmd(3)).futureValue shouldBe "deleted-3"

      // check that nothing gets delivered
      journalOps.withEventsByTag()("number", NoOffset) { tp =>
        tp.request(Int.MaxValue)
        tp.cancel()
      }
    }
  }

  it should "not return deleted events when using CurrentEventsByPersistenceId" in withActorSystem { implicit system =>
    val journalOps = new ScalaPostgresReadJournalOperations(system)
    withTestActors(replyToMessages = true) { (actor1, _, _) =>
      (actor1 ? withTags(1, "number")).futureValue
      (actor1 ? withTags(2, "number")).futureValue
      (actor1 ? withTags(3, "number")).futureValue

      // delete all three events and wait for confirmations
      (actor1 ? DeleteCmd(1)).futureValue shouldBe "deleted-1"
      (actor1 ? DeleteCmd(2)).futureValue shouldBe "deleted-2"
      (actor1 ? DeleteCmd(3)).futureValue shouldBe "deleted-3"

      // check that nothing gets delivered
      journalOps.withCurrentEventsByPersistenceId()("my-1") { tp =>
        tp.request(Int.MaxValue)
        tp.expectComplete()
      }
    }
  }

  it should "not return deleted events when using EventsByPersistenceId" in withActorSystem { implicit system =>
    val journalOps = new ScalaPostgresReadJournalOperations(system)
    withTestActors(replyToMessages = true) { (actor1, _, _) =>
      (actor1 ? withTags(1, "number")).futureValue
      (actor1 ? withTags(2, "number")).futureValue
      (actor1 ? withTags(3, "number")).futureValue

      // delete all three events and wait for confirmations
      (actor1 ? DeleteCmd(1)).futureValue shouldBe "deleted-1"
      (actor1 ? DeleteCmd(2)).futureValue shouldBe "deleted-2"
      (actor1 ? DeleteCmd(3)).futureValue shouldBe "deleted-3"

      // check that nothing gets delivered
      journalOps.withEventsByPersistenceId()("my-1") { tp =>
        tp.request(Int.MaxValue)
        tp.cancel()
      }
    }
  }
}

class NestedPartitionsHardDeleteQueryTest extends HardDeleteQueryTest(NestedPartitions)

class PartitionedHardDeleteQueryTest extends HardDeleteQueryTest(Partitioned)

class PlainHardDeleteQueryTest extends HardDeleteQueryTest(Plain)
