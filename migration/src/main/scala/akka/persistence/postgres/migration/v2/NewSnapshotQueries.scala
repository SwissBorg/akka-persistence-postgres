package akka.persistence.postgres.migration.v2

import akka.persistence.postgres.config.SnapshotTableConfiguration
import akka.persistence.postgres.db.ExtendedPostgresProfile.api._
import io.circe.Json

import scala.concurrent.ExecutionContext

private[v2] class NewSnapshotQueries(override val snapshotTableCfg: SnapshotTableConfiguration)
    extends NewSnapshotTables {

  def insertOrUpdate(rows: List[NewSnapshotRow])(
      implicit ec: ExecutionContext): DBIOAction[Int, NoStream, Effect.Write] =
    SnapshotTable.insertOrUpdateAll(rows).map(_ => rows.size)
}

private[v2] trait NewSnapshotTables {

  def snapshotTableCfg: SnapshotTableConfiguration

  class Snapshot(_tableTag: Tag)
      extends Table[NewSnapshotRow](
        _tableTag,
        _schemaName = snapshotTableCfg.schemaName,
        _tableName = snapshotTableCfg.tableName) {
    def * =
      (persistenceId, sequenceNumber, created, snapshot, snapshotRaw, metadata) <> (NewSnapshotRow.tupled, NewSnapshotRow.unapply)

    val persistenceId: Rep[String] =
      column[String](snapshotTableCfg.columnNames.persistenceId, O.Length(255, varying = true))
    val sequenceNumber: Rep[Long] = column[Long](snapshotTableCfg.columnNames.sequenceNumber)
    val created: Rep[Long] = column[Long](snapshotTableCfg.columnNames.created)
    val snapshot: Rep[Array[Byte]] = column[Array[Byte]](snapshotTableCfg.columnNames.snapshot)
    val snapshotRaw: Rep[Array[Byte]] = column[Array[Byte]]("snapshot_raw")
    val metadata: Rep[Json] = column[Json](snapshotTableCfg.columnNames.metadata)
    val pk = primaryKey(s"${tableName}_pk", (persistenceId, sequenceNumber))
  }

  lazy val SnapshotTable = new TableQuery(tag => new Snapshot(tag))
}
