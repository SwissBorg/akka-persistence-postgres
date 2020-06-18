package akka.persistence.jdbc.db

object PostgresErrorCodes {
  val PgCheckViolation: String = "23514"
  val PgDuplicateTable: String = "42P07"
  val PgUniqueValidation: String = "23505"
}
