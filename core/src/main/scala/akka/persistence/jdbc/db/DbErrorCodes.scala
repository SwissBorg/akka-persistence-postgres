package akka.persistence.jdbc.db

object DbErrorCodes {
  val PgDuplicateTable: String = "42P07"
  val PgUniqueValidation: String = "23505"
}
