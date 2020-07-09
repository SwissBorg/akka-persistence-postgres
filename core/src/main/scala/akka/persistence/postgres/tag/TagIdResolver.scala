package akka.persistence.postgres.tag

import akka.persistence.postgres.config.TagsConfig
import com.github.blemale.scaffeine.{ AsyncLoadingCache, Scaffeine }

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }

trait TagIdResolver {
  def getOrAssignIdsFor(tags: Set[String]): Future[Map[String, Int]]
  def lookupIdFor(name: String): Future[Option[Int]]
}

class CachedTagIdResolver(dao: TagDao, config: TagsConfig)(implicit ctx: ExecutionContext) extends TagIdResolver {

  // TODO add support for loading many tags at once
  // Package private - for testing purposes
  private[tag] val cache: AsyncLoadingCache[String, Int] =
    Scaffeine().expireAfterAccess(config.cacheTtl).buildAsyncFuture(findOrInsert(_, config.insertionRetryAttempts))

  private def findOrInsert(tagName: String, retryAttempts: Int): Future[Int] =
    dao.find(tagName).flatMap {
      case Some(id) => Future.successful(id)
      case None =>
        dao.insert(tagName).recoverWith {
          case _ if retryAttempts > 0 => findOrInsert(tagName, retryAttempts - 1)
        }
    }

  override def getOrAssignIdsFor(tags: Set[String]): Future[Map[String, Int]] =
    cache.getAll(tags)

  override def lookupIdFor(tagName: String): Future[Option[Int]] =
    cache.getFuture(tagName, dao.find(_).map(_.get)).map(Some(_)).recover {
      case _: NoSuchElementException => None
    }
}
