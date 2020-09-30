package akka.persistence.postgres.migration.v2.journal

import akka.persistence.postgres.config.JournalTableConfiguration
import akka.persistence.postgres.db.ExtendedPostgresProfile.api._
import io.circe.Json
import slick.lifted.TableQuery

import scala.concurrent.ExecutionContext

private[v2] class JournalMigrationQueries(journalTable: TableQuery[TempJournalTable]) {

  def updateAll(rows: List[TempJournalRow])(implicit ec: ExecutionContext): DBIOAction[Int, NoStream, Effect.Write] =
    journalTable.insertOrUpdateAll(rows).map(_ => rows.size)

}

private[v2] trait TempJournalTable extends Table[TempJournalRow] {
  def ordering: Rep[Long]
  def persistenceId: Rep[String]
  def sequenceNumber: Rep[Long]
  def deleted: Rep[Boolean]
  def tags: Rep[List[Int]]
  def oldMessage: Rep[Array[Byte]]
  def tempMessage: Rep[Array[Byte]]
  def metadata: Rep[Json]
}

private[v2] abstract class TempBaseJournalTable(_tableTag: Tag, journalTableCfg: JournalTableConfiguration)
    extends Table[TempJournalRow](
      _tableTag,
      _schemaName = journalTableCfg.schemaName,
      _tableName = journalTableCfg.tableName)
    with TempJournalTable

private[v2] class TempFlatJournalTable private(_tableTag: Tag, journalTableCfg: JournalTableConfiguration)
    extends TempBaseJournalTable(_tableTag, journalTableCfg) {
  def * =
    (ordering, deleted, persistenceId, sequenceNumber, oldMessage, tempMessage, tags, metadata) <> (TempJournalRow.tupled, TempJournalRow.unapply)

  val ordering: Rep[Long] = column[Long](journalTableCfg.columnNames.ordering, O.AutoInc)
  val persistenceId: Rep[String] =
    column[String](journalTableCfg.columnNames.persistenceId, O.Length(255, varying = true))
  val sequenceNumber: Rep[Long] = column[Long](journalTableCfg.columnNames.sequenceNumber)
  val deleted: Rep[Boolean] = column[Boolean](journalTableCfg.columnNames.deleted, O.Default(false))
  val tags: Rep[List[Int]] = column[List[Int]](journalTableCfg.columnNames.tags)
  val oldMessage: Rep[Array[Byte]] = column[Array[Byte]](journalTableCfg.columnNames.message)
  val tempMessage: Rep[Array[Byte]] = column[Array[Byte]]("temp_message")
  val metadata: Rep[Json] = column[Json](journalTableCfg.columnNames.metadata)

  val pk = primaryKey(s"${tableName}_pk", (persistenceId, sequenceNumber))
  val orderingIdx = index(s"${tableName}_ordering_idx", ordering, unique = true)
  val tagsIdx = index(s"${tableName}_tags_idx", tags)
}

private[v2] object TempFlatJournalTable {
  def apply(journalTableCfg: JournalTableConfiguration): TableQuery[TempJournalTable] =
    TableQuery(tag => new TempFlatJournalTable(tag, journalTableCfg))
}

private[v2] class TempPartitionedJournalTable private(_tableTag: Tag, journalTableCfg: JournalTableConfiguration)
    extends TempBaseJournalTable(_tableTag, journalTableCfg) {
  def * =
    (ordering, deleted, persistenceId, sequenceNumber, oldMessage, tempMessage, tags, metadata) <> (TempJournalRow.tupled, TempJournalRow.unapply)

  val ordering: Rep[Long] = column[Long](journalTableCfg.columnNames.ordering)
  val persistenceId: Rep[String] =
    column[String](journalTableCfg.columnNames.persistenceId, O.Length(255, varying = true))
  val sequenceNumber: Rep[Long] = column[Long](journalTableCfg.columnNames.sequenceNumber)
  val deleted: Rep[Boolean] = column[Boolean](journalTableCfg.columnNames.deleted, O.Default(false))
  val tags: Rep[List[Int]] = column[List[Int]](journalTableCfg.columnNames.tags)
  val oldMessage: Rep[Array[Byte]] = column[Array[Byte]](journalTableCfg.columnNames.message)
  val tempMessage: Rep[Array[Byte]] = column[Array[Byte]]("temp_message")
  val metadata: Rep[Json] = column[Json](journalTableCfg.columnNames.metadata)

  val pk = primaryKey(s"${tableName}_pk", (persistenceId, sequenceNumber, ordering))
  val tagsIdx = index(s"${tableName}_tags_idx", tags)
}

private[v2] object TempPartitionedJournalTable {
  def apply(journalTableCfg: JournalTableConfiguration): TableQuery[TempJournalTable] =
    TableQuery(tag => new TempPartitionedJournalTable(tag, journalTableCfg))
}

private[v2] object NewNestedPartitionsJournalTable {
  def apply(journalTableCfg: JournalTableConfiguration): TableQuery[TempJournalTable] =
    TempFlatJournalTable.apply(journalTableCfg)
}
