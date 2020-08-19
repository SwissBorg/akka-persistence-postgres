/*
 * Copyright (C) 2014 - 2019 Dennis Vriend <https://github.com/dnvriend>
 * Copyright (C) 2019 - 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.postgres.query

import akka.persistence.postgres.util.Schema.{ NestedPartitions, Partitioned, Plain, SchemaType }

import scala.concurrent.duration._

abstract class AllPersistenceIdsTest(val schemaType: SchemaType) extends QueryTestSpec(schemaType.configName) {
  it should "not terminate the stream when there are not pids" in withActorSystem { implicit system =>
    val journalOps = new ScalaPostgresReadJournalOperations(system)
    journalOps.withPersistenceIds() { tp =>
      tp.request(1)
      tp.expectNoMessage(100.millis)
      tp.cancel()
      tp.expectNoMessage(100.millis)
    }
  }

  it should "find persistenceIds for actors" in withActorSystem { implicit system =>
    val journalOps = new JavaDslPostgresReadJournalOperations(system)
    withTestActors() { (actor1, actor2, actor3) =>
      journalOps.withPersistenceIds() { tp =>
        tp.request(10)
        tp.expectNoMessage(100.millis)

        actor1 ! 1
        tp.expectNext(ExpectNextTimeout, "my-1")
        tp.expectNoMessage(100.millis)

        actor2 ! 1
        tp.expectNext(ExpectNextTimeout, "my-2")
        tp.expectNoMessage(100.millis)

        actor3 ! 1
        tp.expectNext(ExpectNextTimeout, "my-3")
        tp.expectNoMessage(100.millis)

        actor1 ! 1
        tp.expectNoMessage(100.millis)

        actor2 ! 1
        tp.expectNoMessage(100.millis)

        actor3 ! 1
        tp.expectNoMessage(100.millis)

        tp.cancel()
        tp.expectNoMessage(100.millis)
      }
    }
  }
}

class NestedPartitionsScalaAllPersistenceIdsTest extends AllPersistenceIdsTest(NestedPartitions)

class PartitionedScalaAllPersistenceIdsTest extends AllPersistenceIdsTest(Partitioned)

class PlainScalaAllPersistenceIdsTest extends AllPersistenceIdsTest(Plain)
