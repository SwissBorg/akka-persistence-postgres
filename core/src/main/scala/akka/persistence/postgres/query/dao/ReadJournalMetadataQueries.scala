package akka.persistence.postgres.query.dao

import akka.persistence.postgres.journal.dao.JournalMetadataTable
import slick.lifted.TableQuery

class ReadJournalMetadataQueries(journalMetadataTable: TableQuery[JournalMetadataTable]) {

  import akka.persistence.postgres.db.ExtendedPostgresProfile.api._

  private def _minAndMaxOrderingForPersistenceId(
      persistenceId: Rep[String]): Query[(Rep[Long], Rep[Long]), (Long, Long), Seq] =
    journalMetadataTable.filter(_.persistenceId === persistenceId).take(1).map(r => (r.minOrdering, r.maxOrdering))

  val minAndMaxOrderingForPersistenceId = Compiled(_minAndMaxOrderingForPersistenceId _)
}
