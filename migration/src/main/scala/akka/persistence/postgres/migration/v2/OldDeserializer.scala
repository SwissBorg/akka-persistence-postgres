package akka.persistence.postgres.migration.v2

import akka.persistence.PersistentRepr
import akka.serialization.Serialization

import scala.util.Try

private[v2] class OldDeserializer(serialization: Serialization) {

  def deserialize(message: Array[Byte]): Try[PersistentRepr] =
    serialization.deserialize(message, classOf[PersistentRepr])

}
