/*
 * Copyright (C) 2014 - 2019 Dennis Vriend <https://github.com/dnvriend>
 * Copyright (C) 2019 - 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.postgres
package journal.dao

import akka.persistence.PersistentRepr
import akka.persistence.postgres.serialization.FlowPersistentReprSerializer
import akka.persistence.postgres.tag.EventTagConverter
import akka.serialization.Serialization

import scala.collection.immutable._
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

class ByteArrayJournalSerializer(serialization: Serialization, tagConverter: EventTagConverter)(
    implicit val executionContext: ExecutionContext)
    extends FlowPersistentReprSerializer[JournalRow] {

  override def serialize(persistentRepr: PersistentRepr, tags: Set[String]): Future[JournalRow] = {
    val convertedTagsFut = Future.sequence(tags.map(tagConverter.getIdByName))
    val serializedEventFut: Future[Array[Byte]] = Future.fromTry(serialization.serialize(persistentRepr))
    for {
      convertedTags <- convertedTagsFut
      serializedEvent <- serializedEventFut
    } yield {
      JournalRow(
        Long.MinValue,
        persistentRepr.deleted,
        persistentRepr.persistenceId,
        persistentRepr.sequenceNr,
        serializedEvent,
        convertedTags.toList)
    }
  }

  override def deserialize(journalRow: JournalRow): Try[(PersistentRepr, Long)] =
    serialization.deserialize(journalRow.message, classOf[PersistentRepr]).map((_, journalRow.ordering))

}
