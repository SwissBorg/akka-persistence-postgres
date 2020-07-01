package akka.persistence.postgres.tag

import java.util.concurrent.ThreadLocalRandom

import akka.persistence.postgres.config.{ SlickConfiguration, TagsTableConfiguration }
import akka.persistence.postgres.db.SlickDatabase
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.concurrent.{ IntegrationPatience, ScalaFutures }
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{ BeforeAndAfter, BeforeAndAfterAll, OptionValues }
import slick.jdbc

import scala.concurrent.ExecutionContext

class SimpleTagDaoSpec
    extends AnyFlatSpecLike
    with Matchers
    with ScalaFutures
    with OptionValues
    with BeforeAndAfterAll
    with BeforeAndAfter
    with IntegrationPatience {

  import akka.persistence.postgres.db.ExtendedPostgresProfile.api._

  private implicit val global: ExecutionContext = ExecutionContext.global

  before {
    withDB { db =>
      db.run(sqlu"""TRUNCATE tags""".transactionally).futureValue
    }
  }

  it should "return id of existing tag" in withDB { db =>
    // given
    val dao = new SimpleTagDao(db, tagTableConfig)
    val tagName = "predefined"
    db.run(sqlu"""INSERT INTO tags (name) VALUES ('#$tagName')""".transactionally).futureValue

    // when
    val returnedTagId = dao.find(tagName).futureValue
    // then
    returnedTagId shouldBe defined
  }

  it should "return None if tag cannot be found" in withDao { dao =>
    // given
    val tagName = "non-existing"

    // when
    val returnedTagId = dao.find(tagName).futureValue
    // then
    returnedTagId should not be defined
  }

  it should "return id of created tag" in withDao { dao =>
    // given
    val tagName = generateTagName()
    val tagId = dao.insert(tagName).futureValue

    // when
    val returnedTagId = dao.find(tagName).futureValue
    // then
    returnedTagId.value should equal(tagId)
  }

  private def withDao(f: TagDao => Unit): Unit =
    withDB { db =>
      val dao = new SimpleTagDao(db, tagTableConfig)
      f(dao)
    }

  lazy val journalConfig: Config = {
    val globalConfig = ConfigFactory.load("plain-application.conf")
    globalConfig.getConfig("jdbc-journal")
  }
  lazy val slickConfig: SlickConfiguration = new SlickConfiguration(journalConfig.getConfig("slick"))
  lazy val tagTableConfig: TagsTableConfiguration = new TagsTableConfiguration(ConfigFactory.empty)

  private def withDB(f: jdbc.JdbcBackend.Database => Unit): Unit = {
    lazy val db = SlickDatabase.database(journalConfig, slickConfig, "slick.db")
    try {
      f(db)
    } finally {
      db.close()
    }
  }

  private def generateTagName()(implicit position: org.scalactic.source.Position): String =
    s"dao-spec-${position.lineNumber}-${ThreadLocalRandom.current().nextInt()}"
}
