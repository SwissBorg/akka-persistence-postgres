package akka.persistence.postgres.migration.v2.snapshot

import akka.persistence.postgres.config.SnapshotTableConfiguration
import akka.persistence.postgres.db.ExtendedPostgresProfile.api._
import io.circe.Json

import scala.concurrent.ExecutionContext

private[v2] class SnapshotMigrationQueries(override val snapshotTableCfg: SnapshotTableConfiguration)
    extends TempSnapshotTables {

  def insertOrUpdate(rows: List[TempSnapshotRow])(
      implicit ec: ExecutionContext): DBIOAction[Int, NoStream, Effect.Write] =
    SnapshotTable.insertOrUpdateAll(rows).map(_ => rows.size)
}

private trait TempSnapshotTables {

  def snapshotTableCfg: SnapshotTableConfiguration

  class TempSnapshot(_tableTag: Tag)
      extends Table[TempSnapshotRow](
        _tableTag,
        _schemaName = snapshotTableCfg.schemaName,
        _tableName = snapshotTableCfg.tableName) {
    def * =
      (persistenceId, sequenceNumber, created, oldSnapshot, tempSnapshot, metadata) <> (TempSnapshotRow.tupled, TempSnapshotRow.unapply)

    val persistenceId: Rep[String] =
      column[String](snapshotTableCfg.columnNames.persistenceId, O.Length(255, varying = true))
    val sequenceNumber: Rep[Long] = column[Long](snapshotTableCfg.columnNames.sequenceNumber)
    val created: Rep[Long] = column[Long](snapshotTableCfg.columnNames.created)
    val oldSnapshot: Rep[Array[Byte]] = column[Array[Byte]](snapshotTableCfg.columnNames.snapshot)
    val tempSnapshot: Rep[Array[Byte]] = column[Array[Byte]]("temp_snapshot")
    val metadata: Rep[Json] = column[Json](snapshotTableCfg.columnNames.metadata)
    val pk = primaryKey(s"${tableName}_pk", (persistenceId, sequenceNumber))
  }

  lazy val SnapshotTable = new TableQuery(tag => new TempSnapshot(tag))
}
