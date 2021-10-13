/*
 * Copyright (C) 2014 - 2019 Dennis Vriend <https://github.com/dnvriend>
 * Copyright (C) 2019 - 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.postgres
package query.dao

import akka.persistence.postgres.config.ReadJournalConfig
import akka.persistence.postgres.journal.dao.{ FlatJournalTable, JournalPersistenceIdsTable, JournalTable }

class ReadJournalQueries(val readJournalConfig: ReadJournalConfig) {
  import akka.persistence.postgres.db.ExtendedPostgresProfile.api._

  private val journalTable: TableQuery[JournalTable] = FlatJournalTable(readJournalConfig.journalTableConfiguration)
  private val journalPersistenceIdsTable: TableQuery[JournalPersistenceIdsTable] = JournalPersistenceIdsTable(
    readJournalConfig.journalPersistenceIdsTableConfiguration)

  private def _allPersistenceIds(max: ConstColumn[Long]): Query[Rep[String], String, Seq] =
    if (readJournalConfig.includeDeleted)
      journalPersistenceIdsTable.map(_.persistenceId).take(max)
    else
      journalPersistenceIdsTable
        .joinLeft(journalTable.filter(_.deleted === false))
        .on(_.persistenceId === _.persistenceId)
        .filter(_._2.isDefined)
        .map(_._1.persistenceId)
        .take(max)

  val allPersistenceIds = Compiled(_allPersistenceIds _)

  private def baseTableQuery() =
    if (readJournalConfig.includeDeleted) journalTable
    else journalTable.filter(_.deleted === false)

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
      maxOffset: ConstColumn[Long]): Query[JournalTable, JournalRow, Seq] = {
    baseTableQuery()
      .filter(_.tags @> tag)
      .sortBy(_.ordering.asc)
      .filter(row => row.ordering > offset && row.ordering <= maxOffset)
  }

  val eventsByTag = Compiled(_eventsByTag _)

  private def _journalSequenceQuery(from: ConstColumn[Long], limit: ConstColumn[Long]) =
    journalTable.filter(_.ordering > from).map(_.ordering).sorted.take(limit)

  val orderingByOrdering = Compiled(_journalSequenceQuery _)

  val maxOrdering = Compiled {
    journalPersistenceIdsTable.map(_.maxOrdering).max.getOrElse(0L)
  }
}
