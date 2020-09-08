package akka.persistence.postgres.query.dao

import akka.persistence.postgres.util.BaseQueryTest

class ReadJournalQueriesTest extends BaseQueryTest {

  it should "create SQL query for allPersistenceIdsDistinct" in withReadJournalQueries { queries =>
    queries.allPersistenceIdsDistinct(23L) shouldBeSQL """select distinct "persistence_id" from "journal" limit ?"""
  }

  it should "create SQL query for messagesQuery" in withReadJournalQueries { queries =>
    queries.messagesQuery("p1", 1L, 4L, 5L) shouldBeSQL """select "ordering", "deleted", "persistence_id", "sequence_number", "message", "tags" from "journal" where (("persistence_id" = ?) and ("sequence_number" >= ?)) and ("sequence_number" <= ?) order by "sequence_number" limit ?"""
  }

  it should "create SQL query for eventsByTag" in withReadJournalQueries { queries =>
    queries.eventsByTag(List(11), 23L, 25L) shouldBeSQL """select "ordering", "deleted", "persistence_id", "sequence_number", "message", "tags" from "journal" where ("tags" @> ?) and (("ordering" > ?) and ("ordering" <= ?)) order by "ordering""""
  }

  it should "create SQL query for journalSequenceQuery" in withReadJournalQueries { queries =>
    queries.orderingByOrdering(11L, 23L) shouldBeSQL """select "ordering" from "journal" where "ordering" > ? order by "ordering" limit ?"""
  }

  it should "create SQL query for maxJournalSequenceQuery" in withReadJournalQueries { queries =>
    queries.maxOrdering shouldBeSQL """select max("ordering") from "journal""""
  }

  private def withReadJournalQueries(f: ReadJournalQueries => Unit): Unit = {
    withActorSystem { implicit system =>
      f(new ReadJournalQueries(readJournalConfig))
    }
  }
}
