package akka.persistence.postgres.migration.v2

import akka.persistence.PersistentRepr
import akka.persistence.postgres.migration.v2.NewJournalSerializer.Metadata
import akka.serialization.{Serialization, Serializers}
import io.circe.{Encoder, Json}
import io.circe.syntax._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

private[v2] class NewJournalSerializer(serialization: Serialization)(
    implicit val executionContext: ExecutionContext) {

  import NewJournalSerializer._
  import io.circe.syntax._

  def serialize(persistentRepr: PersistentRepr): Future[(Array[Byte], Json)] = {
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

private object NewJournalSerializer {
  case class Metadata(serId: Int, serManifest: String, eventManifest: String, writerUuid: String, timestamp: Long)

  object Metadata {
    implicit val encoder: Encoder[Metadata] =
      Encoder.forProduct5("serId", "serManifest", "eventManifest", "writerUuid", "timestamp")(e =>
        (e.serId, e.serManifest, e.eventManifest, e.writerUuid, e.timestamp))
  }
}
