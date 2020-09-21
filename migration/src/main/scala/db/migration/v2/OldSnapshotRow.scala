package db.migration.v2

case class OldSnapshotRow(persistenceId: String, sequenceNumber: Long, created: Long, snapshot: Array[Byte])
