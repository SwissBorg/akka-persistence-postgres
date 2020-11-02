package akka.persistence.postgres.migration.snapshot

import akka.persistence.postgres.config.SnapshotTableConfiguration
import akka.persistence.postgres.db.ExtendedPostgresProfile.api._
import akka.persistence.postgres.snapshot.dao.SnapshotTables.SnapshotRow
import io.circe.Json

import scala.concurrent.ExecutionContext

private[snapshot] class SnapshotMigrationQueries(snapshotTableCfg: SnapshotTableConfiguration, tempTableName: String) {

  def insertOrUpdate(rows: List[SnapshotRow])(implicit ec: ExecutionContext): DBIOAction[Int, NoStream, Effect.Write] =
    TempSnapshotTable.insertOrUpdateAll(rows.sortBy(_.created)).map(_ => rows.size)

  class TempSnapshotTable(_tableTag: Tag)
      extends Table[SnapshotRow](_tableTag, _schemaName = snapshotTableCfg.schemaName, _tableName = tempTableName) {
    def * =
      (persistenceId, sequenceNumber, created, snapshot, metadata) <> (SnapshotRow.tupled, SnapshotRow.unapply)

    val persistenceId: Rep[String] =
      column[String](snapshotTableCfg.columnNames.persistenceId, O.Length(255, varying = true))
    val sequenceNumber: Rep[Long] = column[Long](snapshotTableCfg.columnNames.sequenceNumber)
    val created: Rep[Long] = column[Long](snapshotTableCfg.columnNames.created)
    val snapshot: Rep[Array[Byte]] = column[Array[Byte]](snapshotTableCfg.columnNames.snapshot)
    val metadata: Rep[Json] = column[Json](snapshotTableCfg.columnNames.metadata)
    val pk = primaryKey(s"${tableName}_pk", (persistenceId, sequenceNumber))
  }

  lazy val TempSnapshotTable = new TableQuery(tag => new TempSnapshotTable(tag))
}
