package akka.persistence.postgres.tag

import com.github.blemale.scaffeine.{ AsyncLoadingCache, Scaffeine }
import slick.jdbc.JdbcBackend._

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

trait TagIdResolver {
  def getOrAssignIdsFor(tags: Set[String]): Future[Map[String, Int]]
  def lookupIdFor(name: String): Future[Option[Int]]
}

class TagDao(db: Database)(implicit ctx: ExecutionContext) extends TagIdResolver {
  private val queries = new EventTagQueries()

  // TODO configure expiration timeout and number of retries
  // TODO add support for loading many tags at once
  private val cache: AsyncLoadingCache[String, Option[Int]] =
    Scaffeine().expireAfterAccess(1.hour).buildAsyncFuture(find)

  import akka.persistence.postgres.db.ExtendedPostgresProfile.api._

  override def getOrAssignIdsFor(tags: Set[String]): Future[Map[String, Int]] = {
    cache
      .getAll(tags)
      .flatMap { ids =>
        Future.sequence {
          ids.collect {
            case (tag, None) =>
              val assignedId: Future[Int] = find(tag).flatMap {
                case None =>
                  insert(tag).recoverWith {
                    case _ =>
                      find(tag).collect {
                        case Some(id) => id
                      }
                  }
                case Some(id) => Future.successful(id)
              }
              cache.put(tag, assignedId.map(Some(_)))
              assignedId.map((tag, _))
            case (tag, Some(id)) =>
              Future.successful((tag, id))
          }
        }
      }
      .map(_.toMap)
  }

  override def lookupIdFor(tagName: String): Future[Option[Int]] =
    // TODO hit the cache but beware of cycles!
    find(tagName)

  private def find(tagName: String): Future[Option[Int]] =
    db.run(queries.selectByName(tagName).map(_.map(_.id)).result.headOption)

  private def insert(tagName: String): Future[Int] =
    db.run(queries.add(EventTag(Int.MinValue, tagName)).transactionally)

}
