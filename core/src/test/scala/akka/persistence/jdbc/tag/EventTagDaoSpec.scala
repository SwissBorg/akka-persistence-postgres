package akka.persistence.jdbc.tag

import java.util.concurrent.ThreadLocalRandom

import org.scalatest.concurrent.{ IntegrationPatience, ScalaFutures }
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{ BeforeAndAfter, BeforeAndAfterAll }
import slick.jdbc
import slick.jdbc.JdbcBackend

import scala.concurrent.{ ExecutionContext, Future }

class EventTagDaoSpec
    extends AnyFlatSpecLike
    with Matchers
    with ScalaFutures
    with BeforeAndAfterAll
    with BeforeAndAfter
    with IntegrationPatience {

  import akka.persistence.jdbc.db.ExtendedPostgresProfile.api._

  private implicit val global: ExecutionContext = ExecutionContext.global

  override def beforeAll(): Unit = {
    super.beforeAll()
//    withDB { db =>
//      val createTable =
//        sqlu"""CREATE TABLE IF NOT EXISTS event_tag (
//                            id SERIAL,
//                            name VARCHAR(255) NOT NULL,
//                            PRIMARY KEY(id)
//                            )"""
//      val nameIdx = sqlu"""CREATE UNIQUE INDEX IF NOT EXISTS event_tag_name_idx ON public.event_tag(name)"""
//      db.run(DBIO.seq(createTable, nameIdx).transactionally).futureValue
//    }
  }

  before {
    withDB { db =>
//      db.run(DBIO.seq(sqlu"""DELETE FROM event_tag WHERE name like ('dao-spec-%')""").transactionally)
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

  private def withDB(f: jdbc.JdbcBackend.Database => Unit): Unit = {
    val db: jdbc.JdbcBackend.Database = JdbcBackend.Database.forURL(
      url = "jdbc:postgresql://localhost:5432/docker",
      user = "docker",
      password = "docker",
      driver = "org.postgresql.Driver")
    try {
      f(db)
    } finally {
      db.close()
    }
  }

  private def generateTagName()(implicit position: org.scalactic.source.Position): String =
    s"dao-spec-${position.lineNumber}-${ThreadLocalRandom.current().nextInt()}"
}
