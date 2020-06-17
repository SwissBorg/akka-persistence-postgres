package akka.persistence.jdbc.util

import akka.persistence.jdbc.SingleActorSystemPerTestSpec
import slick.lifted.RunnableCompiled

class BaseQueryTest extends SingleActorSystemPerTestSpec {
  import profile.api._
  implicit class SQLStringMatcherRunnableCompiled(under: RunnableCompiled[_, _]) {
    def toSQL: String = {
      under.result.toSQL
    }

    def shouldBeSQL(expected: String): Unit = {
      under.toSQL shouldBe expected
    }
  }
  implicit class SQLStringMatcherProfileAction(under: profile.ProfileAction[_, _, _]) {

    def toSQL: String = {
      under.statements.toList.mkString(" ")
    }

    def shouldBeSQL(expected: String): Unit = {
      under.toSQL shouldBe expected
    }
  }
}
