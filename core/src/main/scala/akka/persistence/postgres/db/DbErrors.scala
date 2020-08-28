package akka.persistence.postgres.db

import java.sql.SQLException

import org.slf4j.Logger

import scala.concurrent.ExecutionContext
import scala.util.{ Failure, Success }

object DbErrors {

  import ExtendedPostgresProfile.api._

  val PgDuplicateTable: String = "42P07"

  def withHandledPartitionErrors(logger: Logger, partitionDetails: String)(dbio: DBIOAction[_, NoStream, Effect])(
      implicit ec: ExecutionContext): DBIOAction[Unit, NoStream, Effect] =
    dbio.asTry.flatMap {
      case Failure(ex: SQLException) if ex.getSQLState == PgDuplicateTable =>
        logger.debug(s"Partition for $partitionDetails already exists")
        DBIO.successful(())
      case Failure(ex) =>
        logger.error(s"Cannot create partition for $partitionDetails", ex)
        DBIO.failed(ex)
      case Success(_) =>
        logger.debug(s"Created missing journal partition for $partitionDetails")
        DBIO.successful(())
    }
}
