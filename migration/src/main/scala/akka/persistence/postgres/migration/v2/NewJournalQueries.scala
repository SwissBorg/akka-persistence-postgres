package akka.persistence.postgres.migration.v2

import akka.persistence.postgres.config.JournalTableConfiguration
import akka.persistence.postgres.db.ExtendedPostgresProfile.api._
import io.circe.Json
import slick.lifted.TableQuery

import scala.concurrent.ExecutionContext

private[v2] class NewJournalQueries(journalTable: TableQuery[NewJournalTable]) {

  def updateAll(rows: List[NewJournalRow])(implicit ec: ExecutionContext): DBIOAction[Int, NoStream, Effect.Write] =
    journalTable.insertOrUpdateAll(rows).map(_ => rows.size)

}

private[v2] trait NewJournalTable extends Table[NewJournalRow] {
  def ordering: Rep[Long]
  def persistenceId: Rep[String]
  def sequenceNumber: Rep[Long]
  def deleted: Rep[Boolean]
  def tags: Rep[List[Int]]
  def messageRaw: Rep[Array[Byte]]
  def metadata: Rep[Json]
}

private[v2] abstract class NewBaseJournalTable(_tableTag: Tag, journalTableCfg: JournalTableConfiguration)
    extends Table[NewJournalRow](
      _tableTag,
      _schemaName = journalTableCfg.schemaName,
      _tableName = journalTableCfg.tableName)
    with NewJournalTable

private[v2] class NewFlatJournalTable private (_tableTag: Tag, journalTableCfg: JournalTableConfiguration)
    extends NewBaseJournalTable(_tableTag, journalTableCfg) {
  def * =
    (ordering, deleted, persistenceId, sequenceNumber, message, messageRaw, tags, metadata) <> (NewJournalRow.tupled, NewJournalRow.unapply)

  val ordering: Rep[Long] = column[Long](journalTableCfg.columnNames.ordering, O.AutoInc)
  val persistenceId: Rep[String] =
    column[String](journalTableCfg.columnNames.persistenceId, O.Length(255, varying = true))
  val sequenceNumber: Rep[Long] = column[Long](journalTableCfg.columnNames.sequenceNumber)
  val deleted: Rep[Boolean] = column[Boolean](journalTableCfg.columnNames.deleted, O.Default(false))
  val tags: Rep[List[Int]] = column[List[Int]](journalTableCfg.columnNames.tags)
  val message: Rep[Array[Byte]] = column[Array[Byte]](journalTableCfg.columnNames.message)
  val messageRaw: Rep[Array[Byte]] = column[Array[Byte]]("message_raw")
  val metadata: Rep[Json] = column[Json](journalTableCfg.columnNames.metadata)

  val pk = primaryKey(s"${tableName}_pk", (persistenceId, sequenceNumber))
  val orderingIdx = index(s"${tableName}_ordering_idx", ordering, unique = true)
  val tagsIdx = index(s"${tableName}_tags_idx", tags)
}

private[v2] object NewFlatJournalTable {
  def apply(journalTableCfg: JournalTableConfiguration): TableQuery[NewJournalTable] =
    TableQuery(tag => new NewFlatJournalTable(tag, journalTableCfg))
}

private[v2] class NewPartitionedJournalTable private (_tableTag: Tag, journalTableCfg: JournalTableConfiguration)
    extends NewBaseJournalTable(_tableTag, journalTableCfg) {
  def * =
    (ordering, deleted, persistenceId, sequenceNumber, message, messageRaw, tags, metadata) <> (NewJournalRow.tupled, NewJournalRow.unapply)

  val ordering: Rep[Long] = column[Long](journalTableCfg.columnNames.ordering)
  val persistenceId: Rep[String] =
    column[String](journalTableCfg.columnNames.persistenceId, O.Length(255, varying = true))
  val sequenceNumber: Rep[Long] = column[Long](journalTableCfg.columnNames.sequenceNumber)
  val deleted: Rep[Boolean] = column[Boolean](journalTableCfg.columnNames.deleted, O.Default(false))
  val tags: Rep[List[Int]] = column[List[Int]](journalTableCfg.columnNames.tags)
  val message: Rep[Array[Byte]] = column[Array[Byte]](journalTableCfg.columnNames.message)
  val messageRaw: Rep[Array[Byte]] = column[Array[Byte]]("message_raw")
  val metadata: Rep[Json] = column[Json](journalTableCfg.columnNames.metadata)

  val pk = primaryKey(s"${tableName}_pk", (persistenceId, sequenceNumber, ordering))
  val tagsIdx = index(s"${tableName}_tags_idx", tags)
}

private[v2] object NewPartitionedJournalTable {
  def apply(journalTableCfg: JournalTableConfiguration): TableQuery[NewJournalTable] =
    TableQuery(tag => new NewPartitionedJournalTable(tag, journalTableCfg))
}

private[v2] object NewNestedPartitionsJournalTable {
  def apply(journalTableCfg: JournalTableConfiguration): TableQuery[NewJournalTable] =
    NewFlatJournalTable.apply(journalTableCfg)
}
