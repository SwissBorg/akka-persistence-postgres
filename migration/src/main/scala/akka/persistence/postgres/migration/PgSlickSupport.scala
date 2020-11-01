package akka.persistence.postgres.migration

import io.circe.{ Json, Printer }
import org.slf4j.{ Logger, LoggerFactory }
import slick.jdbc.{ GetResult, SetParameter }

abstract class PgSlickSupport {

  lazy val log: Logger = LoggerFactory.getLogger(this.getClass)

  implicit val GetByteArr: GetResult[Array[Byte]] = GetResult(_.nextBytes())
  implicit val SetByteArr: SetParameter[Array[Byte]] = SetParameter((arr, pp) => pp.setBytes(arr))
  implicit val SetJson: SetParameter[Json] = SetParameter((json, pp) => pp.setString(json.printWith(Printer.noSpaces)))

}
