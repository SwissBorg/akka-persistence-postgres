/*
 * Copyright (C) 2014 - 2019 Dennis Vriend <https://github.com/dnvriend>
 * Copyright (C) 2019 - 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.postgres.snapshot.dao

import akka.persistence.SnapshotMetadata
import akka.persistence.postgres.serialization.SnapshotSerializer
import akka.persistence.postgres.snapshot.dao.ByteArraySnapshotSerializer.Metadata
import akka.persistence.postgres.snapshot.dao.SnapshotTables.SnapshotRow
import akka.serialization.{ Serialization, Serializers }
import io.circe.{ Decoder, Encoder }

import scala.util.Try

class ByteArraySnapshotSerializer(serialization: Serialization) extends SnapshotSerializer[SnapshotRow] {

  def serialize(metadata: SnapshotMetadata, snapshot: Any): Try[SnapshotRow] = {
    import io.circe.syntax._
    val payload = snapshot.asInstanceOf[AnyRef]
    for {
      ser <- Try(serialization.findSerializerFor(payload))
      serializedSnapshot <- serialization.serialize(payload)
    } yield {
      val metadataJson = Metadata(ser.identifier, Serializers.manifestFor(ser, payload))
      SnapshotRow(
        metadata.persistenceId,
        metadata.sequenceNr,
        metadata.timestamp,
        serializedSnapshot,
        metadataJson.asJson)
    }
  }

  def deserialize(snapshotRow: SnapshotRow): Try[(SnapshotMetadata, Any)] = {
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

object ByteArraySnapshotSerializer {
  case class Metadata(serId: Int, serManifest: String)

  object Metadata {
    implicit val encoder: Encoder[Metadata] = Encoder.forProduct2("serId", "serManifest")(m => (m.serId, m.serManifest))
    implicit val decoder: Decoder[Metadata] = Decoder.forProduct2("serId", "serManifest")(Metadata.apply)
  }
}
