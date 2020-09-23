package db.migration.v2

import io.circe.Json

case class NewSnapshotRow(
    persistenceId: String,
    sequenceNumber: Long,
    created: Long,
    snapshot: Array[Byte],
    metadata: Json)
