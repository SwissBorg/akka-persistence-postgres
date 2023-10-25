/*
 * Copyright (C) 2014 - 2019 Dennis Vriend <https://github.com/dnvriend>
 * Copyright (C) 2019 - 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.postgres
package query.dao

import akka.persistence.postgres.journal.dao.JournalTable
import slick.lifted.TableQuery

class ReadJournalQueries(journalTable: TableQuery[JournalTable], includeDeleted: Boolean) {
  import akka.persistence.postgres.db.ExtendedPostgresProfile.api._

  private def _allPersistenceIdsDistinct(max: ConstColumn[Long]): Query[Rep[String], String, Seq] =
    baseTableQuery().map(_.persistenceId).distinct.take(max)

  private def baseTableQuery() =
    if (includeDeleted) journalTable
    else journalTable.filter(_.deleted === false)

  val allPersistenceIdsDistinct = Compiled(_allPersistenceIdsDistinct _)

  private def _messagesQuery(
      persistenceId: Rep[String],
      fromSequenceNr: Rep[Long],
      toSequenceNr: Rep[Long],
      max: ConstColumn[Long]): Query[JournalTable, JournalRow, Seq] =
    baseTableQuery()
      .filter(_.persistenceId === persistenceId)
      .filter(_.sequenceNumber >= fromSequenceNr)
      .filter(_.sequenceNumber <= toSequenceNr)
      .sortBy(_.sequenceNumber.asc)
      .take(max)

  private def _messagesMinOrderingBoundedQuery(
      persistenceId: Rep[String],
      fromSequenceNr: Rep[Long],
      toSequenceNr: Rep[Long],
      max: ConstColumn[Long],
      minOrdering: Rep[Long]): Query[JournalTable, JournalRow, Seq] =
    baseTableQuery()
      .filter(_.persistenceId === persistenceId)
      .filter(_.sequenceNumber >= fromSequenceNr)
      .filter(_.sequenceNumber <= toSequenceNr)
      .filter(_.ordering >= minOrdering)
      .sortBy(_.sequenceNumber.asc)
      .take(max)

  val messagesQuery = Compiled(_messagesQuery _)

  val messagesMinOrderingBoundedQuery = Compiled(_messagesMinOrderingBoundedQuery _)

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
    journalTable.map(_.ordering).max.getOrElse(0L)
  }
}
