/*
 * Copyright (C) 2014 - 2019 Dennis Vriend <https://github.com/dnvriend>
 * Copyright (C) 2019 - 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.jdbc
package journal.dao

import akka.persistence.jdbc.tag.EventTagConverter
import akka.persistence.{ AtomicWrite, PersistentRepr }

import scala.collection.immutable._
import scala.concurrent.Future

class ByteArrayJournalSerializerTest extends SharedActorSystemTestSpec {
  it should "serialize a serializable message and indicate whether or not the serialization succeeded" in {
    val serializer = new ByteArrayJournalSerializer(serialization, FakeEventTagConverter)
    val result = serializer.serialize(Seq(AtomicWrite(PersistentRepr("foo"))))
    result should have size 1
    (result.head should be).a(Symbol("success"))
  }

  it should "not serialize a non-serializable message and indicate whether or not the serialization succeeded" in {
    class Test
    val serializer = new ByteArrayJournalSerializer(serialization, FakeEventTagConverter)
    val result = serializer.serialize(Seq(AtomicWrite(PersistentRepr(new Test))))
    result should have size 1
    (result.head should be).a(Symbol("failure"))
  }

  it should "serialize non-serializable and serializable messages and indicate whether or not the serialization succeeded" in {
    class Test
    val serializer = new ByteArrayJournalSerializer(serialization, FakeEventTagConverter)
    val result = serializer.serialize(List(AtomicWrite(PersistentRepr(new Test)), AtomicWrite(PersistentRepr("foo"))))
    result should have size 2
    (result.head should be).a(Symbol("failure"))
    (result.last should be).a(Symbol("success"))
  }
}

object FakeEventTagConverter extends EventTagConverter {
  override def getIdByName(name: String): Future[Int] = Future.successful(0)

  override def getIdByNameForce(name: String): Int = 0
}
