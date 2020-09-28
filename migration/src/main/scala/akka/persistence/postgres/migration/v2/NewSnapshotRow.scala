package akka.persistence.postgres.migration.v2

import io.circe.Json

private[v2] case class NewSnapshotRow(
    persistenceId: String,
    sequenceNumber: Long,
    created: Long,
    snapshot: Array[Byte],
    metadata: Json)
