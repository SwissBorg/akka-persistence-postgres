package akka.persistence.postgres.journal.dao

import slick.lifted.TableQuery

class JournalMetadataQueries(journalMetadataTable: TableQuery[JournalMetadataTable]) {
  import akka.persistence.postgres.db.ExtendedPostgresProfile.api._

  private def _highestSequenceNrForPersistenceId(persistenceId: Rep[String]): Query[Rep[Long], Long, Seq] = {
    journalMetadataTable.filter(_.persistenceId === persistenceId).map(_.maxSequenceNumber).take(1)
  }

  val highestSequenceNrForPersistenceId = Compiled(_highestSequenceNrForPersistenceId _)

  private def _minAndMaxOrderingForPersistenceId(
      persistenceId: Rep[String]): Query[(Rep[Long], Rep[Long]), (Long, Long), Seq] =
    journalMetadataTable.filter(_.persistenceId === persistenceId).take(1).map(r => (r.minOrdering, r.maxOrdering))

  val minAndMaxOrderingForPersistenceId = Compiled(_minAndMaxOrderingForPersistenceId _)
}
