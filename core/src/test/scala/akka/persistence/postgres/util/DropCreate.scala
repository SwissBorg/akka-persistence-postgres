/*
 * Copyright (C) 2014 - 2019 Dennis Vriend <https://github.com/dnvriend>
 * Copyright (C) 2019 - 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.postgres.util

import java.sql.Statement
import akka.persistence.postgres.config.{ JournalPersistenceIdsTableConfiguration, JournalTableConfiguration }
import akka.persistence.postgres.journal.dao.{
  FlatJournalTable,
  JournalPersistenceIdsTable,
  JournalTable,
  NestedPartitionsJournalTable,
  PartitionedJournalTable
}
import akka.persistence.postgres.util.Schema.SchemaType
import slick.jdbc.JdbcBackend.{ Database, Session }
import slick.lifted.TableQuery

object Schema {

  sealed trait SchemaType {
    def resourceNamePrefix: String
    lazy val schema: String = s"schema/postgres/$resourceNamePrefix-schema.sql"
    lazy val configName: String = s"${resourceNamePrefix}-application.conf"
    def table(journalTableCfg: JournalTableConfiguration): TableQuery[JournalTable]
    def persistenceIdsTable(journalPersistenceIdsTableCfg: JournalPersistenceIdsTableConfiguration)
        : TableQuery[JournalPersistenceIdsTable] = JournalPersistenceIdsTable.apply(journalPersistenceIdsTableCfg)
  }

  case object Plain extends SchemaType {
    override val resourceNamePrefix: String = "plain"
    override def table(journalTableCfg: JournalTableConfiguration): TableQuery[JournalTable] =
      FlatJournalTable(journalTableCfg)
  }

  case object NestedPartitions extends SchemaType {
    override val resourceNamePrefix: String = "nested-partitions"
    override def table(journalTableCfg: JournalTableConfiguration): TableQuery[JournalTable] =
      NestedPartitionsJournalTable(journalTableCfg)
  }

  case object Partitioned extends SchemaType {
    override val resourceNamePrefix: String = "partitioned"
    override def table(journalTableCfg: JournalTableConfiguration): TableQuery[JournalTable] =
      PartitionedJournalTable(journalTableCfg)
  }
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
