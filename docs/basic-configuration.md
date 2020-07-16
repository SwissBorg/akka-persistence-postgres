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

## Explicitly shutting down the database connections

The plugin automatically shuts down the HikariCP connection pool when the ActorSystem is terminated.
This is done using [ActorSystem.registerOnTermination](https://doc.akka.io/api/akka/current/akka/actor/ActorSystem.html).
