package akka.persistence.postgres.tag

import akka.persistence.postgres.config.TagsTableConfiguration

class EventTagQueries(val tagsTableCfg: TagsTableConfiguration) extends TagTables {

  import akka.persistence.postgres.db.ExtendedPostgresProfile.api._

  def add(xs: EventTag) =
    EventTagTable.returning(EventTagTable.map(_.id)) += xs

  val selectAll = EventTagTable.result

  private def _selectByName(name: Rep[String]) =
    EventTagTable.filter(_.name === name)

  val selectByName = Compiled(_selectByName _)

  private def _selectById(id: Rep[Int]) =
    EventTagTable.filter(_.id === id)

  val selectById = Compiled(_selectById _)

}
