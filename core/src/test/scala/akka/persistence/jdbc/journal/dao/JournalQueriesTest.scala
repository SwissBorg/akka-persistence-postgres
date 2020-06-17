package akka.persistence.jdbc.journal.dao

import akka.persistence.jdbc.SingleActorSystemPerTestSpec
import slick.lifted.RunnableCompiled

class JournalQueriesTest extends BaseQueryTest {

  behavior.of("Slick")

  it should "produce SQL query for distinct persistenceID" in withJournalQueries { queries =>
    queries.allPersistenceIdsDistinct shouldBeSQL """select distinct "persistence_id" from "journal""""
  }

  it should "create SQL query for highestMarkedSequenceNrForPersistenceId" in withJournalQueries { queries =>
    queries.highestMarkedSequenceNrForPersistenceId("aaa") shouldBeSQL """select max("sequence_number") from "journal" where ("deleted" = true) and ("persistence_id" = ?)"""
  }

  def withJournalQueries(f: JournalQueries => Unit): Unit = {
    withActorSystem { implicit system =>
      f(new JournalQueries(profile, journalConfig.journalTableConfiguration))
    }
  }
}

class BaseQueryTest extends SingleActorSystemPerTestSpec {
  import profile.api._
  implicit class SQLStringRepresentation(under: RunnableCompiled[_, _]) {
    def toSQL: String = {
      under.result.statements.toList.mkString(" ")
    }

    def shouldBeSQL(expected: String): Unit = {
      under.toSQL shouldBe expected
    }
  }
}
