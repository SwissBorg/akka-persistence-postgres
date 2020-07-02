# Akka Persistence Postgres

[![License](https://img.shields.io/:license-Apache%202-red.svg)](https://www.apache.org/licenses/LICENSE-2.0.txt)
[![Actions Status](https://github.com/SwissBorg/akka-persistence-postgres/workflows/Scala%20CI/badge.svg)](https://github.com/SwissBorg/akka-persistence-postgres/actions)

This is a fork of the official Akka's JDBC plugin, aimed to support PostgreSQL 11 features such as partitions, arrays, BRIN indexes and others.
All of this to reduce database RAM usage and index size.  

## How to enable Akka Persistence Postgres in your project

To use this plugin prior default one, add the following to application.conf:
```hocon
akka.persistence {
  journal.plugin = "postgres-journal"
  snapshot-store.plugin = "postgres-snapshot-store"
}
```
and for persistence query
```scala
PersistenceQuery(system).readJournalFor[JdbcReadJournal](JdbcReadJournal.Identifier)
```

## Difference in a compare to Akka Persistence JDBC plugin

### BRIN index on ordering
Akka-persistence-jdbc use BTree index on ordering column. It has a few advantages in compare to BRIN: 
it has faster data access, it can guarantee uniques of values. However, it's size grow very fast and consume more and more RAM memory.
BRIN index does not have information about each record in the table, it is know which value range are persisted in which row range.
Thanks that it has smaller memory footprint. 
More information about [BRIN index](https://www.postgresql.org/docs/11/brin-intro.html) you can read in official Postgres documentation.   

You can also apply BRIN index on ordering for akka-persistence-jdbc. It does not need any changes in code.

### Tags as array of int
Akka Persistence JDBC store all tags in single column as String separated by separators. It causes that searching events by tag should use `tags like '%tag_name%'` statement. 
It isn't perfect as it can return events which has similar names as searched event, eg: looking for events with tag 'car' query return also events with tag 'blue-car', 
which will be filtered out later. In future releases developers of Akka Persistence JDBC is planning to migrate tags to separate table, 
but it is extra cost in terms of index size and RAM usage, which is against our goals.

In this, Akka Persistence Postgres, we decided to store tags name in separate dictionary table. 
Thanks that in journal we are keeping only array of ids of tags. To speed up searching events by tag we are using 
[GIN index](https://www.postgresql.org/docs/11/gin.html) on tags column.

## Versions of plugin

Akka Persistence Postgres is available in 2 versions:
* flat - it is very similar to akka-persistence-jdbc plugin. You can find schema [here](core/src/test/resources/schema/postgres/plain-schema.sql).
* partitioned by persistenceId and sequence_number - This version allows you to store thousands millions of events 
per single persistenceId without huge impact on recovery time and persistence query by persistenceId and sequenceNumber.
It is ideally suited when you have only few persistenceId. More about that version you can read at 'Partitioned version'.
You can find schema [here](core/src/test/resources/schema/postgres/partitioned-schema.sql).   

If you want to use partitioned version of plugin, you should change value of `postgres-journal.partitioned` to `true`.

### Partitioned version
Shortly speaking partitioning in Postgres is the feature, where single table has subtables, which keeps values for specific data range, list, hash.
You are deciding which column should be partitioning column. In Akka Persistence Postgres there are 2 levels of partitioning.
Firstly we partition journal by persistence_id, next the table for particular partition_id we partition by range of sequence_number.
The size of the partitions by sequnce_number range you can adjust by setting: `postgres-journal.tables.journal.partitions.size`.
So for example, when you have persistence_id CAR with 1000 events, persistence_id AIRPLANE with 300 events and `partitions.size=500` 
you will end up with following table structure in the database:
```
journal
├── j_car 
|     ├── j_car_1 -- partition from 0 until 500
|     ├── j_car_2 -- partition from 500 until 1000
|     └── j_car_3 -- partition from 1000 until 1500
└── j_airplane
      └── j_airplane_1  -- partition from 0 until 500
```
Tables journal, j_car and j_airplane does not contain data, their only redirect each query to particular table with data. 
All tables except journal will be automatically created by plugin, when it found that partition does not exist for event.

> :warning: Settings once  `postgres-journal.tables.journal.partitions.size` should newer be changed, 
> otherwise you get PostgresException with table name or range conflict

#### Table pruning
Thanks that structure querying by partition keys, we are do not touch tables and indexes of particular table. 
For example when you recovering your CAR Actor, and your Snapshot was taken for sequence_number 550. The query will only 
touch j_car_2 and j_car_3 tables. Thanks that Postgres can potentially unload not used indexes. You can read more about that 
[here](https://www.2ndquadrant.com/en/blog/partition-elimination-postgresql-11/).

#### Persistence_id rules
We use persistence_id value as part of table name. It has benefit that it is much easier to get know what data table contains.
However, it has few limits related to table name policy in PostgreSQL:
* length of table name is up to 63 characters, each table has some prefix (at least 2 character) and postfix (at least 2 chcaracters), based on that we suggest to use persistence_id with up to 50 characters
* small and big characters are not distinguished, so keep in mind that: "CAR" and "car" will end up with the same table name and in RuntimeException
* table name can contain only letters (A-Z), digits and _ (underscore), to support other characters, we are replacing them to _ (underscore). It causes that "m#o" and "m$o" will be converted to "m_o".

#### Archiving data
There are 2 reasons why you can want to archive data:
* you are using query eventByTag - it doesn't benefit from Table pruning and your queries become slower and slower in time 
* you have limited space on disk.

By archiving data, we mean dumping data from tables and deleting unused tables. If you are interested in scripts let's look at 
our [demo](scripts/partitioned/archivisation/demo.sh).    

### Migration
We currently prepared migration from Akka Persistence JDBC to Partitioned version of plugin. 
There aren't step by step guide instead of it we prepared [demo](scripts/partitioned/migration/demo.sh) script, 
which also contain some consistency checks and other utils.
 
