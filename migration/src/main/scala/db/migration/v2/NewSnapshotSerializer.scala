package db.migration.v2

import akka.persistence.SnapshotMetadata
import akka.persistence.postgres.serialization.SnapshotSerializer
import akka.serialization.{ Serialization, Serializers }
import db.migration.v2.NewSnapshotSerializer.Metadata
import io.circe.{ Decoder, Encoder }

import scala.util.Try

class NewSnapshotSerializer(serialization: Serialization) extends SnapshotSerializer[NewSnapshotRow] {

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

  def deserialize(snapshotRow: NewSnapshotRow): Try[(SnapshotMetadata, Any)] = {
    for {
      metadata <- snapshotRow.metadata.as[Metadata].toTry
      snapshot <- serialization.deserialize(snapshotRow.snapshot, metadata.serId, metadata.serManifest)
    } yield {
      val snapshotMetadata =
        SnapshotMetadata(snapshotRow.persistenceId, snapshotRow.sequenceNumber, snapshotRow.created)
      (snapshotMetadata, snapshot)
    }
  }
}

object NewSnapshotSerializer {
  case class Metadata(serId: Int, serManifest: String)

  object Metadata {
    implicit val encoder: Encoder[Metadata] = Encoder.forProduct2("serId", "serManifest")(m => (m.serId, m.serManifest))
    implicit val decoder: Decoder[Metadata] = Decoder.forProduct2("serId", "serManifest")(Metadata.apply)
  }

}
