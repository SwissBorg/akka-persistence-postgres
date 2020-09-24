package db.migration.v2

import akka.persistence.PersistentRepr
import akka.serialization.{Serialization, Serializers}
import db.migration.v2.NewJournalSerializer.Metadata
import io.circe.{Encoder, Json}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class NewJournalSerializer(serialization: Serialization)(
    implicit val executionContext: ExecutionContext) {

  def serialize(persistentRepr: PersistentRepr): Future[(Array[Byte], Json)] = {
    import io.circe.syntax._
    val payload: AnyRef = persistentRepr.payload.asInstanceOf[AnyRef]
    val serializedEventFut: Future[Array[Byte]] = Future.fromTry(serialization.serialize(payload))
    for {
      serializer <- Future.fromTry(Try(serialization.findSerializerFor(payload)))
      serializedEvent <- serializedEventFut
    } yield {
      val serId = serializer.identifier
      val serManifest = Serializers.manifestFor(serializer, payload)
      val meta =
        Metadata(serId, serManifest, persistentRepr.manifest, persistentRepr.writerUuid, persistentRepr.timestamp)
      (
        serializedEvent,
        meta.asJson)
    }
  }
}

object NewJournalSerializer {
  case class Metadata(serId: Int, serManifest: String, eventManifest: String, writerUuid: String, timestamp: Long)

  object Metadata {
    implicit val encoder: Encoder[Metadata] =
      Encoder.forProduct5("serId", "serManifest", "eventManifest", "writerUuid", "timestamp")(e =>
        (e.serId, e.serManifest, e.eventManifest, e.writerUuid, e.timestamp))
  }
}
