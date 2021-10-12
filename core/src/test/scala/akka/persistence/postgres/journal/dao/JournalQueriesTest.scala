package akka.persistence.postgres.journal.dao

import akka.persistence.postgres.JournalRow
import akka.persistence.postgres.util.BaseQueryTest
import io.circe.{ Json, JsonObject }

class JournalQueriesTest extends BaseQueryTest {

  it should "create SQL query for highestMarkedSequenceNrForPersistenceId" in withJournalQueries { queries =>
    queries.highestMarkedSequenceNrForPersistenceId(
      "aaa") shouldBeSQL """select max("sequence_number") from "journal" where ("deleted" = true) and ("persistence_id" = ?)"""
  }

  it should "create SQL query for highestSequenceNrForPersistenceId" in withJournalQueries { queries =>
    queries.highestSequenceNrForPersistenceId(
      "aaa") shouldBeSQL """select "max_sequence_number" from "journal_persistence_ids" where "persistence_id" = ? limit 1"""
  }

  it should "create SQL query for messagesQuery" in withJournalQueries { queries =>
    queries.messagesQuery(
      "aaa",
      11L,
      11L,
      11L) shouldBeSQL """select "ordering", "deleted", "persistence_id", "sequence_number", "message", "tags", "metadata" from "journal" where ((("persistence_id" = ?) and ("deleted" = false)) and ("sequence_number" >= ?)) and ("sequence_number" <= ?) order by "sequence_number" limit ?"""
  }

  it should "create SQL query for markJournalMessagesAsDeleted" in withJournalQueries { queries =>
    queries.markJournalMessagesAsDeleted(
      "aaa",
      11L) shouldBeSQL """update "journal" set "deleted" = ? where (("journal"."persistence_id" = 'aaa') and ("journal"."sequence_number" <= 11)) and ("journal"."deleted" = false)"""
  }

  it should "create SQL query for update" in withJournalQueries { queries =>
    queries.update(
      "aaa",
      11L,
      Array.ofDim(0),
      emptyJson) shouldBeSQL """update "journal" set "message" = ?, "metadata" = ? where ("journal"."persistence_id" = 'aaa') and ("journal"."sequence_number" = 11)"""
  }

  it should "create SQL query for delete" in withJournalQueries { queries =>
    queries.delete(
      "aaa",
      11L) shouldBeSQL """delete from "journal" where ("journal"."persistence_id" = 'aaa') and ("journal"."sequence_number" <= 11)"""
  }

  it should "create SQL query for writeJournalRows" in withJournalQueries { queries =>
    val row = JournalRow(1L, deleted = false, "p", 3L, Array.ofDim(0), List(1, 2, 3), emptyJson)
    queries.writeJournalRows(
      Seq(row, row, row)) shouldBeSQL """insert into "journal" ("deleted","persistence_id","sequence_number","message","tags","metadata")  values (?,?,?,?,?,?)"""
  }

  private lazy val emptyJson = Json.fromJsonObject(JsonObject.empty)

  private def withJournalQueries(f: JournalQueries => Unit): Unit = {
    withActorSystem { implicit system =>
      f(
        new JournalQueries(
          FlatJournalTable.apply(journalConfig.journalTableConfiguration),
          JournalPersistenceIdsTable.apply(journalConfig.journalPersistenceIdsTableConfiguration)))
    }
  }
}
