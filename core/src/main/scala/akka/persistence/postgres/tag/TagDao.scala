package akka.persistence.postgres.tag

import slick.jdbc.JdbcBackend.Database

import scala.concurrent.{ ExecutionContext, Future }

trait TagDao {

  def find(tagName: String): Future[Option[Int]]

  def insert(tagName: String): Future[Int]
}

class SimpleTagDao(db: Database)(implicit ec: ExecutionContext) extends TagDao {
  import akka.persistence.postgres.db.ExtendedPostgresProfile.api._

  private val queries = new EventTagQueries()

  def find(tagName: String): Future[Option[Int]] =
    db.run(queries.selectByName(tagName).map(_.map(_.id)).result.headOption)

  def insert(tagName: String): Future[Int] =
    db.run(queries.add(EventTag(Int.MinValue, tagName)).transactionally)
}
