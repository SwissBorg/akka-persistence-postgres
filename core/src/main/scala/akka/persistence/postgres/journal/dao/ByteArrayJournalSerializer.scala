/*
 * Copyright (C) 2014 - 2019 Dennis Vriend <https://github.com/dnvriend>
 * Copyright (C) 2019 - 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.postgres
package journal.dao

import akka.persistence.PersistentRepr
import akka.persistence.postgres.journal.dao.ByteArrayJournalSerializer.Metadata
import akka.persistence.postgres.serialization.FlowPersistentReprSerializer
import akka.persistence.postgres.tag.TagIdResolver
import akka.serialization.{ Serialization, Serializers }
import io.circe.{ Decoder, Encoder }

import scala.collection.immutable._
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

class ByteArrayJournalSerializer(serialization: Serialization, tagConverter: TagIdResolver)(
    implicit val executionContext: ExecutionContext)
    extends FlowPersistentReprSerializer[JournalRow] {

  override def serialize(persistentRepr: PersistentRepr, tags: Set[String]): Future[JournalRow] = {
    import io.circe.syntax._
    val convertedTagsFut = {
      if (tags.nonEmpty) tagConverter.getOrAssignIdsFor(tags).map(_.values)
      else Future.successful(Nil)
    }
    val payload: AnyRef = persistentRepr.payload.asInstanceOf[AnyRef]
    val serializedEventFut: Future[Array[Byte]] = Future.fromTry(serialization.serialize(payload))
    for {
      serializer <- Future.fromTry(Try(serialization.findSerializerFor(payload)))
      convertedTags <- convertedTagsFut
      serializedEvent <- serializedEventFut
    } yield {
      val serId = serializer.identifier
      val serManifest = Serializers.manifestFor(serializer, payload)
      val meta =
        Metadata(
          serId,
          Option(serManifest).filterNot(_.isBlank),
          Option(persistentRepr.manifest).filterNot(_.isBlank),
          persistentRepr.writerUuid,
          persistentRepr.timestamp)
      JournalRow(
        Long.MinValue,
        persistentRepr.deleted,
        persistentRepr.persistenceId,
        persistentRepr.sequenceNr,
        serializedEvent,
        convertedTags.toList,
        meta.asJson)
    }
  }

  override def deserialize(journalRow: JournalRow): Try[(PersistentRepr, Long)] =
    for {
      metadata <- journalRow.metadata.as[Metadata].toTry
      e <- serialization.deserialize(journalRow.message, metadata.serId, metadata.serManifest.getOrElse(""))
    } yield {
      (
        PersistentRepr(
          e,
          journalRow.sequenceNumber,
          journalRow.persistenceId,
          metadata.eventManifest.getOrElse(""),
          // not used, marked as deprecated (https://github.com/akka/akka/issues/27278)
          deleted = false,
          // not used, marked as deprecated (https://github.com/akka/akka/issues/27278
          sender = null,
          metadata.writerUuid).withTimestamp(metadata.timestamp),
        journalRow.ordering)
    }

}

object ByteArrayJournalSerializer {
  case class Metadata(
      serId: Int,
      serManifest: Option[String],
      eventManifest: Option[String],
      writerUuid: String,
      timestamp: Long)

  object Metadata {
    implicit val encoder: Encoder[Metadata] = Encoder
      .forProduct5[Metadata, Int, Option[String], Option[String], String, Long]("sid", "sm", "em", "wid", "t") { e =>
        (e.serId, e.serManifest, e.eventManifest, e.writerUuid, e.timestamp)
      }
      .mapJson(_.dropNullValues)

    implicit val decoder: Decoder[Metadata] =
      Decoder.forProduct5("sid", "sm", "em", "wid", "t")(Metadata.apply).or {
        Decoder.forProduct5("serId", "serManifest", "eventManifest", "writerUuid", "timestamp")(Metadata.apply)
      }
  }
}
