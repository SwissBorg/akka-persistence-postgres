package akka.persistence.postgres.tag

import akka.persistence.postgres.config.TagsConfig
import com.github.blemale.scaffeine.{ AsyncLoadingCache, Scaffeine }

import scala.concurrent.{ ExecutionContext, Future }

trait TagIdResolver {
  def getOrAssignIdsFor(tags: Set[String]): Future[Map[String, Int]]
  def lookupIdFor(name: String): Future[Option[Int]]
}

class CachedTagIdResolver(dao: TagDao, config: TagsConfig)(implicit ctx: ExecutionContext) extends TagIdResolver {

  import akka.persistence.postgres.tag.CachedTagIdResolver.LookupResult
  import LookupResult._

  // TODO add support for loading many tags at once
  // Package private - for testing purposes
  private[tag] val cache: AsyncLoadingCache[String, LookupResult] =
    Scaffeine()
      .expireAfterAccess(config.cacheTtl)
      .buildAsyncFuture(findOrInsert(_, config.insertionRetryAttempts).map(Present))

  private def findOrInsert(tagName: String, retryAttempts: Int): Future[Int] =
    dao.find(tagName).flatMap {
      case Some(id) => Future.successful(id)
      case None =>
        dao.insert(tagName).recoverWith {
          case _ if retryAttempts > 0 => findOrInsert(tagName, retryAttempts - 1)
        }
    }

  override def getOrAssignIdsFor(tags: Set[String]): Future[Map[String, Int]] =
    cache
      .getAll(tags)
      .map(_.map {
        case (tagName, Present(tagId)) => (tagName, tagId)
        case (_, NotFound)             => throw new IllegalStateException("Ooops! This should never happen.")
      })

  override def lookupIdFor(tagName: String): Future[Option[Int]] =
    Future.sequence(cache.getIfPresent(tagName).toList).map(_.headOption).flatMap {
      case Some(Present(tagId)) => Future.successful(Some(tagId))
      case _ =>
        val findRes = dao.find(tagName)
        cache.put(tagName, findRes.map(_.fold[LookupResult](NotFound)(Present)))
        findRes
    }
}

private[tag] object CachedTagIdResolver {
  // This (internal) ADT was introduced to avoid confusion with nested `Option`s and make the code more readable
  sealed trait LookupResult
  object LookupResult {

    case object NotFound extends LookupResult

    case class Present(tagId: Int) extends LookupResult

  }
}
