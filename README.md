# Akka Persistence Postgres

[![License](https://img.shields.io/:license-Apache%202-red.svg)](https://www.apache.org/licenses/LICENSE-2.0.txt)
[![Actions Status](https://github.com/SwissBorg/akka-persistence-postgres/workflows/Scala%20CI/badge.svg)](https://github.com/SwissBorg/akka-persistence-postgres/actions)

It is a fork of the official Akka's JDBC plugin, aimed to support PostgreSQL 11 features such as partitions, arrays, BRIN indexes and others.
All of this to reduce RAM usage and index size by the database.  

## How to enable Akka Persistence Postgres in your project
To use this plugin prior default one, add the following to application.conf:
```hocon
akka.persistence {
  journal.plugin = "pg-journal"
  snapshot-store.plugin = "pg-snapshot-store"
}
```
and for persistence query
```scala
PersistenceQuery(system).readJournalFor[JdbcReadJournal](JdbcReadJournal.Identifier)
```

## Difference in a compare to Akka Persistence JDBC plugin

### BRIN index on ordering
Akka-persistence-jdbc uses a B-Tree index on the ordering column. It has a few advantages in comparison to BRIN:  
it has faster data access, it can guarantee uniqueness of values. However, it's size grow very fast and consume more and more RAM.
BRIN index does not have information about each record in the table, it knows which value range are persisted in which row range.
Thanks to that it has a smaller memory footprint. 
More information about [BRIN index](https://www.postgresql.org/docs/11/brin-intro.html) you can read in official Postgres documentation.   

You can also apply the BRIN index on the ordering column for your akka-persistence-jdbc setup. It does not need any changes in code.

### Tags as an array of int
Akka-persistence-jdbc store all tags in a single column as String separated by commas. It causes that plugin searching for events by tag uses `tags like '%tag_name%'` statement. 
It isn't perfect as it can return events which have similar tag names as a searched tag, eg: 
query looking for events with 'car' tag return also events with 'blue-car' tag. From version 4.0.0 extra events are filtered out in Scala. 
In future releases, developers of Akka Persistence JDBC is planning to migrate tags to separate table, 
but it is an extra cost in terms of index size and RAM usage, which is against our goals.

In akka-persistence-postgres we decided to store tag name in separate dictionary table, 
whereas in the journal table we are keeping the array of ids of tags. We introduce 2 optimizations:
* [GIN index](https://www.postgresql.org/docs/11/gin.html) on tags column, which makes searching events by tag faster;
* cache of mapping tag id to tag name to reduce the number of connections to the database.
The cache is tunable under `TODO` properties.

## Versions of the plugin

Akka Persistence Postgres supports two variants of the journal schema:
* flat - it is very similar to akka-persistence-jdbc plugin. You can find the schema [here](core/src/test/resources/schema/postgres/plain-schema.sql).
* partitioned by persistence_id and sequence_number - This version allows you to store thousands of millions of events 
per single persistence_id without a huge impact on recovery time and persistence query by persistence_id and sequence_number.
It is ideally suited when you have only a few aggregates. More about that version you can read at 'Partitioned version'.
You can find the schema [here](core/src/test/resources/schema/postgres/partitioned-schema.sql).   

If you want to use a partitioned version of the plugin, you should change the value of `pg-journal.partitioned` to `true`.

### Partitioned version
Shortly speaking partitioning in Postgres is the feature, where a single table has subtables, which keeps values for specific data range, list, hash.
You are deciding which column should be partitioning column. In akka-persistence-postgres there are 2 levels of partitioning. 
Firstly we partition the journal table by persistence_id, and next the table for particular persistence_id we partition by a range of sequence_number. 
The size of the partitions by sequence_number range you can adjust by setting: `pg-journal.tables.journal.partitions.size`. 
So for example, when you have an aggregate with persistence_id CAR for which you stored 1000 events, 
aggregate with persistence_id AIRPLANE for which you stored 300 events and `partitions.size=500` 
you will end up with the following table structure in the database:
```
journal
├── j_car 
|     ├── j_car_1 -- partition from 0 until 500
|     ├── j_car_2 -- partition from 500 until 1000
|     └── j_car_3 -- partition from 1000 until 1500
└── j_airplane
      └── j_airplane_1  -- partition from 0 until 500
```
journal, j_car and j_airplane tables do not contain data, their only redirect each query to a particular table with data. 
All tables except journal will be automatically created by the plugin, when it found that partition does not exist for an event.

> :warning: Setting once  `pg-journal.tables.journal.partitions` should never be changed, otherwise, you get PostgresException with table name conflict or range conflict.
#### Table pruning
Thanks to the partitioned structure of journal table, querying by partition keys are do not touch tables and indexes of tables 
which do not contain requested data. For example when you recovering your CAR Actor, and your Snapshot was taken for sequence_number 550. 
The query will take data from j_car_2 and j_car_3 tables, whereas j_car_1 and j_airplane_1 will be untouched. 
In consequences, PostgreSQL can potentially unload from RAM not used indexes. You can read more about that  
[here](https://www.2ndquadrant.com/en/blog/partition-elimination-postgresql-11/).

#### Persistence_id rules
We use persistence_id value as part of the table name. It has the benefit that it is easier to know what data table 
contains by DevOps. However, it leads us to a few limits related to table name policy in PostgreSQL:
* length of a table name is up to 63 characters, each table has some prefix (at least 2 characters) and postfix (at least 2 characters), 
based on that we suggest using persistence_id with up to 50 characters
* small and big characters are not distinguished, so keep in mind that: 
having aggregates with persistence_id "CAR" and "car" will be translated to the same table name and will cause exception in runtime 
* table name can contain only letters (A-Z), digits(0-9) and _ (underscore), 
to support other characters, we are replacing them to _ (underscore). 
It causes that "m#o" and "m$o" will be converted to "m_o" and it again can lead to table name conflict in runtime.

#### Archiving data
There are 2 reasons why you can want to archive data:
* you are using query eventByTag - it doesn't benefit from Table pruning and your queries become slower and slower in time 
* you have limited space on a disk.

By archiving data, we mean dumping data from tables and deleting unused tables. If you are interested in scripts look at 
our [demo](scripts/partitioned/archivisation/demo.sh).  

### Migration
We currently prepared migration from akka-persistence-jdbc to Partitioned version of the plugin. 
There aren't step by step guide instead of it we prepared [demo](scripts/partitioned/migration/demo.sh) script, 
which also contain some consistency checks and other utils.
