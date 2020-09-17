/*
 * Copyright (C) 2014 - 2019 Dennis Vriend <https://github.com/dnvriend>
 * Copyright (C) 2019 - 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.postgres.snapshot.dao

import akka.persistence.postgres.config.SnapshotTableConfiguration
import akka.persistence.postgres.snapshot.dao.SnapshotTables._
import io.circe.Json

object SnapshotTables {
  case class SnapshotRow(
      persistenceId: String,
      sequenceNumber: Long,
      created: Long,
      snapshot: Array[Byte],
      metadata: Json)
}

trait SnapshotTables {
  import akka.persistence.postgres.db.ExtendedPostgresProfile.api._

  def snapshotTableCfg: SnapshotTableConfiguration

  class Snapshot(_tableTag: Tag)
      extends Table[SnapshotRow](
        _tableTag,
        _schemaName = snapshotTableCfg.schemaName,
        _tableName = snapshotTableCfg.tableName) {
    def * = (persistenceId, sequenceNumber, created, snapshot, metadata) <> (SnapshotRow.tupled, SnapshotRow.unapply)

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
