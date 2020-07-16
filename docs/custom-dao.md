---
layout: page
title: Custom DAO Implementation
permalink: /custom-dao
nav_order: 40
---

# Custom DAO Implementation

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
class MyCustomJournalDao(db: Database, journalConfig: JournalConfig, serialization: Serialization)(implicit ec: ExecutionContext, mat: Materializer) extends JournalDao {
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
