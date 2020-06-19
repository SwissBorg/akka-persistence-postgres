/*
 * Copyright (C) 2014 - 2019 Dennis Vriend <https://github.com/dnvriend>
 * Copyright (C) 2019 - 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.jdbc.snapshot.dao

import akka.persistence.jdbc.config.SnapshotTableConfiguration
import akka.persistence.jdbc.snapshot.dao.SnapshotTables.SnapshotRow

class SnapshotQueries(override val snapshotTableCfg: SnapshotTableConfiguration) extends SnapshotTables {
  import akka.persistence.jdbc.db.ExtendedPostgresProfile.api._

  private val SnapshotTableC = Compiled(SnapshotTable)

  def insertOrUpdate(snapshotRow: SnapshotRow) =
    SnapshotTableC.insertOrUpdate(snapshotRow)

  private def _selectAllByPersistenceId(persistenceId: Rep[String]) =
    SnapshotTable.filter(_.persistenceId === persistenceId).sortBy(_.sequenceNumber.desc)
  val selectAllByPersistenceId = Compiled(_selectAllByPersistenceId _)

  private def _selectLatestByPersistenceId(persistenceId: Rep[String]) =
    _selectAllByPersistenceId(persistenceId).take(1)
  val selectLatestByPersistenceId = Compiled(_selectLatestByPersistenceId _)

  private def _selectByPersistenceIdAndSequenceNr(persistenceId: Rep[String], sequenceNr: Rep[Long]) =
    _selectAllByPersistenceId(persistenceId).filter(_.sequenceNumber === sequenceNr)
  val selectByPersistenceIdAndSequenceNr = Compiled(_selectByPersistenceIdAndSequenceNr _)

  private def _selectByPersistenceIdUpToMaxTimestamp(persistenceId: Rep[String], maxTimestamp: Rep[Long]) =
    _selectAllByPersistenceId(persistenceId).filter(_.created <= maxTimestamp)
  val selectByPersistenceIdUpToMaxTimestamp = Compiled(_selectByPersistenceIdUpToMaxTimestamp _)

  private def _selectByPersistenceIdUpToMaxSequenceNr(persistenceId: Rep[String], maxSequenceNr: Rep[Long]) =
    _selectAllByPersistenceId(persistenceId).filter(_.sequenceNumber <= maxSequenceNr)
  val selectByPersistenceIdUpToMaxSequenceNr = Compiled(_selectByPersistenceIdUpToMaxSequenceNr _)

  private def _selectByPersistenceIdUpToMaxSequenceNrAndMaxTimestamp(
      persistenceId: Rep[String],
      maxSequenceNr: Rep[Long],
      maxTimestamp: Rep[Long]) =
    _selectByPersistenceIdUpToMaxSequenceNr(persistenceId, maxSequenceNr).filter(_.created <= maxTimestamp)
  val selectByPersistenceIdUpToMaxSequenceNrAndMaxTimestamp = Compiled(
    _selectByPersistenceIdUpToMaxSequenceNrAndMaxTimestamp _)

  private def _selectOneByPersistenceIdAndMaxTimestamp(persistenceId: Rep[String], maxTimestamp: Rep[Long]) =
    _selectAllByPersistenceId(persistenceId).filter(_.created <= maxTimestamp).take(1)
  val selectOneByPersistenceIdAndMaxTimestamp = Compiled(_selectOneByPersistenceIdAndMaxTimestamp _)

  private def _selectOneByPersistenceIdAndMaxSequenceNr(persistenceId: Rep[String], maxSequenceNr: Rep[Long]) =
    _selectAllByPersistenceId(persistenceId).filter(_.sequenceNumber <= maxSequenceNr).take(1)
  val selectOneByPersistenceIdAndMaxSequenceNr = Compiled(_selectOneByPersistenceIdAndMaxSequenceNr _)

  private def _selectOneByPersistenceIdAndMaxSequenceNrAndMaxTimestamp(
      persistenceId: Rep[String],
      maxSequenceNr: Rep[Long],
      maxTimestamp: Rep[Long]) =
    _selectByPersistenceIdUpToMaxSequenceNr(persistenceId, maxSequenceNr).filter(_.created <= maxTimestamp).take(1)
  val selectOneByPersistenceIdAndMaxSequenceNrAndMaxTimestamp = Compiled(
    _selectOneByPersistenceIdAndMaxSequenceNrAndMaxTimestamp _)
}
