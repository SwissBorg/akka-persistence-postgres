package akka.persistence.postgres.tag

import java.util.concurrent.ThreadLocalRandom

import akka.actor.ActorSystem
import akka.persistence.postgres.config.{JournalConfig, SlickConfiguration}
import akka.persistence.postgres.db.SlickDatabase
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}
import slick.jdbc
import slick.jdbc.JdbcBackend

import scala.concurrent.{ExecutionContext, Future}

class EventTagDaoSpec
    extends AnyFlatSpecLike
    with Matchers
    with ScalaFutures
    with BeforeAndAfterAll
    with BeforeAndAfter
    with IntegrationPatience {

  import akka.persistence.postgres.db.ExtendedPostgresProfile.api._

  private implicit val global: ExecutionContext = ExecutionContext.global

  before {
    withDB { db =>
      db.run(DBIO.seq(sqlu"""TRUNCATE event_tag""").transactionally)
    }
  }

  it should "return id of existing tag" in withConnection { dao =>
    // given
    val tagName = generateTagName()
    val expectedTagId = dao.getIdByName(tagName).futureValue
    // when
    val returnedTagId = dao.getIdByName(tagName).futureValue
    // then
    expectedTagId shouldBe returnedTagId
  }

  it should "allow to run async multiple requests" in withConnection { dao =>
    // given
    // generate tags
    val listOfTags = List.fill(30)(generateTagName())
    // list of tags for which we will execute test
    val listOfTagQueries = List.fill(300)(listOfTags(ThreadLocalRandom.current().nextInt(listOfTags.size)))

    // when
    val stored = Future.traverse(listOfTagQueries)(tag => dao.getIdByName(tag).map((_, tag))).futureValue

    // then
    // take ids of tagsName
    val expected = Future.sequence(listOfTags.map(tag => dao.getIdByName(tag).map((tag, _)))).map(_.toMap).futureValue
    stored.foreach {
      case (id, name) =>
        expected(name) shouldBe id
    }
  }

  it should "allow to run async multiple daos" in withDB { db =>
    // given
    // generate tags
    val listOfTags = List.fill(30)(generateTagName())
    // list of tags for which we will execute test
    val listOfTagQueries = List.fill(300)(listOfTags(ThreadLocalRandom.current().nextInt(listOfTags.size)))

    // when
    val stored =
      Future.traverse(listOfTagQueries)(tag => new EventTagDao(db).getIdByName(tag).map((_, tag))).futureValue

    // then
    // take ids of tagsName
    val expected = Future
      .sequence(listOfTags.map(tag => new EventTagDao(db).getIdByName(tag).map((tag, _))))
      .map(_.toMap)
      .futureValue
    stored.foreach {
      case (id, name) =>
        expected(name) shouldBe id
    }
  }

  private def withConnection(f: EventTagConverter => Unit): Unit =
    withDB { db =>
      val dao = new EventTagDao(db)
      f(dao)
    }

  lazy val journalConfig: Config = {
    val globalConfig = ConfigFactory.load("plain-application.conf")
    globalConfig.getConfig("jdbc-journal")
  }
  lazy val slickConfig: SlickConfiguration = new SlickConfiguration(journalConfig.getConfig("slick"))

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
