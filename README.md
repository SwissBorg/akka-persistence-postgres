# Akka Persistence Postgres

[![License](https://img.shields.io/:license-Apache%202-red.svg)](https://www.apache.org/licenses/LICENSE-2.0.txt)
[![Actions Status](https://github.com/SwissBorg/akka-persistence-postgres/workflows/master/badge.svg)](https://github.com/SwissBorg/akka-persistence-postgres/actions)

This is a fork of the official Akka's JDBC plugin, aimed to support PostgreSQL 11 features such as partitions, arrays, BRIN indexes and others.

This plugin is created to reduce size of journal indexes.  

## Difference in a compare to Akka JDBC plugin

* BRIN index on ordering - smaller index
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

