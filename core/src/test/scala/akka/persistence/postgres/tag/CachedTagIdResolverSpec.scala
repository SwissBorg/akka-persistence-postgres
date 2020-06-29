package akka.persistence.postgres.tag

import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicLong

import org.scalatest.concurrent.{ IntegrationPatience, ScalaFutures }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{ BeforeAndAfter, BeforeAndAfterAll, OptionValues }

import scala.collection.mutable
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Random
import scala.util.control.NoStackTrace

class CachedTagIdResolverSpec
    extends AnyWordSpecLike
    with Matchers
    with ScalaFutures
    with OptionValues
    with BeforeAndAfterAll
    with BeforeAndAfter
    with IntegrationPatience {

  private implicit val global: ExecutionContext = ExecutionContext.global

  "CachedTagIdResolver" when {
    "finding or adding tag name to id mapping" should {
      "return id for an existing tag" in {
        // given
        val fakeTagName = generateTagName()
        val fakeTagId = Random.nextInt()
        val dao = new FakeTagDao(findF = tagName => {
          tagName should equal(fakeTagName)
          Future.successful(Some(fakeTagId))
        }, insertF = _ => fail("Unwanted interaction with DAO (insert)"))
        val resolver = new CachedTagIdResolver(dao)

        // when
        val returnedTagIds = resolver.getOrAssignIdsFor(Set(fakeTagName)).futureValue

        // then
        returnedTagIds should contain theSameElementsAs Map(fakeTagName -> fakeTagId)
      }

      "assign id if it does not exist" in {
        // given
        val fakeTagName = generateTagName()
        val fakeTagId = Random.nextInt()
        val dao = new FakeTagDao(findF = _ => Future.successful(None), insertF = tagName => {
          tagName should equal(fakeTagName)
          Future.successful(fakeTagId)
        })
        val resolver = new CachedTagIdResolver(dao)

        // when
        val returnedTagIds = resolver.getOrAssignIdsFor(Set(fakeTagName)).futureValue

        // then
        returnedTagIds should contain theSameElementsAs Map(fakeTagName -> fakeTagId)
      }

      "hit the DAO only once and then read from cache" in {
        // given
        val fakeTagName = generateTagName()
        val fakeTagId = Random.nextInt()
        val responses =
          mutable.Stack(() => Future.successful(None), () => fail("Unwanted 2nd interaction with DAO (find)"))
        val dao = new FakeTagDao(findF = _ => responses.pop()(), insertF = tagName => {
          tagName should equal(fakeTagName)
          Future.successful(fakeTagId)
        })
        val resolver = new CachedTagIdResolver(dao)

        // when
        val firstReturnedTagIds = resolver.getOrAssignIdsFor(Set(fakeTagName)).futureValue
        val secondReturnedTagIds = resolver.getOrAssignIdsFor(Set(fakeTagName)).futureValue

        // then
        firstReturnedTagIds should contain theSameElementsAs Map(fakeTagName -> fakeTagId)
        firstReturnedTagIds should contain theSameElementsAs secondReturnedTagIds
      }

      "retry (constraint check failure caused by simultaneous inserts)" in {
        val expectedNumOfRetry = 1
        val fakeTagName = generateTagName()
        val attemptsCount = new AtomicLong(0L)
        val dao = new FakeTagDao(findF = _ => Future.successful(None), insertF = _ => {
          attemptsCount.incrementAndGet()
          Future.failed(FakeException)
        })
        val resolver = new CachedTagIdResolver(dao)

        // when
        resolver.getOrAssignIdsFor(Set(fakeTagName)).failed.futureValue

        // then
        attemptsCount.get() should equal(expectedNumOfRetry + 1)
      }

      "not hit DB if id is already cached" in {
        // given
        val fakeTagName = generateTagName()
        val fakeTagId = Random.nextInt()
        val dao = new FakeTagDao(
          findF = _ => fail("Unwanted interaction with DAO (find)"),
          insertF = _ => fail("Unwanted interaction with DAO (insert)"))
        val resolver = new CachedTagIdResolver(dao)
        resolver.cache.put(fakeTagName, Future.successful(fakeTagId))

        // when
        val returnedTagIds = resolver.getOrAssignIdsFor(Set(fakeTagName)).futureValue

        // then
        returnedTagIds should contain theSameElementsAs Map(fakeTagName -> fakeTagId)
      }

      "allow to run async multiple requests" in {
        // given
        // generate tags
        val mapOfTags = List.fill(30)((generateTagName(), Random.nextInt())).toMap
        // list of tags for which we will execute test
        val listOfTagQueries = List.fill(300)(mapOfTags.keys.toList(Random.nextInt(mapOfTags.size)))
        val dao = new FakeTagDao(
          findF = tagName => Future.successful(if (Random.nextBoolean()) Some(mapOfTags(tagName)) else None),
          insertF = tagName => Future.successful(mapOfTags(tagName)))
        val resolver = new CachedTagIdResolver(dao)

        // when
        val resolved = Future.traverse(listOfTagQueries)(tag => resolver.getOrAssignIdsFor(Set(tag))).futureValue

        // then
        val expected = listOfTagQueries.map(tagName => Map(tagName -> mapOfTags(tagName)))
        resolved should contain theSameElementsAs expected
      }
    }

    "looking up for a tag id" should {
      "return tagId read from DB" in {
        // given
        val fakeTagName = generateTagName()
        val fakeTagId = Random.nextInt()
        val dao = new FakeTagDao(findF = tagName => {
          tagName should equal(fakeTagName)
          Future.successful(Some(fakeTagId))
        }, insertF = _ => fail("Unwanted interaction with DAO (insert)"))
        val resolver = new CachedTagIdResolver(dao)

        // when
        val returnedTagId = resolver.lookupIdFor(fakeTagName).futureValue

        // then
        returnedTagId.value should equal(fakeTagId)
      }

      "return tagId read from cache" in {
        // given
        val fakeTagName = generateTagName()
        val fakeTagId = Random.nextInt()
        val dao = new FakeTagDao(
          findF = _ => fail("Unwanted interaction with DAO (find)"),
          insertF = _ => fail("Unwanted interaction with DAO (insert)"))
        val resolver = new CachedTagIdResolver(dao)
        resolver.cache.put(fakeTagName, Future.successful(fakeTagId))

        // when
        val returnedTagId = resolver.lookupIdFor(fakeTagName).futureValue

        // then
        returnedTagId.value should equal(fakeTagId)
      }

      "return None if tag id mapping is missing" in {
        // given
        val fakeTagName = generateTagName()
        val dao = new FakeTagDao(
          findF = _ => Future.successful(None),
          insertF = _ => fail("Unwanted interaction with DAO (insert)"))
        val resolver = new CachedTagIdResolver(dao)

        // when
        val returnedTagId = resolver.lookupIdFor(fakeTagName).futureValue

        // then
        returnedTagId should not be defined
      }

      "update cache" in {
        // given
        val fakeTagName = generateTagName()
        val fakeTagId = Random.nextInt()
        val dao = new FakeTagDao(
          findF = _ => Future.successful(Some(fakeTagId)),
          insertF = _ => fail("Unwanted interaction with DAO (insert)"))
        val resolver = new CachedTagIdResolver(dao)

        // then
        resolver.cache.synchronous().getIfPresent(fakeTagName) should not be defined
        resolver.lookupIdFor(fakeTagName).futureValue
        resolver.cache.synchronous().getIfPresent(fakeTagName).value should equal(fakeTagId)
      }

      "not update cache" in {
        // given
        val fakeTagName = generateTagName()
        val dao = new FakeTagDao(
          findF = _ => Future.successful(None),
          insertF = _ => fail("Unwanted interaction with DAO (insert)"))
        val resolver = new CachedTagIdResolver(dao)

        // then
        resolver.cache.synchronous().getIfPresent(fakeTagName) should not be defined
        resolver.lookupIdFor(fakeTagName).futureValue
        resolver.cache.synchronous().getIfPresent(fakeTagName) should not be defined
      }
    }
  }

  private def generateTagName()(implicit position: org.scalactic.source.Position): String =
    s"dao-spec-${position.lineNumber}-${ThreadLocalRandom.current().nextInt()}"
}

case object FakeException extends Throwable with NoStackTrace

class FakeTagDao(findF: String => Future[Option[Int]], insertF: String => Future[Int]) extends TagDao {

  override def find(tagName: String): Future[Option[Int]] = findF(tagName)

  override def insert(tagName: String): Future[Int] = insertF(tagName)

}
