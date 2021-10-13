/*
 * Copyright (C) 2014 - 2019 Dennis Vriend <https://github.com/dnvriend>
 * Copyright (C) 2019 - 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.postgres
package query.dao

import akka.NotUsed
import akka.persistence.PersistentRepr
import akka.persistence.postgres.config.ReadJournalConfig
import akka.persistence.postgres.journal.dao.{ BaseJournalDaoWithReadMessages, ByteArrayJournalSerializer }
import akka.persistence.postgres.serialization.FlowPersistentReprSerializer
import akka.persistence.postgres.tag.{ CachedTagIdResolver, SimpleTagDao, TagIdResolver }
import akka.serialization.Serialization
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import slick.basic.DatabasePublisher
import slick.jdbc.JdbcBackend._

import scala.collection.immutable._
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

trait BaseByteArrayReadJournalDao extends ReadJournalDao with BaseJournalDaoWithReadMessages {
  def db: Database
  def queries: ReadJournalQueries
  def serializer: FlowPersistentReprSerializer[JournalRow]
  def tagIdResolver: TagIdResolver
  def readJournalConfig: ReadJournalConfig

  import akka.persistence.postgres.db.ExtendedPostgresProfile.api._

  override def allPersistenceIdsSource(max: Long): Source[String, NotUsed] =
    Source.fromPublisher(db.stream(queries.allPersistenceIdsDistinct(max).result))

  override def eventsByTag(
      tag: String,
      offset: Long,
      maxOffset: Long,
      max: Long): Source[Try[(PersistentRepr, Long)], NotUsed] = {
    val publisher: Int => DatabasePublisher[JournalRow] = tagId =>
      db.stream(queries.eventsByTag(List(tagId), offset, maxOffset).result)
    Source
      .future(tagIdResolver.lookupIdFor(tag))
      .flatMapConcat(_.fold(Source.empty[JournalRow])(tagId => Source.fromPublisher(publisher(tagId))))
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
  }

  override def journalSequence(offset: Long, limit: Long): Source[Long, NotUsed] =
    Source.fromPublisher(db.stream(queries.orderingByOrdering(offset, limit).result))

  override def maxJournalSequence(): Future[Long] = {
    db.run(queries.maxOrdering.result)
  }
}

class ByteArrayReadJournalDao(
    val db: Database,
    val readJournalConfig: ReadJournalConfig,
    serialization: Serialization,
    val tagIdResolver: TagIdResolver)(implicit val ec: ExecutionContext, val mat: Materializer)
    extends BaseByteArrayReadJournalDao {
  val queries = new ReadJournalQueries(readJournalConfig)
  val serializer = new ByteArrayJournalSerializer(
    serialization,
    new CachedTagIdResolver(
      new SimpleTagDao(db, readJournalConfig.tagsTableConfiguration),
      readJournalConfig.tagsConfig))
}
