/*
 * Copyright (C) 2014 - 2019 Dennis Vriend <https://github.com/dnvriend>
 * Copyright (C) 2019 - 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.postgres.query

abstract class CurrentPersistenceIdsTest(config: String) extends QueryTestSpec(config) {
  it should "not find any persistenceIds for empty journal" in withActorSystem { implicit system =>
    val journalOps = new ScalaJdbcReadJournalOperations(system)
    journalOps.withCurrentPersistenceIds() { tp =>
      tp.request(1)
      tp.expectComplete()
    }
  }

  it should "find persistenceIds for actors" in withActorSystem { implicit system =>
    val journalOps = new JavaDslJdbcReadJournalOperations(system)
    withTestActors() { (actor1, actor2, actor3) =>
      actor1 ! 1
      actor2 ! 1
      actor3 ! 1

      eventually {
        journalOps.withCurrentPersistenceIds() { tp =>
          tp.request(3)
          tp.expectNextUnordered("my-1", "my-2", "my-3")
          tp.expectComplete()
        }
      }
    }
  }
}

// Note: these tests use the shared-db configs, the test for all persistence ids use the regular db config

class PartitionedScalaCurrentPersistenceIdsTest
    extends CurrentPersistenceIdsTest("partitioned-shared-db-application.conf")
    with PartitionedDbCleaner

class PlainScalaCurrentPersistenceIdsTest
    extends CurrentPersistenceIdsTest("plain-shared-db-application.conf")
    with PlainDbCleaner
