package akka.persistence.postgres.migration.v2

import akka.persistence.SnapshotMetadata
import akka.serialization.{ Serialization, Serializers }
import io.circe.Encoder

import scala.util.Try

private[v2] class NewSnapshotSerializer(serialization: Serialization) {

  import NewSnapshotSerializer._
  import io.circe.syntax._

  def serialize(metadata: SnapshotMetadata, snapshot: Any): Try[NewSnapshotRow] = {
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

private object NewSnapshotSerializer {
  case class Metadata(serId: Int, serManifest: String)

  object Metadata {
    implicit val encoder: Encoder[Metadata] = Encoder.forProduct2("serId", "serManifest")(m => (m.serId, m.serManifest))
  }

}
