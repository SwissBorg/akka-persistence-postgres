# Akka Persistence Postgres

[![License](https://img.shields.io/:license-Apache%202-red.svg)](https://www.apache.org/licenses/LICENSE-2.0.txt)
[![Actions Status](https://github.com/SwissBorg/akka-persistence-postgres/workflows/Scala%20CI/badge.svg)](https://github.com/SwissBorg/akka-persistence-postgres/actions)

This is a fork of the official Akka's JDBC plugin, aimed to support PostgreSQL 11 features such as partitions, arrays, BRIN indexes and others.
All of this to reduce database RAM usage and index size.  

## Difference in a compare to Akka Persistence JDBC plugin

### BRIN index on ordering
Akka-persistence-jdbc use BTree index on ordering column. It has a few advantages in compare to BRIN: 
it has faster data access, it can guarantee uniques of values. However, it's size grow very fast and consume more and more RAM memory.
BRIN index does not have information about each record in the table, it is know which value range are persisted in which row range.
Thanks that it has smaller memory footprint. 
More information about [BRIN index](https://www.postgresql.org/docs/11/brin-intro.html) you can read in official Postgres documentation.   

You can also apply BRIN index on ordering for akka-persistence-jdbc. It does not need any changes in code.

### Tags as array of int
Akka Persistence JDBC store all tags in single column as String separated by separators. It cause that searching events by tag should use `tags like '%tag_name%'` statement. 
It isn't perfect as it can return events which has similar names as searched event, eg: looking for events with tag 'car' query return also events with tag 'blue-car', 
which will be filtered out later. In future releases developers of Akka Persistence JDBC has plan to migrate tags to separate table, but it is extra cost in terms of RAM usage.

In this, Akka Persistence Postgres, we decided to store tags name in separate dictionary table. 
Thanks that in journal we are keeping only array of ids of tags. To bootstrap searching we are using 
[GIN index](https://www.postgresql.org/docs/11/gin.html).

## Versions of plugin

Akka Persistence Postgres is available in 2 versions:
* flat - it is very similar to akka-persistence-jdbc plugin.
* partitioned by persistenceId and sequence_number - That version allow you to store thousands millions of events 
per single persistenceId without huge impact on recovery time and persistence query by persistenceId and sequenceNumber.
It is ideally suited when you have only few persistenceId. More about that version you can read at 'Partitioned version'.   

If you want to use partitioned version of plugin, you should change value of `postgres-journal.partitioned` to `true`.

## Partitioned version

* configuration
* archivisation
* migration

