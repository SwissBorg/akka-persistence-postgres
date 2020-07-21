---
title: About the plugin
nav_order: 0
---

![Akka Persistence Postgres](assets/project-logo.png)

The Akka Persistence Postgres plugin allows for using Postgres database as backend for [Akka Persistence](https://doc.akka.io/docs/akka/current/persistence.html) and [Akka Persistence Query](https://doc.akka.io/docs/akka/current/persistence-query.html).

akka-persistence-postgres writes journal and snapshot entries to a configured PostgreSQL store. It implements the full akka-persistence-query API and is therefore very useful for implementing DDD-style application models using Akka and Scala for creating reactive applications.

It’s been originally created as a fork of [Akka Persistence JDBC plugin](https://github.com/akka/akka-persistence-jdbc) 4.0.0, focused on PostgreSQL features such as partitions, arrays, BRIN indexes and others. Many parts of this doc have been adopted from the original [project page](https://doc.akka.io/docs/akka-persistence-jdbc/4.0.0/index.html).

The main goal is to keep index size and memory consumption on a moderate level while being able to cope with an increasing data volume.

## Installation

To use `akka-persistence-postgres` in your SBT project, add the following to your `build.sbt`:

```scala
libraryDependencies += "com.swisborg" %% "akka-persistence-postgres" % "X.Y.Z"
```

For a maven project add:
```xml
<dependency>
    <groupId>com.swisborg</groupId>
    <artifactId>akka-persistence-postgres_2.12</artifactId>
    <version>X.Y.Z</version>
</dependency>
```
to your `pom.xml`.

## Source code

Source code for this plugin can be found on [GitHub](https://github.com/SwissBorg/akka-persistence-postgres).

## Contribution policy

Contributions via GitHub pull requests are gladly accepted. Along with any pull requests, please state that the contribution is your original work and that you license the work to the project under the project's open source license. Whether or not you state this explicitly, by submitting any copyrighted material via pull request, email, or other means you agree to license the material under the project's open source license and warrant that you have the legal authority to do so.

## Contributors
List of all contributors can be found on [GitHub](https://github.com/SwissBorg/akka-persistence-postgres/graphs/contributors).

Brought to you by

[![SoftwareMill](assets/softwaremill-logo.png)](https://softwaremill.com)

and

[![SwissBorg](assets/swissborg-logo.png)](https://swissborg.com)

## License

This source code is made available under the [Apache 2.0 License](https://www.apache.org/licenses/LICENSE-2.0).
