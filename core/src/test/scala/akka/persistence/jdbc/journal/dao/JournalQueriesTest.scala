package akka.persistence.jdbc.journal.dao

import akka.persistence.jdbc.JournalRow
import akka.persistence.jdbc.util.BaseQueryTest

class JournalQueriesTest extends BaseQueryTest {

  it should "produce SQL query for distinct persistenceID" in withJournalQueries { queries =>
    queries.allPersistenceIdsDistinct shouldBeSQL """select distinct "persistence_id" from "journal""""
  }

  it should "create SQL query for highestMarkedSequenceNrForPersistenceId" in withJournalQueries { queries =>
    queries.highestMarkedSequenceNrForPersistenceId("aaa") shouldBeSQL """select max("sequence_number") from "journal" where ("deleted" = true) and ("persistence_id" = ?)"""
  }

  it should "create SQL query for highestSequenceNrForPersistenceId" in withJournalQueries { queries =>
    queries.highestSequenceNrForPersistenceId("aaa") shouldBeSQL """select max("sequence_number") from "journal" where "persistence_id" = ?"""
  }

  it should "create SQL query for selectByPersistenceIdAndMaxSequenceNumber" in withJournalQueries { queries =>
    queries.selectByPersistenceIdAndMaxSequenceNumber("aaa", 11L) shouldBeSQL """select "ordering", "deleted", "persistence_id", "sequence_number", "message", "tags" from "journal" where ("persistence_id" = ?) and ("sequence_number" <= ?) order by "sequence_number" desc"""
  }

  it should "create SQL query for messagesQuery" in withJournalQueries { queries =>
    queries.messagesQuery("aaa", 11L, 11L, 11L) shouldBeSQL """select "ordering", "deleted", "persistence_id", "sequence_number", "message", "tags" from "journal" where ((("persistence_id" = ?) and ("deleted" = false)) and ("sequence_number" >= ?)) and ("sequence_number" <= ?) order by "sequence_number" limit ?"""
  }

  it should "create SQL query for markJournalMessagesAsDeleted" in withJournalQueries { queries =>
    queries.markJournalMessagesAsDeleted("aaa", 11L) shouldBeSQL """update "journal" set "deleted" = ? where (("journal"."persistence_id" = 'aaa') and ("journal"."sequence_number" <= 11)) and ("journal"."deleted" = false)"""
  }

  it should "create SQL query for update" in withJournalQueries { queries =>
    queries.update("aaa", 11L, Array.ofDim(0)) shouldBeSQL """update "journal" set "message" = ? where ("journal"."persistence_id" = 'aaa') and ("journal"."sequence_number" = 11)"""
  }

  it should "create SQL query for delete" in withJournalQueries { queries =>
    queries.delete("aaa", 11L) shouldBeSQL """delete from "journal" where ("journal"."persistence_id" = 'aaa') and ("journal"."sequence_number" <= 11)"""
  }

  it should "create SQL query for writeJournalRows" in withJournalQueries { queries =>
    val row = JournalRow(1L, deleted = false, "p", 3L, Array.ofDim(0), List(1,2,3))
    queries.writeJournalRows(Seq(row, row, row)) shouldBeSQL """insert into "journal" ("deleted","persistence_id","sequence_number","message","tags")  values (?,?,?,?,?)"""
  }

  private def withJournalQueries(f: JournalQueries => Unit): Unit = {
    withActorSystem { implicit system =>
      f(new JournalQueries(journalConfig.journalTableConfiguration))
    }
  }
}
