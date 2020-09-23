package db.migration.v2

import akka.persistence.postgres.config.SnapshotTableConfiguration
import io.circe.Json

trait NewSnapshotTables {
  import akka.persistence.postgres.db.ExtendedPostgresProfile.api._

  def snapshotTableCfg: SnapshotTableConfiguration

  class Snapshot(_tableTag: Tag)
    extends Table[NewSnapshotRow](
      _tableTag,
      _schemaName = snapshotTableCfg.schemaName,
      _tableName = snapshotTableCfg.tableName) {
    def * = (persistenceId, sequenceNumber, created, snapshot, metadata) <> (NewSnapshotRow.tupled, NewSnapshotRow.unapply)

    val persistenceId: Rep[String] =
      column[String](snapshotTableCfg.columnNames.persistenceId, O.Length(255, varying = true))
    val sequenceNumber: Rep[Long] = column[Long](snapshotTableCfg.columnNames.sequenceNumber)
    val created: Rep[Long] = column[Long](snapshotTableCfg.columnNames.created)
    val snapshot: Rep[Array[Byte]] = column[Array[Byte]](snapshotTableCfg.columnNames.snapshot)
    val metadata: Rep[Json] = column[Json](snapshotTableCfg.columnNames.metadata)
    val pk = primaryKey(s"${tableName}_pk", (persistenceId, sequenceNumber))
  }

  lazy val SnapshotTable = new TableQuery(tag => new Snapshot(tag))
}

class NewSnapshotQueries(override val snapshotTableCfg: SnapshotTableConfiguration) extends NewSnapshotTables {
  import akka.persistence.postgres.db.ExtendedPostgresProfile.api._

  private val SnapshotTableC = Compiled(SnapshotTable)

  def insertOrUpdate(snapshotRow: NewSnapshotRow) =
    SnapshotTableC.insertOrUpdate(snapshotRow)
}

