package db.migration.v2

import akka.persistence.SnapshotMetadata
import akka.serialization.{ Serialization, Serializers }
import db.migration.v2.NewSnapshotSerializer.Metadata
import io.circe.Encoder

import scala.util.Try

class NewSnapshotSerializer(serialization: Serialization) {

  def serialize(metadata: SnapshotMetadata, snapshot: Any): Try[NewSnapshotRow] = {
    import io.circe.syntax._
    val payload = snapshot.asInstanceOf[AnyRef]
    for {
      ser <- Try(serialization.findSerializerFor(payload))
      serializedSnapshot <- serialization.serialize(payload)
    } yield {
      val metadataJson = Metadata(ser.identifier, Serializers.manifestFor(ser, payload))
      NewSnapshotRow(
        metadata.persistenceId,
        metadata.sequenceNr,
        metadata.timestamp,
        serializedSnapshot,
        metadataJson.asJson)
    }
  }

}

object NewSnapshotSerializer {
  case class Metadata(serId: Int, serManifest: String)

  object Metadata {
    implicit val encoder: Encoder[Metadata] = Encoder.forProduct2("serId", "serManifest")(m => (m.serId, m.serManifest))
  }

}
