/*
 * Copyright (C) 2014 - 2019 Dennis Vriend <https://github.com/dnvriend>
 * Copyright (C) 2019 - 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.jdbc.util

import java.sql.Statement

import akka.persistence.jdbc.util.Schema.SchemaType
import slick.jdbc.JdbcBackend.{ Database, Session }

object Schema {
  sealed trait SchemaType { def schema: String }
  final case class Postgres(schema: String = "schema/postgres/postgres-schema.sql") extends SchemaType
}

trait DropCreate extends ClasspathResources {
  def db: Database

  def dropCreate(schemaType: SchemaType): Unit =
    create(schemaType.schema)

  def create(schema: String, separator: String = ";"): Unit =
    for {
      schema <- Option(fromClasspathAsString(schema))
      ddl <- for {
        trimmedLine <- schema.split(separator).map(_.trim)
        if trimmedLine.nonEmpty
      } yield trimmedLine
    } withStatement { stmt =>
      try stmt.executeUpdate(ddl)
      catch {
        case t: java.sql.SQLSyntaxErrorException if t.getMessage contains "ORA-00942" => // suppress known error message in the test
      }
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
