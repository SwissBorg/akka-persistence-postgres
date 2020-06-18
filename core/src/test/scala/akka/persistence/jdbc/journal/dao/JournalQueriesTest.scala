package akka.persistence.jdbc.journal.dao

import akka.persistence.jdbc.JournalRow
import akka.persistence.jdbc.util.BaseQueryTest

class JournalQueriesTest extends BaseQueryTest {
  import profile.api._

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

  it should "create SQL query for selectDeleteByPersistenceIdAndMaxSequenceNumber" in withJournalQueries { queries =>
    queries.selectDeleteByPersistenceIdAndMaxSequenceNumber("aaa", 11L) shouldBeSQL """select "deleted" from "journal" where (("persistence_id" = ?) and ("sequence_number" <= ?)) and ("deleted" = false)"""
  }

  it should "create SQL query for selectDeleteByPersistenceIdAndMaxSequenceNumber.update" in withJournalQueries { queries =>
    queries.selectDeleteByPersistenceIdAndMaxSequenceNumber("aaa", 11L).update(true) shouldBeSQL """update "journal" set "deleted" = ? where (("journal"."persistence_id" = ?) and ("journal"."sequence_number" <= ?)) and ("journal"."deleted" = false)"""
  }

  it should "create SQL query for selectDeleteByPersistenceIdAndMaxSequenceNumber.delete" in withJournalQueries { queries =>
    queries.selectDeleteByPersistenceIdAndMaxSequenceNumber("aaa", 11L).delete shouldBeSQL """delete from "journal" where (("journal"."persistence_id" = ?) and ("journal"."sequence_number" <= ?)) and ("journal"."deleted" = false)"""
  }

  it should "create SQL query for selectByPersistenceIdAndSequenceNumber" in withJournalQueries { queries =>
    queries.selectMessageByPersistenceIdAndSequenceNumber("aaa", 11L) shouldBeSQL """select "message" from "journal" where ("persistence_id" = ?) and ("sequence_number" = ?)"""
  }

  it should "create SQL query for selectByPersistenceIdAndSequenceNumber.update" in withJournalQueries { queries =>
    queries.selectMessageByPersistenceIdAndSequenceNumber("aaa", 11L).update(Array.ofDim(0)) shouldBeSQL """update "journal" set "message" = ? where ("journal"."persistence_id" = ?) and ("journal"."sequence_number" = ?)"""
  }

  it should "create SQL query for writeJournalRows" in withJournalQueries { queries =>
    val row = JournalRow(1L, deleted = false, "p", 3L, Array.ofDim(0), Option("tag"))
    queries.writeJournalRows(Seq(row, row, row)) shouldBeSQL """insert into "journal" ("deleted","persistence_id","sequence_number","message","tags")  values (?,?,?,?,?)"""
  }

  private def withJournalQueries(f: JournalQueries => Unit): Unit = {
    withActorSystem { implicit system =>
      f(new JournalQueries(profile, journalConfig.journalTableConfiguration))
    }
  }
}
