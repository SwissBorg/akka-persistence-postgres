package akka.persistence.postgres.migration.snapshot

import akka.persistence.serialization.Snapshot
import akka.serialization.Serialization

import scala.util.Try

private[snapshot] class OldSnapshotDeserializer(serialization: Serialization) {

  def deserialize(rawSnapshot: Array[Byte]): Try[Any] = {
    serialization.deserialize(rawSnapshot, classOf[Snapshot]).map(_.data)
  }
}
