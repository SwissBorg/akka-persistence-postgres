package akka.persistence.jdbc.tag

import java.util.concurrent.ConcurrentHashMap

import akka.persistence.jdbc.db.PostgresErrorCodes
import org.postgresql.util.PSQLException
import slick.jdbc.JdbcBackend._

import scala.concurrent.{ExecutionContext, Future}

trait EventTagConverter {
  def getIdByName(name: String): Future[Int]
  def getIdByNameForce(name: String): Int
}

class EventTagDao(db: Database)(implicit ctx: ExecutionContext) extends EventTagConverter {
  val queries = new EventTagQueries()

  // TODO should we load on startup??
  // the biggest problem with cache like below is that we only store new values, so you can have memory leak, shouldn't we delete some old event?
  // maybe we should cache in different way?
  private val nameToId = new ConcurrentHashMap[String, Int]()

  db.run(queries.selectAll).foreach(_.foreach(updateCache))

  import akka.persistence.jdbc.db.Postgres11Profile.api._

  override def getIdByNameForce(name: String): Int = nameToId.get(name)

  override def getIdByName(name: String): Future[Int] = {
    Option(nameToId.get(name)) match {
      case Some(value) =>
        println(s"reading tag from cache ($name, $value)")
        Future(value)
      case None =>
        db.run(queries.selectByName(name).result.headOption)
          .flatMap {
            case Some(value) =>
              println(s"reading tag from db ($name, $value)")
              Future(value)
            case None =>
              println(s"adding tag to db ($name)")
              db.run(queries.add(EventTag(Int.MinValue, name)).transactionally).map(EventTag(_, name))
          }
          .map(updateCache)
          .map(_.id)
          .recoverWith {
            // TODO introduce circuit breaker & back off
            case ex: PSQLException if ex.getSQLState == PostgresErrorCodes.PgUniqueValidation =>
              getIdByName(name)
          }
    }
  }

  private def updateCache(eventTag: EventTag): EventTag = {
    nameToId.put(eventTag.name, eventTag.id)
    eventTag
  }
}
