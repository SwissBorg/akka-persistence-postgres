---
layout: page
title: Migrations
permalink: /migration
nav_order: 60
---

# Migrations

## Migration from akka-persistence-jdbc 4.0.0
It is possible to migrate existing journals from Akka Persistence JDBC 4.0.0. 
Since we decided to extract metadata from the serialized payload and store it in a separate column it is not possible to migrate exiting journal and snapshot store using plain SQL scripts.

### How migration works
Each journal event and snapshot has to be read, deserialized, metadata and tags must be extracted and then everything stored in the new table.

We provide you with an optional artifact, `akka-persistence-postgres-migration` that brings to your project the necessary classes to automate the above process.

*Important*: Our util classes neither drop nor update any old data. Original tables will be still there but renamed with an `old_` prefix. It's up to you when to drop them.

### How to use plugin provided migrations
#### Add akka-persistence-migration to your project
Add the following to your `build.sbt` 
```
libraryDependencies += "com.swissborg" %% "akka-persistence-postgres-migration" % "0.5.0"
``` 
For a maven project add: 
```xml
<dependency>
    <groupId>com.swisborg</groupId>
    <artifactId>akka-persistence-postgres-migration_2.13</artifactId>
    <version>0.5.0</version>
</dependency>
``` 
to your `pom.xml`.

#### Create and run migrations:
```scala
import akka.persistence.postgres.migration.journal.Jdbc4JournalMigration
import akka.persistence.postgres.migration.snapshot.Jdbc4SnapshotStoreMigration

for {
_ <- new Jdbc4JournalMigration(config).run()
_ <- new Jdbc4SnapshotStoreMigration(config).run()
} yield ()
```
*Very important note*: The migration has to be finished before your application starts any persistent actors!

It's your choice whether you want to trigger migration manually or (recommended) leverage a database version control system of your choice (e.g. Flyway).

### Examples
An example Flyway-based migration can be found in the demo app: https://github.com/mkubala/demo-akka-persistence-postgres/blob/master/src/main/scala/com/github/mkubala/FlywayMigrationExample.scala

## Migration from akka-persistence-postgres 0.4.0 to 0.5.0
New indices need to be created on each partition, to avoid locking production databases for too long, it should be done in 2 steps:
1. manually create indices CONCURRENTLY,
2. deploy new release with migration scripts.

### Manually create indices CONCURRENTLY
Execute DDL statements produced by the [sample migration script](https://github.com/SwissBorg/akka-persistence-postgres/blob/master/scripts/migration-0.5.0/partitioned/1-add-indices-manually.sql), adapt top level variables to match your journal configuration before executing.

### Deploy new release with migration scripts
See [sample flyway migration script](https://github.com/SwissBorg/akka-persistence-postgres/blob/master/scripts/migration-0.5.0/partitioned/2-add-indices-flyway.sql) and adapt top level variables to match your journal configuration.
