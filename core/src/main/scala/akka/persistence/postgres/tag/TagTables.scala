package akka.persistence.postgres.tag

import akka.persistence.postgres.config.TagsTableConfiguration

trait TagTables {

  import akka.persistence.postgres.db.ExtendedPostgresProfile.api._

  def tagsTableCfg: TagsTableConfiguration

  class EventTagTableDefinition(_tableTag: Tag)
      extends Table[EventTag](_tableTag, _schemaName = tagsTableCfg.schemaName, _tableName = tagsTableCfg.tableName) {
    def * = (id, name) <> (EventTag.tupled, EventTag.unapply)

    val id: Rep[Int] = column[Int](tagsTableCfg.columnNames.id, O.AutoInc)
    val name: Rep[String] = column[String](tagsTableCfg.columnNames.name, O.Length(255, varying = true))
    val pk = primaryKey(s"${tableName}_pk", id)
    val nameIdx = index(s"${tableName}_name_idx", name, unique = true)
  }

  lazy val EventTagTable = new TableQuery(tag => new EventTagTableDefinition(tag))
}
