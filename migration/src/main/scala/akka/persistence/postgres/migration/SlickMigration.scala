package akka.persistence.postgres.migration

import akka.persistence.postgres.db.ExtendedPostgresProfile
import akka.stream.Materializer
import io.circe.{ Json, Printer }
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.slf4j.{ Logger, LoggerFactory }
import slick.jdbc.{ GetResult, JdbcBackend, SetParameter }

import scala.concurrent.ExecutionContext

abstract class SlickMigration(db: => JdbcBackend.Database)(implicit ec: ExecutionContext, mat: Materializer)
    extends BaseJavaMigration
    with ExtendedPostgresProfile.MyAPI {

  lazy val log: Logger = LoggerFactory.getLogger(this.getClass)

  implicit val GetIntList: GetResult[List[Int]] = GetResult(_.nextArray[Int]().toList)
  implicit val GetByteArr: GetResult[Array[Byte]] = GetResult(_.nextBytes())
  implicit val SetByteArr: SetParameter[Array[Byte]] = SetParameter((arr, pp) => pp.setBytes(arr))
  implicit val SetJson: SetParameter[Json] = SetParameter((json, pp) => pp.setString(json.printWith(Printer.noSpaces)))

}
