package akka.persistence.postgres.db

import java.sql.SQLException

import org.slf4j.Logger

import scala.concurrent.Future

object DbErrors {
  val PgDuplicateTable: String = "42P07"
  val PgUniqueValidation: String = "23505"

  lazy val SwallowPartitionAlreadyExistsError: PartialFunction[Throwable, Future[Unit]] = {
    case ex: SQLException if ex.getSQLState == DbErrors.PgDuplicateTable =>
      // Partition already created from another session, all good, recovery succeeded
      Future.successful(())
  }
}
