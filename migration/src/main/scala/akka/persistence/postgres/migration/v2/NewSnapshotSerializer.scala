package akka.persistence.postgres.migration.v2

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
      val metadataJson = Metadata(ser.identifier, Serializers.manifestFor(ser, payload))
      (serializedSnapshot, metadataJson.asJson)
    }
  }

}

private object NewSnapshotSerializer {
  case class Metadata(serId: Int, serManifest: String)

  object Metadata {
    implicit val encoder: Encoder[Metadata] = Encoder.forProduct2("serId", "serManifest")(m => (m.serId, m.serManifest))
  }

}
