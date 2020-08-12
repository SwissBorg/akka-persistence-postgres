/*
 * Copyright (C) 2014 - 2019 Dennis Vriend <https://github.com/dnvriend>
 * Copyright (C) 2019 - 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.postgres.util

import java.sql.Statement

import akka.persistence.postgres.util.Schema.SchemaType
import slick.jdbc.JdbcBackend.{ Database, Session }

object Schema {
  sealed trait SchemaType { def schema: String }
  final case class Plain(schema: String = "schema/postgres/plain-schema.sql") extends SchemaType
  final case class NestedPartitions(schema: String = "schema/postgres/nested-partitions-schema.sql") extends SchemaType
}

trait DropCreate extends ClasspathResources {
  def db: Database

  def dropCreate(schemaType: SchemaType): Unit =
    create(schemaType.schema)

  def create(schema: String): Unit =
    for {
      schema <- Option(fromClasspathAsString(schema))
    } withStatement { stmt =>
      stmt.executeUpdate(schema)
    }

  def withDatabase[A](f: Database => A): A =
    f(db)

  def withSession[A](f: Session => A): A = {
    withDatabase { db =>
      val session = db.createSession()
      try f(session)
      finally session.close()
    }
  }

  def withStatement[A](f: Statement => A): A =
    withSession(session => session.withStatement()(f))
}
