/*
 * Copyright (C) 2014 - 2019 Dennis Vriend <https://github.com/dnvriend>
 * Copyright (C) 2019 - 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.postgres
package journal.dao

import java.nio.charset.Charset
import java.time.{ LocalDateTime, ZoneOffset }
import java.util.UUID

import akka.persistence.journal.Tagged
import akka.persistence.postgres.journal.dao.ByteArrayJournalSerializer.Metadata
import akka.persistence.postgres.journal.dao.FakeTagIdResolver.unwanted1
import akka.persistence.postgres.tag.TagIdResolver
import akka.persistence.{ AtomicWrite, PersistentRepr }
import akka.serialization.Serializers
import io.circe.Json
import org.scalatest.EitherValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers

import scala.collection.immutable._
import scala.concurrent.Future

class ByteArrayJournalSerializerTest extends SharedActorSystemTestSpec with ScalaFutures with EitherValues {
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

  it should "not serialize a serializable message tagged with a non-serializable tag(s)" in {
    val failingEventTagConverter =
      new FakeTagIdResolver(getOrAssignIdsForF = _ => Future.failed(new RuntimeException("Fake exception")))
    val serializer = new ByteArrayJournalSerializer(serialization, failingEventTagConverter)
    val result = serializer.serialize(Seq(AtomicWrite(PersistentRepr(Tagged("foo", Set("bar", "baz"))))))
    result should have size 1
    result.head.failed.futureValue shouldBe a[Throwable]
  }

  it should "serialize metadata" in {
    val serializer = new ByteArrayJournalSerializer(serialization, new FakeTagIdResolver())
    val payload = "foo"
    val repr = PersistentRepr(payload)
    val result = serializer.serialize(Seq(AtomicWrite(repr)))
    val serializedRows = result.head.futureValue
    serializedRows should have size 1

    val meta = serializedRows.head.metadata.as[Metadata].right.value
    val payloadSer = serialization.serializerFor(payload.getClass)
    meta should equal {
      Metadata(
        serId = payloadSer.identifier,
        serManifest = Serializers.manifestFor(payloadSer, payload),
        eventManifest = repr.manifest,
        writerUuid = repr.writerUuid,
        timestamp = repr.timestamp)
    }
  }

  it should "deserialize metadata" in {
    val serializer = new ByteArrayJournalSerializer(serialization, new FakeTagIdResolver())

    val payload = "foo"
    val payloadSer = serialization.serializerFor(payload.getClass)
    val serId = payloadSer.identifier
    val serManifest = Serializers.manifestFor(payloadSer, payload)
    val eventManifest = "event manifest"
    val writerUuid = UUID.randomUUID().toString
    val timestamp = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC)

    val meta = Json.fromFields(
      List(
        "serId" -> Json.fromLong(serId),
        "serManifest" -> Json.fromString(serManifest),
        "eventManifest" -> Json.fromString(eventManifest),
        "writerUuid" -> Json.fromString(writerUuid),
        "timestamp" -> Json.fromLong(timestamp)))
    val row = JournalRow(1L, false, "my-7", 2137L, payload.getBytes(Charset.forName("UTF-8")), Nil, meta)

    val result = serializer.deserialize(row)

    val (repr, ordering) = result.success.value

    ordering should equal(1L)
    repr should equal {
      PersistentRepr(payload, 2137L, "my-7", eventManifest, false, null, writerUuid)
    }
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
