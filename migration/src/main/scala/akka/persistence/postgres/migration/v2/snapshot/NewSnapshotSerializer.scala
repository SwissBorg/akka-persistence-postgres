package akka.persistence.postgres.migration.v2.snapshot

import akka.serialization.{ Serialization, Serializers }
import io.circe.{ Encoder, Json }

import scala.util.Try

private[v2] class NewSnapshotSerializer(serialization: Serialization) {

  import NewSnapshotSerializer._
  import io.circe.syntax._

  def serialize(snapshot: Any): Try[(Array[Byte], Json)] = {
    val payload = snapshot.asInstanceOf[AnyRef]
    for {
      ser <- Try(serialization.findSerializerFor(payload))
      serializedSnapshot <- serialization.serialize(payload)
    } yield {
      val metadataJson = Metadata(ser.identifier, Option(Serializers.manifestFor(ser, payload)).filterNot(_.trim.isEmpty))
      (serializedSnapshot, metadataJson.asJson)
    }
  }

}

private object NewSnapshotSerializer {
  case class Metadata(serId: Int, serManifest: Option[String])

  object Metadata {
    implicit val encoder: Encoder[Metadata] = Encoder
      .forProduct2[Metadata, Int, Option[String]]("sid", "sm")(m => (m.serId, m.serManifest))
      .mapJson(_.dropNullValues)
  }

}
