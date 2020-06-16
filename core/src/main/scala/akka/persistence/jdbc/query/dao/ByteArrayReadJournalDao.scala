/*
 * Copyright (C) 2014 - 2019 Dennis Vriend <https://github.com/dnvriend>
 * Copyright (C) 2019 - 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.jdbc
package query.dao

import akka.NotUsed
import akka.persistence.PersistentRepr
import akka.persistence.jdbc.config.ReadJournalConfig
import akka.persistence.jdbc.journal.dao.BaseJournalDaoWithReadMessages
import akka.persistence.jdbc.journal.dao.ByteArrayJournalSerializer
import akka.persistence.jdbc.query.dao.TagFilterFlow.perfectlyMatchTag
import akka.persistence.jdbc.serialization.FlowPersistentReprSerializer
import akka.serialization.Serialization
import akka.stream.Materializer
import akka.stream.scaladsl.{ Flow, Source }
import slick.jdbc.JdbcProfile
import slick.jdbc.GetResult
import slick.jdbc.JdbcBackend._
import scala.collection.immutable._
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Failure
import scala.util.Success
import scala.util.Try

trait BaseByteArrayReadJournalDao extends ReadJournalDao with BaseJournalDaoWithReadMessages {
  def db: Database
  val profile: JdbcProfile
  def queries: ReadJournalQueries
  def serializer: FlowPersistentReprSerializer[JournalRow]
  def readJournalConfig: ReadJournalConfig

  import profile.api._

  override def allPersistenceIdsSource(max: Long): Source[String, NotUsed] =
    Source.fromPublisher(db.stream(queries.allPersistenceIdsDistinct(max).result))

  override def eventsByTag(
      tag: String,
      offset: Long,
      maxOffset: Long,
      max: Long): Source[Try[(PersistentRepr, Set[String], Long)], NotUsed] = {

    val publisher = db.stream(queries.eventsByTag(s"%$tag%", offset, maxOffset, max).result)
    // applies workaround for https://github.com/akka/akka-persistence-jdbc/issues/168
    Source
      .fromPublisher(publisher)
      .via(perfectlyMatchTag(tag, readJournalConfig.pluginConfig.tagSeparator))
      .via(serializer.deserializeFlow)
  }

  override def messages(
      persistenceId: String,
      fromSequenceNr: Long,
      toSequenceNr: Long,
      max: Long): Source[Try[(PersistentRepr, Long)], NotUsed] = {
    Source
      .fromPublisher(db.stream(queries.messagesQuery(persistenceId, fromSequenceNr, toSequenceNr, max).result))
      .via(serializer.deserializeFlow)
      .map {
        case Success((repr, _, ordering)) => Success(repr -> ordering)
        case Failure(e)                   => Failure(e)
      }
  }

  override def journalSequence(offset: Long, limit: Long): Source[Long, NotUsed] =
    Source.fromPublisher(db.stream(queries.journalSequenceQuery(offset, limit).result))

  override def maxJournalSequence(): Future[Long] = {
    db.run(queries.maxJournalSequenceQuery.result)
  }
}

object TagFilterFlow {
  /*
   * Returns a Flow that retains every event with tags that perfectly match passed tag.
   * This is a workaround for bug https://github.com/akka/akka-persistence-jdbc/issues/168
   */
  private[dao] def perfectlyMatchTag(tag: String, separator: String) =
    Flow[JournalRow].filter(_.tags.exists(tags => tags.split(separator).contains(tag)))
}

class ByteArrayReadJournalDao(
    val db: Database,
    val profile: JdbcProfile,
    val readJournalConfig: ReadJournalConfig,
    serialization: Serialization)(implicit val ec: ExecutionContext, val mat: Materializer)
    extends BaseByteArrayReadJournalDao {
  val queries = new ReadJournalQueries(profile, readJournalConfig)
  val serializer = new ByteArrayJournalSerializer(serialization, readJournalConfig.pluginConfig.tagSeparator)
}
