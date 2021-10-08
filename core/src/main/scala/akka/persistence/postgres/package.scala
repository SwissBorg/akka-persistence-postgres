/*
 * Copyright (C) 2014 - 2019 Dennis Vriend <https://github.com/dnvriend>
 * Copyright (C) 2019 - 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence

import io.circe.Json

package object postgres {
  final case class JournalRow(
      ordering: Long,
      deleted: Boolean,
      persistenceId: String,
      sequenceNumber: Long,
      message: Array[Byte],
      tags: List[Int],
      metadata: Json)

  final case class JournalPersistenceIdsRow(
      persistenceId: String,
      maxSequenceNumber: Long,
      minOrdering: Long,
      maxOrdering: Long)
}
