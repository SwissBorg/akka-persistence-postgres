/*
 * Copyright (C) 2014 - 2019 Dennis Vriend <https://github.com/dnvriend>
 * Copyright (C) 2019 - 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.jdbc
package query.dao

import akka.persistence.jdbc.config.{ JournalTableConfiguration, ReadJournalConfig }
import akka.persistence.jdbc.journal.dao.JournalTables

class ReadJournalQueries(val readJournalConfig: ReadJournalConfig) extends JournalTables {
  override val journalTableCfg: JournalTableConfiguration = readJournalConfig.journalTableConfiguration

  import akka.persistence.jdbc.db.ExtendedPostgresProfile.api._

  private def _allPersistenceIdsDistinct(max: ConstColumn[Long]): Query[Rep[String], String, Seq] =
    baseTableQuery().map(_.persistenceId).distinct.take(max)

  private def baseTableQuery() =
    if (readJournalConfig.includeDeleted) JournalTable
    else JournalTable.filter(_.deleted === false)

  val allPersistenceIdsDistinct = Compiled(_allPersistenceIdsDistinct _)

  private def _messagesQuery(
      persistenceId: Rep[String],
      fromSequenceNr: Rep[Long],
      toSequenceNr: Rep[Long],
      max: ConstColumn[Long]) =
    baseTableQuery()
      .filter(_.persistenceId === persistenceId)
      .filter(_.sequenceNumber >= fromSequenceNr)
      .filter(_.sequenceNumber <= toSequenceNr)
      .sortBy(_.sequenceNumber.asc)
      .take(max)

  val messagesQuery = Compiled(_messagesQuery _)

  protected def _eventsByTag(
      tag: Rep[List[Int]],
      offset: ConstColumn[Long],
      maxOffset: ConstColumn[Long],
      max: ConstColumn[Long]) = {
    baseTableQuery()
      .filter(_.tags @> tag)
      .sortBy(_.ordering.asc)
      .filter(row => row.ordering > offset && row.ordering <= maxOffset)
      .take(max)
  }

  val eventsByTag = Compiled(_eventsByTag _)

  private def _journalSequenceQuery(from: ConstColumn[Long], limit: ConstColumn[Long]) =
    JournalTable.filter(_.ordering > from).map(_.ordering).sorted.take(limit)

  val orderingByOrdering = Compiled(_journalSequenceQuery _)

  val maxOrdering = Compiled {
    JournalTable.map(_.ordering).max.getOrElse(0L)
  }
}
