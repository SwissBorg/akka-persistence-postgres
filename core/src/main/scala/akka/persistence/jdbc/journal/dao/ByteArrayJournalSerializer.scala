/*
 * Copyright (C) 2014 - 2019 Dennis Vriend <https://github.com/dnvriend>
 * Copyright (C) 2019 - 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.jdbc
package journal.dao

import akka.persistence.PersistentRepr
import akka.persistence.jdbc.serialization.FlowPersistentReprSerializer
import akka.persistence.jdbc.tag.EventTagConverter
import akka.serialization.Serialization

import scala.collection.immutable._
import scala.concurrent.ExecutionContext
import scala.util.Try

class ByteArrayJournalSerializer(serialization: Serialization, tagConverter: EventTagConverter)(implicit ctx: ExecutionContext)
    extends FlowPersistentReprSerializer[JournalRow] {

    override def serialize(persistentRepr: PersistentRepr, tags: Set[String]): Try[JournalRow] =
      serialization
        .serialize(persistentRepr)
        .map(
          JournalRow(
            Long.MinValue,
            persistentRepr.deleted,
            persistentRepr.persistenceId,
            persistentRepr.sequenceNr,
            _,
            tags.map(tagConverter.getIdByNameForce).toList
          ))


    override def deserialize(journalRow: JournalRow): Try[(PersistentRepr, Long)] =
      serialization
        .deserialize(journalRow.message, classOf[PersistentRepr])
        .map((_, journalRow.ordering))

}

object ByteArrayJournalSerializer {
  val tagToIdMap: Map[String, Int] = Map("firstEvent" -> 1, "longtag" -> 2, "multiT1" -> 3, "multiT2" -> 4, "firstUnique" -> 5, "tag" -> 6, "expected" -> 7, "multi" -> 8, "companion" -> 9, "xxx" -> 10, "ended" -> 11, "last" -> 12, "mul" -> 13)
  val idToTagMap: Map[Int, String] = tagToIdMap.map { case (tag, id) => (id, tag) }
}
