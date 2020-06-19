package akka.persistence.jdbc.util

import akka.persistence.jdbc.SingleActorSystemPerTestSpec
import akka.persistence.jdbc.db.ExtendedPostgresProfile
import slick.lifted.RunnableCompiled

class BaseQueryTest extends SingleActorSystemPerTestSpec {
  import akka.persistence.jdbc.db.ExtendedPostgresProfile.api._
  implicit class SQLStringMatcherRunnableCompiled(under: RunnableCompiled[_, _]) {
    def toSQL: String = {
      under.result.toSQL
    }

    def shouldBeSQL(expected: String): Unit = {
      under.toSQL shouldBe expected
    }
  }
  implicit class SQLStringMatcherProfileAction(under: ExtendedPostgresProfile.ProfileAction[_, _, _]) {

    def toSQL: String = {
      under.statements.toList.mkString(" ")
    }

    def shouldBeSQL(expected: String): Unit = {
      under.toSQL shouldBe expected
    }
  }
}
