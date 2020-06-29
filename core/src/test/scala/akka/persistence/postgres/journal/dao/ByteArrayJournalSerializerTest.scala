/*
 * Copyright (C) 2014 - 2019 Dennis Vriend <https://github.com/dnvriend>
 * Copyright (C) 2019 - 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.postgres
package journal.dao

import akka.persistence.journal.Tagged
import akka.persistence.postgres.journal.dao.FakeTagIdResolver.unwanted1
import akka.persistence.postgres.tag.TagIdResolver
import akka.persistence.{ AtomicWrite, PersistentRepr }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers

import scala.collection.immutable._
import scala.concurrent.Future

class ByteArrayJournalSerializerTest extends SharedActorSystemTestSpec with ScalaFutures {
  it should "serialize a serializable message and indicate whether or not the serialization succeeded" in {
    val serializer = new ByteArrayJournalSerializer(serialization, new FakeTagIdResolver())
    val result = serializer.serialize(Seq(AtomicWrite(PersistentRepr("foo"))))
    result should have size 1
    result.head.futureValue should not be empty
  }

  it should "serialize a serializable tagged message and indicate whether or not the serialization succeeded" in {
    val successfulEventTagConverter =
      new FakeTagIdResolver(getOrAssignIdsForF = tags => Future.successful(tags.zipWithIndex.toMap))
    val serializer = new ByteArrayJournalSerializer(serialization, successfulEventTagConverter)
    val result = serializer.serialize(Seq(AtomicWrite(PersistentRepr(Tagged("foo", Set("bar", "baz"))))))
    result should have size 1
    result.head.futureValue should not be empty
  }

  it should "not serialize a non-serializable message and indicate whether or not the serialization succeeded" in {
    class Test
    val serializer = new ByteArrayJournalSerializer(serialization, new FakeTagIdResolver())
    val result = serializer.serialize(Seq(AtomicWrite(PersistentRepr(new Test))))
    result should have size 1
    result.head.failed.futureValue shouldBe a[Throwable]
  }

  it should "serialize non-serializable and serializable messages and indicate whether or not the serialization succeeded" in {
    class Test
    val serializer = new ByteArrayJournalSerializer(serialization, new FakeTagIdResolver())
    val result = serializer.serialize(List(AtomicWrite(PersistentRepr(new Test)), AtomicWrite(PersistentRepr("foo"))))
    result should have size 2
    result.head.failed.futureValue shouldBe a[Throwable]
    result.last.futureValue should not be empty
  }

  it should "not serialize a serializable message a non-serializable tag(s)" in {
    val failingEventTagConverter =
      new FakeTagIdResolver(getOrAssignIdsForF = _ => Future.failed(new RuntimeException("Fake exception")))
    val serializer = new ByteArrayJournalSerializer(serialization, failingEventTagConverter)
    val result = serializer.serialize(Seq(AtomicWrite(PersistentRepr(Tagged("foo", Set("bar", "baz"))))))
    result should have size 1
    result.head.failed.futureValue shouldBe a[Throwable]
  }
}

class FakeTagIdResolver(
    getOrAssignIdsForF: Set[String] => Future[Map[String, Int]] = unwanted1("getOrAssignIdFor"),
    lookupIdForF: String => Future[Option[Int]] = unwanted1("lookupIdFor"))
    extends TagIdResolver {
  override def getOrAssignIdsFor(tags: Set[String]): Future[Map[String, Int]] = getOrAssignIdsForF(tags)

  override def lookupIdFor(name: String): Future[Option[Int]] = lookupIdForF(name)
}

object FakeTagIdResolver {
  private[FakeTagIdResolver] def unwanted1(methodName: String): Any => Nothing =
    arg => Matchers.fail(s"Unwanted interaction with EventTagConverter.$methodName($arg)")
}
