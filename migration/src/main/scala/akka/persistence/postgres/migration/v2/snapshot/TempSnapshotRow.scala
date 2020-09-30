package akka.persistence.postgres.migration.v2.snapshot

import io.circe.Json

private[v2] case class TempSnapshotRow(
    persistenceId: String,
    sequenceNumber: Long,
    created: Long,
    oldSnapshot: Array[Byte],
    newSnapshot: Array[Byte],
    metadata: Json)
