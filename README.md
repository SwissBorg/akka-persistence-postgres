# Akka Persistence Postgres

[![License](https://img.shields.io/:license-Apache%202-red.svg)](https://www.apache.org/licenses/LICENSE-2.0.txt)

This is a fork of the official Akka's JDBC plugin, aimed to support PostgreSQL 11 features such as partitions, arrays, BRIN indexes and others.

* idea - to reduce size of indexes

## Versions of plugin 
* flat
* partitioned by persistenceId and sequence_number
* where to use which

## Difference in a compare to Akka JDBC plugin

* BRIN index on ordering - smaller index
* array as tag - smaller size of table, perfectly matched tags

## Partitioned version
* how to enable
* configuration
* archivisation
* migration

