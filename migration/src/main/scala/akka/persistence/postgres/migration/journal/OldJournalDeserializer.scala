package akka.persistence.postgres.migration.journal

import akka.persistence.PersistentRepr
import akka.serialization.Serialization

import scala.util.Try

private[journal] class OldJournalDeserializer(serialization: Serialization) {

  def deserialize(message: Array[Byte]): Try[PersistentRepr] =
    serialization.deserialize(message, classOf[PersistentRepr])

}
