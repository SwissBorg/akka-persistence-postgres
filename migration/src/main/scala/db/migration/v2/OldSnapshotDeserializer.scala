package db.migration.v2

import akka.persistence.serialization.Snapshot
import akka.serialization.Serialization

import scala.util.Try

class OldSnapshotDeserializer(serialization: Serialization) {

  def deserialize(rawSnapshot: Array[Byte]): Try[Any] = {
    serialization.deserialize(rawSnapshot, classOf[Snapshot]).map(_.data)
  }
}
