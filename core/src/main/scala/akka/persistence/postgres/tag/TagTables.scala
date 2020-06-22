package akka.persistence.postgres.tag

trait TagTables {

  import akka.persistence.postgres.db.ExtendedPostgresProfile.api._

  class EventTagTableDefinition(_tableTag: Tag)
    extends Table[EventTag](
      _tableTag,
      _schemaName = None,
      _tableName = "event_tag") {
    def * = (id, name) <> (EventTag.tupled, EventTag.unapply)

    val id: Rep[Int] = column[Int]("id", O.AutoInc)
    val name: Rep[String] = column[String]("name", O.Length(255, varying = true))
    val pk = primaryKey(s"${tableName}_pk", id)
    val nameIdx = index(s"${tableName}_name_idx", name, unique = true)
  }

  lazy val EventTagTable = new TableQuery(tag => new EventTagTableDefinition(tag))
}
