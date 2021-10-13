/*
 * Copyright (C) 2014 - 2019 Dennis Vriend <https://github.com/dnvriend>
 * Copyright (C) 2019 - 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.postgres
package journal.dao

import io.circe.Json
import slick.lifted.TableQuery
import slick.sql.FixedSqlAction

class JournalQueries(
    journalTable: TableQuery[JournalTable],
    journalPersistenceIdsTable: TableQuery[JournalPersistenceIdsTable]) {

  import akka.persistence.postgres.db.ExtendedPostgresProfile.api._

  private val compiledJournalTable = Compiled(journalTable)

  def writeJournalRows(xs: Seq[JournalRow]): FixedSqlAction[Option[Int], NoStream, slick.dbio.Effect.Write] =
    compiledJournalTable ++= xs.sortBy(_.sequenceNumber)

  def delete(persistenceId: String, toSequenceNr: Long): FixedSqlAction[Int, NoStream, slick.dbio.Effect.Write] = {
    journalTable.filter(_.persistenceId === persistenceId).filter(_.sequenceNumber <= toSequenceNr).delete
  }

  /**
   * Updates (!) a payload stored in a specific events row.
   * Intended to be used sparingly, e.g. moving all events to their encrypted counterparts.
   */
  def update(
      persistenceId: String,
      seqNr: Long,
      replacement: Array[Byte],
      metadata: Json): FixedSqlAction[Int, NoStream, Effect.Write] = {
    val baseQuery = journalTable.filter(_.persistenceId === persistenceId).filter(_.sequenceNumber === seqNr)

    baseQuery.map(r => (r.message, r.metadata)).update((replacement, metadata))
  }

  def markJournalMessagesAsDeleted(persistenceId: String, maxSequenceNr: Long) =
    journalTable
      .filter(_.persistenceId === persistenceId)
      .filter(_.sequenceNumber <= maxSequenceNr)
      .filter(_.deleted === false)
      .map(_.deleted)
      .update(true)

  private def _highestSequenceNrForPersistenceId(persistenceId: Rep[String]) = {
    journalTable.filter(_.persistenceId === persistenceId).map(_.sequenceNumber).max
    // journalPersistenceIdsTable.filter(_.persistenceId === persistenceId).map(_.maxSequenceNumber).take(1)
  }

  private def _highestMarkedSequenceNrForPersistenceId(persistenceId: Rep[String]): Rep[Option[Long]] =
    journalTable.filter(_.deleted === true).filter(_.persistenceId === persistenceId).map(_.sequenceNumber).max

  val highestSequenceNrForPersistenceId = Compiled(_highestSequenceNrForPersistenceId _)

  val highestMarkedSequenceNrForPersistenceId = Compiled(_highestMarkedSequenceNrForPersistenceId _)

  private def _messagesQuery(
      persistenceId: Rep[String],
      fromSequenceNr: Rep[Long],
      toSequenceNr: Rep[Long],
      max: ConstColumn[Long]) =
    journalTable
      .filter(_.persistenceId === persistenceId)
      .filter(_.deleted === false)
      .filter(_.sequenceNumber >= fromSequenceNr)
      .filter(_.sequenceNumber <= toSequenceNr)
      .sortBy(_.sequenceNumber.asc)
      .take(max)

  val messagesQuery = Compiled(_messagesQuery _)

}
