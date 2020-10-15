package akka.persistence.postgres.migration.v2.journal

import akka.persistence.PersistentRepr
import akka.serialization.{ Serialization, Serializers }
import io.circe.{ Encoder, Json }

import scala.util.Try

private[v2] class NewJournalSerializer(serialization: Serialization) {

  import NewJournalSerializer._
  import io.circe.syntax._

  def serialize(persistentRepr: PersistentRepr): Try[(Array[Byte], Json)] = {
    val payload: AnyRef = persistentRepr.payload.asInstanceOf[AnyRef]
    val serializedEventFut: Try[Array[Byte]] = serialization.serialize(payload)
    for {
      serializer <- Try(serialization.findSerializerFor(payload))
      serializedEvent <- serializedEventFut
    } yield {
      val serId = serializer.identifier
      val serManifest = Serializers.manifestFor(serializer, payload)
      val meta =
        Metadata(
          serId,
          Option(serManifest).filterNot(_.trim.isEmpty),
          Option(persistentRepr.manifest).filterNot(_.trim.isEmpty),
          persistentRepr.writerUuid,
          persistentRepr.timestamp)
      (serializedEvent, meta.asJson)
    }
  }
}

private object NewJournalSerializer {
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
  }
}
