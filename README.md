# Akka Persistence Postgres

[![License](https://img.shields.io/:license-Apache%202-red.svg)](https://www.apache.org/licenses/LICENSE-2.0.txt)
[![Actions Status](https://github.com/SwissBorg/akka-persistence-postgres/workflows/Scala%20CI/badge.svg)](https://github.com/SwissBorg/akka-persistence-postgres/actions)

This is a fork of the official Akka's JDBC plugin, aimed to support PostgreSQL 11 features such as partitions, arrays, BRIN indexes and others.

This plugin is created to reduce size of journal indexes.  

## Difference in a compare to Akka JDBC plugin

### BRIN index on ordering
Akka-persistence-jdbc use BTree index on ordering column. It has a few advantages in compare to BRIN: 
it has faster data access, it can guarantee uniques of values. However, it's size grow very fast and consume more and more RAM memory.
BRIN index does not have information about each record in the table, it is know which value range are persisted in which row range.
Thanks that it has smaller memory footprint.   
More information about [BRIN index](https://www.postgresql.org/docs/11/brin-intro.html) you can read in official Postgres documentation.   

You can also apply BRIN index on ordering for akka-persistence-jdbc. It does not need any changes in code.

### Tags as array of int
* array as tag - smaller size of table, perfectly matched ta

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

