# Akka Persistence Postgres

The Akka Persistence Postgres plugin allows for using Postgres database as backend for [Akka Persistence](https://doc.akka.io/docs/akka/current/persistence.html) and [Akka Persistence Query](https://doc.akka.io/docs/akka/current/persistence-query.html).

akka-persistence-postgres writes journal and snapshot entries to a configured PostgreSQL store. It implements the full akka-persistence-query API and is therefore very useful for implementing DDD-style application models using Akka and Scala for creating reactive applications.

## Module info

```sbt
libraryDependencies += ??? // TBD
```

## Contribution policy

Contributions via GitHub pull requests are gladly accepted from their original author. Along with any pull requests, please state that the contribution is your original work and that you license the work to the project under the project's open source license. Whether or not you state this explicitly, by submitting any copyrighted material via pull request, email, or other means you agree to license the material under the project's open source license and warrant that you have the legal authority to do so.

## License

This source code is made available under the [Apache 2.0 License](https://www.apache.org/licenses/LICENSE-2.0).

## Configuration

The plugin relies on [Slick-pg](https://github.com/tminglei/slick-pg) to do create the SQL dialect for the database in use, therefore the following must be configured in `application.conf`

Configure `akka-persistence`:

- instruct akka persistence to use the `postgres-journal` plugin,
- instruct akka persistence to use the `postgres-snapshot-store` plugin,

Configure `slick.db`:
 
- setup [connection pool](https://scala-slick.org/doc/3.3.0/database.html#postgresql)

## Database Schema

Depending on the journal variant, choose the appropriate schema:

- [Plain (flat) Journal]({{ site.repo }}/core/src/test/resources/schema/postgres/plain-schema.sql)
- [Journal with Nested Partitions]({{ site.repo }}/core/src/test/resources/schema/postgres/partitioned-schema.sql)

## Reference Configuration

akka-persistence-postgres provides the defaults as part of the [reference.conf]({{ site.repo }}/core/src/main/resources/reference.conf). This file documents all the values which can be configured.

There are several possible ways to configure loading your database connections. Options will be explained below.

### One database connection pool per journal type

There is the possibility to create a separate database connection pool per journal-type (one pool for the write-journal,
one pool for the snapshot-journal, and one pool for the read-journal). This is the default and [the example
configuration]({{ site.repo }}/src/test/resources/postgres-application.conf) shows how this is configured.

### Sharing the database connection pool between the journals

In order to create only one connection pool which is shared between all journals [this configuration]({{ site.repo }}/src/test/resources/postgres-shared-db-application.conf) can be used.

### Customized loading of the db connection

It is also possible to load a custom database connection. 
In order to do so a custom implementation of [SlickDatabaseProvider]({{ site.repo }}/core/src/main/scala/akka/persistence/postgres/db/SlickExtension.scala#L46-L54)
needs to be created. The method that need to be implemented supply the Slick `Database` to the journals.

To enable your custom `SlickDatabaseProvider`, the fully qualified class name of the `SlickDatabaseProvider`
needs to be configured in the application.conf. In addition, you might want to consider whether you want
the database to be closed automatically:

```hocon
akka-persistence-postgres {
  database-provider-fqcn = "com.mypackage.CustomSlickDatabaseProvider"
}
postgres-journal {
  use-shared-db = "enabled" // setting this to any non-empty string prevents the journal from closing the database on shutdown
}
postgres-snapshot-store {
  use-shared-db = "enabled" // setting this to any non-empty string prevents the snapshot-journal from closing the database on shutdown
}
```

### DataSource lookup by JNDI name

The plugin uses `Slick` as the database access library. Slick [supports jndi](https://scala-slick.org/doc/3.3.0/database.html#using-a-jndi-name) for looking up [DataSource](https://docs.oracle.com/en/java/javase/11/docs/api/java.sql/javax/sql/DataSource.html)s.

To enable the JNDI lookup, you must add the following to your application.conf:

```hocon
postgres-journal {
  slick {
    jndiName = "java:jboss/datasources/PostgresDS"
  }
}
```

When using the `use-shared-db = slick` setting, the follow configuration can serve as an example:

```hocon
akka-persistence-postgres {
  shared-databases {
    slick {
      jndiName = "java:/jboss/datasources/bla"
    }
  }
}
```

## How to get the ReadJournal using Scala

The `ReadJournal` is retrieved via the `akka.persistence.query.PersistenceQuery` extension:

```scala
import akka.persistence.query.PersistenceQuery
import akka.persistence.postgres.query.scaladsl.PostgresReadJournal

val readJournal: PostgresReadJournal = PersistenceQuery(system).readJournalFor[PostgresReadJournal](PostgresReadJournal.Identifier)
```

## How to get the ReadJournal using Java

The `ReadJournal` is retrieved via the `akka.persistence.query.PersistenceQuery` extension:

```java
import akka.persistence.query.PersistenceQuery
import akka.persistence.postgres.query.javadsl.PostgresReadJournal

final PostgresReadJournal readJournal = PersistenceQuery.get(system).getReadJournalFor(PostgresReadJournal.class, PostgresReadJournal.Identifier());
```

## Persistence Query

The plugin supports the following queries:

## AllPersistenceIdsQuery and CurrentPersistenceIdsQuery

`allPersistenceIds` and `currentPersistenceIds` are used for retrieving all persistenceIds of all persistent actors.

```scala
import akka.actor.ActorSystem
import akka.stream.{Materializer, ActorMaterializer}
import akka.stream.scaladsl.Source
import akka.persistence.query.PersistenceQuery
import akka.persistence.postgres.query.scaladsl.PostgresReadJournal

implicit val system: ActorSystem = ActorSystem()
implicit val mat: Materializer = ActorMaterializer()(system)
val readJournal: PostgresReadJournal = PersistenceQuery(system).readJournalFor[PostgresReadJournal](PostgresReadJournal.Identifier)

val willNotCompleteTheStream: Source[String, NotUsed] = readJournal.allPersistenceIds()

val willCompleteTheStream: Source[String, NotUsed] = readJournal.currentPersistenceIds()
```

The returned event stream is unordered and you can expect different order for multiple executions of the query.

When using the `allPersistenceIds` query, the stream is not completed when it reaches the end of the currently used persistenceIds,
but it continues to push new persistenceIds when new persistent actors are created.

When using the `currentPersistenceIds` query, the stream is completed when the end of the current list of persistenceIds is reached,
thus it is not a `live` query.

The stream is completed with failure if there is a failure in executing the query in the backend journal.

## EventsByPersistenceIdQuery and CurrentEventsByPersistenceIdQuery

`eventsByPersistenceId` and `currentEventsByPersistenceId` is used for retrieving events for
a specific PersistentActor identified by persistenceId.

```scala
import akka.actor.ActorSystem
import akka.stream.{Materializer, ActorMaterializer}
import akka.stream.scaladsl.Source
import akka.persistence.query.{ PersistenceQuery, EventEnvelope }
import akka.persistence.postgres.query.scaladsl.PostgresReadJournal

implicit val system: ActorSystem = ActorSystem()
implicit val mat: Materializer = ActorMaterializer()(system)
val readJournal: PostgresReadJournal = PersistenceQuery(system).readJournalFor[PostgresReadJournal](PostgresReadJournal.Identifier)

val willNotCompleteTheStream: Source[EventEnvelope, NotUsed] = readJournal.eventsByPersistenceId("some-persistence-id", 0L, Long.MaxValue)

val willCompleteTheStream: Source[EventEnvelope, NotUsed] = readJournal.currentEventsByPersistenceId("some-persistence-id", 0L, Long.MaxValue)
```

You can retrieve a subset of all events by specifying `fromSequenceNr` and `toSequenceNr` or use `0L` and `Long.MaxValue` respectively to retrieve all events. Note that the corresponding sequence number of each event is provided in the `EventEnvelope`, which makes it possible to resume the stream at a later point from a given sequence number.

The returned event stream is ordered by sequence number, i.e. the same order as the PersistentActor persisted the events. The same prefix of stream elements (in same order) are returned for multiple executions of the query, except for when events have been deleted.

The stream is completed with failure if there is a failure in executing the query in the backend journal.

## EventsByTag and CurrentEventsByTag

`eventsByTag` and `currentEventsByTag` are used for retrieving events that were marked with a given
`tag`, e.g. all domain events of an Aggregate Root type.

```scala
import akka.actor.ActorSystem
import akka.stream.{Materializer, ActorMaterializer}
import akka.stream.scaladsl.Source
import akka.persistence.query.{ PersistenceQuery, EventEnvelope }
import akka.persistence.postgres.query.scaladsl.PostgresReadJournal

implicit val system: ActorSystem = ActorSystem()
implicit val mat: Materializer = ActorMaterializer()(system)
val readJournal: PostgresReadJournal = PersistenceQuery(system).readJournalFor[PostgresReadJournal](PostgresReadJournal.Identifier)

val willNotCompleteTheStream: Source[EventEnvelope, NotUsed] = readJournal.eventsByTag("apple", 0L)

val willCompleteTheStream: Source[EventEnvelope, NotUsed] = readJournal.currentEventsByTag("apple", 0L)
```

## Tagging events

To tag events you'll need to create an [Event Adapter](https://doc.akka.io/docs/akka/current/persistence.html#event-adapters) that will wrap the event in a [akka.persistence.journal.Tagged](https://doc.akka.io/api/akka/current/akka/persistence/journal/Tagged.html) class with the given tags. The `Tagged` class will instruct `akka-persistence-postgres` to tag the event with the given set of tags.

The persistence plugin will __not__ store the `Tagged` class in the journal. It will strip the `tags` and `payload` from the `Tagged` class, and use the class only as an instruction to tag the event with the given tags and store the `payload` in the `message` field of the journal table.

```scala
import akka.persistence.journal.{ Tagged, WriteEventAdapter }
import com.github.dnvriend.Person.{ LastNameChanged, FirstNameChanged, PersonCreated }

class TaggingEventAdapter extends WriteEventAdapter {
  override def manifest(event: Any): String = ""

  def withTag(event: Any, tag: String) = Tagged(event, Set(tag))

  override def toJournal(event: Any): Any = event match {
    case _: PersonCreated =>
      withTag(event, "person-created")
    case _: FirstNameChanged =>
      withTag(event, "first-name-changed")
    case _: LastNameChanged =>
      withTag(event, "last-name-changed")
    case _ => event
  }
}
```

The `EventAdapter` must be registered by adding the following to the root of `application.conf` Please see the  [demo-akka-persistence-postgres](https://github.com/SwissBorg/demo-akka-persistence-postgres) project for more information.

```bash
postgres-journal {
  event-adapters {
    tagging = "com.github.dnvriend.TaggingEventAdapter"
  }
  event-adapter-bindings {
    "com.github.dnvriend.Person$PersonCreated" = tagging
    "com.github.dnvriend.Person$FirstNameChanged" = tagging
    "com.github.dnvriend.Person$LastNameChanged" = tagging
  }
}
```

You can retrieve a subset of all events by specifying offset, or use `0L` to retrieve all events with a given tag. The offset corresponds to an ordered sequence number for the specific tag. Note that the corresponding offset of each event is provided in the `EventEnvelope`, which makes it possible to resume the stream at a later point from a given offset.

In addition to the offset the `EventEnvelope` also provides `persistenceId` and `sequenceNr` for each event. The `sequenceNr` is the sequence number for the persistent actor with the `persistenceId` that persisted the event. The `persistenceId` + `sequenceNr` is an unique identifier for the event.

The returned event stream contains only events that correspond to the given tag, and is ordered by the creation time of the events. The same stream elements (in same order) are returned for multiple executions of the same query. Deleted events are not deleted from the tagged event stream.

## Custom DAO Implementation

The plugin supports loading a custom DAO for the journal and snapshot. You should implement a custom Data Access Object (DAO) if you wish to alter the default persistency strategy in
any way, but wish to reuse all the logic that the plugin already has in place, eg. the Akka Persistence Query API. For example, the default persistency strategy that the plugin
supports serializes journal and snapshot messages using a serializer of your choice and stores them as byte arrays in the database.

By means of configuration in application.conf a DAO can be configured, below the default DAOs are shown:

```hocon
postgres-journal {
  dao = "akka.persistence.postgres.journal.dao.FlatJournalDao"
}

postgres-snapshot-store {
  dao = "akka.persistence.postgres.snapshot.dao.ByteArraySnapshotDao"
}

postgres-read-journal {
  dao = "akka.persistence.postgres.query.dao.ByteArrayReadJournalDao"
}
```

Storing messages as byte arrays in blobs is not the only way to store information in a database. For example, you could store messages with full type information as a normal database rows, each event type having its own table.
For example, implementing a Journal Log table that stores all persistenceId, sequenceNumber and event type discriminator field, and storing the event data in another table with full typing

You only have to implement two interfaces `akka.persistence.postgres.journal.dao.JournalDao` and/or `akka.persistence.postgres.snapshot.dao.SnapshotDao`. 

For example, take a look at the following two custom DAOs:

```scala
class MyCustomJournalDao(val db: Database, val journalConfig: JournalConfig, serialization: Serialization)(implicit ec: ExecutionContext, mat: Materializer) extends JournalDao {
    // snip
}

class MyCustomSnapshotDao(db: JdbcBackend#Database, snapshotConfig: SnapshotConfig, serialization: Serialization)(implicit ec: ExecutionContext, val mat: Materializer) extends SnapshotDao {
    // snip
}
```

As you can see, the custom DAOs get a _Slick database_, the journal or snapshot _configuration_, an _akka.serialization.Serialization_, an _ExecutionContext_ and _Materializer_ injected after constructed.
You should register the Fully Qualified Class Name in application.conf so that the custom DAOs will be used.

For more information please review the two default implementations `akka.persistence.postgres.journal.dao.FlatJournalDao` and `akka.persistence.postgres.snapshot.dao.ByteArraySnapshotDao` or the demo custom DAO example from the [demo-akka-persistence](https://github.com/SwissBorg/demo-akka-persistence-postgres) site.

> :warning: The APIs for custom DAOs are not guaranteed to be binary backwards compatible between major versions of the plugin.
> There may also be source incompatible changes of the APIs for customer DAOs if new capabilities must be added to the traits.

## Explicitly shutting down the database connections

The plugin automatically shuts down the HikariCP connection pool when the ActorSystem is terminated.
This is done using @apidoc[ActorSystem.registerOnTermination](ActorSystem).
