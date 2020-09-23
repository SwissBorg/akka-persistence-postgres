package db.migration.v2

import io.circe.Json

final case class NewJournalRow(
    ordering: Long,
    deleted: Boolean,
    persistenceId: String,
    sequenceNumber: Long,
    message: Array[Byte],
    tags: List[Int],
    metadata: Json)
