package akka.persistence.postgres.journal.dao

import akka.persistence.postgres.util.BaseQueryTest

class JournalMetadataQueriesTest extends BaseQueryTest {

  it should "create SQL query for highestSequenceNrForPersistenceId" in withJournalMetadataQueries { queries =>
    queries.highestSequenceNrForPersistenceId(
      "aaa") shouldBeSQL """select "max_sequence_number" from "journal_metadata" where "persistence_id" = ? limit 1"""
  }

  it should "create SQL query for minAndMaxOrderingForPersistenceId" in withJournalMetadataQueries { queries =>
    queries.minAndMaxOrderingForPersistenceId(
      "aaa") shouldBeSQL """select "min_ordering", "max_ordering" from "journal_metadata" where "persistence_id" = ? limit 1"""
  }

  private def withJournalMetadataQueries(f: JournalMetadataQueries => Unit): Unit = {
    withActorSystem { implicit system =>
      f(new JournalMetadataQueries(JournalMetadataTable(journalConfig.journalMetadataTableConfiguration)))
    }
  }
}
