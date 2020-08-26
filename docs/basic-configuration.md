---
layout: page
title: Configuration
permalink: /configuration
nav_order: 10
has_toc: true
---

# Configuration
{: .no_toc }

## Table of contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Enabling Akka Persistence Postgres plugin

The plugin relies on [Slick-pg](https://github.com/tminglei/slick-pg) to do create the SQL dialect for the database in use, therefore the following must be configured in `application.conf`

Configure `akka-persistence`:

- instruct akka persistence to use the `postgres-journal` plugin,
- instruct akka persistence to use the `postgres-snapshot-store` plugin,

Configure `slick.db`:
 
- setup [connection pool](https://scala-slick.org/doc/3.3.0/database.html#postgresql)

## Database Schema

Depending on the journal variant, choose the appropriate schema:

- [Plain (flat) Journal]({{ site.repo }}/core/src/test/resources/schema/postgres/plain-schema.sql)
- [Journal with Nested Partitions]({{ site.repo }}/core/src/test/resources/schema/postgres/nested-partitions-schema.sql)

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

## Choosing journal schema variants

Currently, the plugin supports two variants of the journal table schema:
*flat journal* - a single table, similar to what the JDBC plugin provides. All events are appended to the table. Schema can be found [here]({{ site.repo }}/core/src/test/resources/schema/postgres/plain-schema.sql).

This is the default schema.

*journal with nested partitions*  by persistenceId and sequenceNumber - this version allows you to shard your events by the persistenceId. Additionally, each of the shards is split by sequenceNumber range to cap the indexes.
You can find the schema [here]({{ site.repo }}/core/src/test/resources/schema/postgres/nested-partitions-schema.sql).

This variant is aimed for services that have a finite and/or small number of unique persistence aggregates, but each of them has a big journal.

*journal partitioned by ordering* (offset) values - this schema fits scenarios with a huge or unbounded number of unique persistence units. Because ordering (offset) is used as a partition key, we can leverage [partition pruning](https://www.postgresql.org/docs/11/ddl-partitioning.html#DDL-PARTITION-PRUNING) while reading from the journal, thus gaining better performance.
You can find the schema [here]({{ site.repo }}/core/src/test/resources/schema/postgres/partitioned-schema.sql).

### Using flat journal

This is the default variant, a [schema without any partitions]({{ site.repo }}/core/src/test/resources/schema/postgres/plain-schema.sql) similar to what's used by Akka Persistence JDBC.

You do not have to override anything in order to start using it, although if you'd like to set it up explicitly, here's the necessary config:

```hocon
postgres-journal.dao = "akka.persistence.postgres.journal.dao.FlatJournalDao"
```

### Using journal partitioned by persistence id and sequence number

In order to start using journal with nested partitions, you have to create a table with nested partitions (here is [the schema]({{ site.repo }}/core/src/test/resources/schema/postgres/nested-partitions-schema.sql)) and set the Journal DAO FQCN:
```hocon
postgres-journal.dao = "akka.persistence.postgres.journal.dao.NestedPartitionsJournalDao"
```

#### Partition size
The size of the nested partitions (`sequence_number`’s range) can be changed by setting `postgres-journal.tables.journal.partitions.size`. By default partition size is set to `10000000` (10M).

Partitions are automatically created by the plugin in advance. `NestedPartitionsJournalDao` keeps track of created partitions and once sequence_number is out of the range for any known partitions, a new one is created.

#### Partition table names

Partitions follow the `prefix_sanitizedPersistenceId_partitionNumber` naming pattern.
The `prefix` can be configured by changing the `posgres-journal.tables.journal.partitions.prefix` value. By default it’s set to `j`.
`sanitizedPersistenceId` is PersistenceId with all non-word characters replaced by `_`.
`partitionNumber` is the ordinal number of the partition for a given partition id.

Example partition names: `j_myActor_0`, `j_myActor_1`, `j_worker_0` etc.

Keep in mind that the default maximum length for a table name in Postgres is 63 bytes, so you should avoid any non-ascii characters in your `persistenceId`s and keep the `prefix` reasonably short.

> :warning: Once any of the partitioning setting under  `postgres-journal.tables.journal.partitions` branch is settled, you should never change it.  Otherwise you might end up with PostgresExceptions caused by table name or range conflicts.

### Using journal partitioned by ordering (offset)

In order to start using partitioned journal, you have to apply [this schema]({{ site.repo }}/core/src/test/resources/schema/postgres/nested-partitions-schema.sql) and set the Journal DAO FQCN:
```hocon
postgres-journal.dao = "akka.persistence.postgres.journal.dao.PartitionedJournalDao"
```

#### Partition size
The size of each partition (`ordering`’s range) can be changed by setting `postgres-journal.tables.journal.partitions.size`. By default partition size is set to `10000000` (10M).

Partitions are automatically created by the plugin in advance. `PartitionedJournalDao` keeps track of created partitions and once ordering is out of the range for any known partitions, a new one is created.

#### Partition table names

Partitions follow the `prefix_partitionNumber` naming pattern.
The `prefix` can be configured by changing the `posgres-journal.tables.journal.partitions.prefix` value. By default it’s set to `j`.
`partitionNumber` is the ordinal number of the partition for a given partition id.

Example partition names: `j_0`, `j_1`, `j_2` etc.

> :warning: Once any of the partitioning setting under  `postgres-journal.tables.journal.partitions` branch is settled, you should never change it.  Otherwise you might end up with PostgresExceptions caused by table name or range conflicts.

## Tags caching
Tags are mapped into their unique integer ids and store in a column of type `int[]`.

In order to provide fast access we cache those mappings. You can define how long given mapping entry remains in the cache before it gets wiped out by setting `postgres-journal.tags.cacheTtl` (used by write journal when persisting events) and `postgres-read-journal.tags.cacheTtl` (used by read journal when querying events by tags) config parameters.

Default value is **1 hour**.

## Explicitly shutting down the database connections

The plugin automatically shuts down the HikariCP connection pool when the ActorSystem is terminated.
This is done using [ActorSystem.registerOnTermination](https://doc.akka.io/api/akka/current/akka/actor/ActorSystem.html).
