package akka.persistence.postgres.migration.v2

import io.circe.Json

private[v2] final case class NewJournalRow(
    ordering: Long,
    deleted: Boolean,
    persistenceId: String,
    sequenceNumber: Long,
    oldMessage: Array[Byte],
    newMessage: Array[Byte],
    tags: List[Int],
    metadata: Json)
