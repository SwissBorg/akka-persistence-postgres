package akka.persistence.postgres.migration.v2.journal

import akka.persistence.PersistentRepr
import akka.serialization.Serialization

import scala.util.Try

private[v2] class OldJournalDeserializer(serialization: Serialization) {

  def deserialize(message: Array[Byte]): Try[PersistentRepr] =
    serialization.deserialize(message, classOf[PersistentRepr])

}
