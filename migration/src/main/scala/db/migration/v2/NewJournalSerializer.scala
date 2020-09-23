package db.migration.v2

import akka.persistence.PersistentRepr
import akka.persistence.postgres.serialization.FlowPersistentReprSerializer
import akka.persistence.postgres.tag.TagIdResolver
import akka.serialization.{ Serialization, Serializers }
import db.migration.v2.NewJournalSerializer.Metadata
import io.circe.{ Decoder, Encoder }

import scala.collection.immutable._
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

class NewJournalSerializer(serialization: Serialization, tagConverter: TagIdResolver)(
    implicit val executionContext: ExecutionContext)
    extends FlowPersistentReprSerializer[NewJournalRow] {

  override def serialize(persistentRepr: PersistentRepr, tags: Set[String]): Future[NewJournalRow] = {
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
        Metadata(serId, serManifest, persistentRepr.manifest, persistentRepr.writerUuid, persistentRepr.timestamp)
      NewJournalRow(
        Long.MinValue,
        persistentRepr.deleted,
        persistentRepr.persistenceId,
        persistentRepr.sequenceNr,
        serializedEvent,
        convertedTags.toList,
        meta.asJson)
    }
  }

  override def deserialize(journalRow: NewJournalRow): Try[(PersistentRepr, Long)] =
    for {
      metadata <- journalRow.metadata.as[Metadata].toTry
      e <- serialization.deserialize(journalRow.message, metadata.serId, metadata.serManifest)
    } yield {
      (
        PersistentRepr(
          e,
          journalRow.sequenceNumber,
          journalRow.persistenceId,
          metadata.eventManifest,
          // not used, marked as deprecated (https://github.com/akka/akka/issues/27278)
          deleted = false,
          // not used, marked as deprecated (https://github.com/akka/akka/issues/27278
          sender = null,
          metadata.writerUuid).withTimestamp(metadata.timestamp),
        journalRow.ordering)
    }

}

object NewJournalSerializer {
  case class Metadata(serId: Int, serManifest: String, eventManifest: String, writerUuid: String, timestamp: Long)

  object Metadata {
    implicit val encoder: Encoder[Metadata] =
      Encoder.forProduct5("serId", "serManifest", "eventManifest", "writerUuid", "timestamp")(e =>
        (e.serId, e.serManifest, e.eventManifest, e.writerUuid, e.timestamp))
    implicit val decoder: Decoder[Metadata] =
      Decoder.forProduct5("serId", "serManifest", "eventManifest", "writerUuid", "timestamp")(Metadata.apply)
  }
}
