package akka.persistence.postgres.migration.journal

import akka.persistence.postgres.JournalRow
import akka.persistence.postgres.config.JournalTableConfiguration
import akka.persistence.postgres.db.ExtendedPostgresProfile.api._
import io.circe.Json
import slick.lifted.TableQuery

import scala.concurrent.ExecutionContext

private[journal] class JournalMigrationQueries(journalTable: TableQuery[TempJournalTable]) {

  def insertAll(rows: List[JournalRow])(implicit ec: ExecutionContext): DBIOAction[Int, NoStream, Effect.Write] =
    journalTable.insertOrUpdateAll(rows.sortBy(_.ordering)).map(_ => rows.size)

}

private[journal] trait TempJournalTable extends Table[JournalRow] {
  def ordering: Rep[Long]
  def persistenceId: Rep[String]
  def sequenceNumber: Rep[Long]
  def deleted: Rep[Boolean]
  def tags: Rep[List[Int]]
  def message: Rep[Array[Byte]]
  def metadata: Rep[Json]
}

private[journal] abstract class TempBaseJournalTable(
    _tableTag: Tag,
    journalTableCfg: JournalTableConfiguration,
    tempTableName: String)
    extends Table[JournalRow](_tableTag, _schemaName = journalTableCfg.schemaName, _tableName = tempTableName)
    with TempJournalTable

private[journal] class TempFlatJournalTable private (
    _tableTag: Tag,
    journalTableCfg: JournalTableConfiguration,
    tempTableName: String)
    extends TempBaseJournalTable(_tableTag, journalTableCfg, tempTableName) {
  def * =
    (ordering, deleted, persistenceId, sequenceNumber, message, tags, metadata) <> (JournalRow.tupled, JournalRow.unapply)

  val ordering: Rep[Long] = column[Long](journalTableCfg.columnNames.ordering)
  val persistenceId: Rep[String] =
    column[String](journalTableCfg.columnNames.persistenceId, O.Length(255, varying = true))
  val sequenceNumber: Rep[Long] = column[Long](journalTableCfg.columnNames.sequenceNumber)
  val deleted: Rep[Boolean] = column[Boolean](journalTableCfg.columnNames.deleted, O.Default(false))
  val tags: Rep[List[Int]] = column[List[Int]](journalTableCfg.columnNames.tags)
  val message: Rep[Array[Byte]] = column[Array[Byte]](journalTableCfg.columnNames.message)
  val metadata: Rep[Json] = column[Json](journalTableCfg.columnNames.metadata)

  val pk = primaryKey(s"${tableName}_pk", (persistenceId, sequenceNumber))
  val orderingIdx = index(s"${tableName}_ordering_idx", ordering, unique = true)
  val tagsIdx = index(s"${tableName}_tags_idx", tags)
}

private[journal] object TempFlatJournalTable {
  def apply(journalTableCfg: JournalTableConfiguration, tempTableName: String): TableQuery[TempJournalTable] =
    TableQuery(tag => new TempFlatJournalTable(tag, journalTableCfg, tempTableName))
}

private[journal] class TempPartitionedJournalTable private (
    _tableTag: Tag,
    journalTableCfg: JournalTableConfiguration,
    tempTableName: String)
    extends TempBaseJournalTable(_tableTag, journalTableCfg, tempTableName) {
  def * =
    (ordering, deleted, persistenceId, sequenceNumber, message, tags, metadata) <> (JournalRow.tupled, JournalRow.unapply)

  val ordering: Rep[Long] = column[Long](journalTableCfg.columnNames.ordering)
  val persistenceId: Rep[String] =
    column[String](journalTableCfg.columnNames.persistenceId, O.Length(255, varying = true))
  val sequenceNumber: Rep[Long] = column[Long](journalTableCfg.columnNames.sequenceNumber)
  val deleted: Rep[Boolean] = column[Boolean](journalTableCfg.columnNames.deleted, O.Default(false))
  val tags: Rep[List[Int]] = column[List[Int]](journalTableCfg.columnNames.tags)
  val message: Rep[Array[Byte]] = column[Array[Byte]](journalTableCfg.columnNames.message)
  val metadata: Rep[Json] = column[Json](journalTableCfg.columnNames.metadata)

  val pk = primaryKey(s"${tableName}_pk", ordering)
  val perIdSeqNumIdx = index(s"${tableName}_persistence_id_sequence_number_idx", (persistenceId, sequenceNumber))
  val tagsIdx = index(s"${tableName}_tags_idx", tags)
}

private[journal] object TempPartitionedJournalTable {
  def apply(journalTableCfg: JournalTableConfiguration, tempTableName: String): TableQuery[TempJournalTable] =
    TableQuery(tag => new TempPartitionedJournalTable(tag, journalTableCfg, tempTableName))
}

private[journal] object TempNestedPartitionsJournalTable {
  def apply(journalTableCfg: JournalTableConfiguration, tempTableName: String): TableQuery[TempJournalTable] =
    TempFlatJournalTable.apply(journalTableCfg, tempTableName)
}
