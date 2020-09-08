/*
 * Copyright (C) 2014 - 2019 Dennis Vriend <https://github.com/dnvriend>
 * Copyright (C) 2019 - 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.postgres.query
package scaladsl

import akka.NotUsed
import akka.actor.{ ExtendedActorSystem, Scheduler }
import akka.persistence.postgres.config.ReadJournalConfig
import akka.persistence.postgres.db.SlickExtension
import akka.persistence.postgres.journal.dao.FlowControl
import akka.persistence.postgres.query.JournalSequenceActor.{ GetMaxOrderingId, MaxOrderingId }
import akka.persistence.postgres.query.dao.ReadJournalDao
import akka.persistence.postgres.tag.{ CachedTagIdResolver, SimpleTagDao, TagIdResolver }
import akka.persistence.query.scaladsl._
import akka.persistence.query.{ EventEnvelope, Offset, Sequence }
import akka.persistence.{ Persistence, PersistentRepr }
import akka.serialization.{ Serialization, SerializationExtension }
import akka.stream.scaladsl.{ Sink, Source }
import akka.stream.{ Materializer, SystemMaterializer }
import akka.util.Timeout
import com.typesafe.config.Config
import org.slf4j.LoggerFactory
import slick.jdbc.JdbcBackend._

import scala.collection.immutable._
import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

object PostgresReadJournal {
  final val Identifier = "postgres-read-journal"
}

class PostgresReadJournal(config: Config, configPath: String)(implicit val system: ExtendedActorSystem)
    extends ReadJournal
    with CurrentPersistenceIdsQuery
    with PersistenceIdsQuery
    with CurrentEventsByPersistenceIdQuery
    with EventsByPersistenceIdQuery
    with CurrentEventsByTagQuery
    with EventsByTagQuery {

  private val log = LoggerFactory.getLogger(this.getClass)

  implicit val ec: ExecutionContext = system.dispatcher
  implicit val mat: Materializer = SystemMaterializer(system).materializer
  val readJournalConfig = new ReadJournalConfig(config)

  private val writePluginId = config.getString("write-plugin")
  private val eventAdapters = Persistence(system).adaptersFor(writePluginId)

  val readJournalDao: ReadJournalDao = {
    val slickDb = SlickExtension(system).database(config)
    val db = slickDb.database
    val tagIdResolver = new CachedTagIdResolver(
      new SimpleTagDao(db, readJournalConfig.tagsTableConfiguration),
      readJournalConfig.tagsConfig)
    if (readJournalConfig.addShutdownHook && slickDb.allowShutdown) {
      system.registerOnTermination {
        db.close()
      }
    }
    val fqcn = readJournalConfig.pluginConfig.dao
    val args = Seq(
      (classOf[Database], db),
      (classOf[ReadJournalConfig], readJournalConfig),
      (classOf[Serialization], SerializationExtension(system)),
      (classOf[TagIdResolver], tagIdResolver),
      (classOf[ExecutionContext], ec),
      (classOf[Materializer], mat))
    system.dynamicAccess.createInstanceFor[ReadJournalDao](fqcn, args) match {
      case Success(dao)   => dao
      case Failure(cause) => throw cause
    }
  }

  // Started lazily to prevent the actor for querying the db if no eventsByTag queries are used
  private[query] lazy val journalSequenceActor = system.systemActorOf(
    JournalSequenceActor.props(readJournalDao, readJournalConfig.journalSequenceRetrievalConfiguration),
    s"$configPath.akka-persistence-postgres-journal-sequence-actor")
  private val delaySource =
    Source.tick(readJournalConfig.refreshInterval, 0.seconds, 0).take(1)

  /**
   * Same type of query as `persistenceIds` but the event stream
   * is completed immediately when it reaches the end of the "result set". Events that are
   * stored after the query is completed are not included in the event stream.
   */
  override def currentPersistenceIds(): Source[String, NotUsed] =
    readJournalDao.allPersistenceIdsSource(Long.MaxValue)

  /**
   * `persistenceIds` is used to retrieve a stream of all `persistenceId`s as strings.
   *
   * The stream guarantees that a `persistenceId` is only emitted once and there are no duplicates.
   * Order is not defined. Multiple executions of the same stream (even bounded) may emit different
   * sequence of `persistenceId`s.
   *
   * The stream is not completed when it reaches the end of the currently known `persistenceId`s,
   * but it continues to push new `persistenceId`s when new events are persisted.
   * Corresponding query that is completed when it reaches the end of the currently
   * known `persistenceId`s is provided by `currentPersistenceIds`.
   */
  override def persistenceIds(): Source[String, NotUsed] =
    Source
      .repeat(0)
      .flatMapConcat(_ => delaySource.flatMapConcat(_ => currentPersistenceIds()))
      .statefulMapConcat[String] { () =>
        var knownIds = Set.empty[String]
        def next(id: String): Iterable[String] = {
          val xs = Set(id).diff(knownIds)
          knownIds += id
          xs
        }
        id => next(id)
      }

  private def adaptEvents(repr: PersistentRepr): Seq[PersistentRepr] = {
    val adapter = eventAdapters.get(repr.payload.getClass)
    adapter.fromJournal(repr.payload, repr.manifest).events.map(repr.withPayload)
  }

  /**
   * Same type of query as `eventsByPersistenceId` but the event stream
   * is completed immediately when it reaches the end of the "result set". Events that are
   * stored after the query is completed are not included in the event stream.
   */
  override def currentEventsByPersistenceId(
      persistenceId: String,
      fromSequenceNr: Long,
      toSequenceNr: Long): Source[EventEnvelope, NotUsed] =
    eventsByPersistenceIdSource(persistenceId, fromSequenceNr, toSequenceNr, None)

  /**
   * `eventsByPersistenceId` is used to retrieve a stream of events for a particular persistenceId.
   *
   * The `EventEnvelope` contains the event and provides `persistenceId` and `sequenceNr`
   * for each event. The `sequenceNr` is the sequence number for the persistent actor with the
   * `persistenceId` that persisted the event. The `persistenceId` + `sequenceNr` is an unique
   * identifier for the event.
   *
   * `fromSequenceNr` and `toSequenceNr` can be specified to limit the set of returned events.
   * The `fromSequenceNr` and `toSequenceNr` are inclusive.
   *
   * The `EventEnvelope` also provides the `offset` that corresponds to the `ordering` column in
   * the Journal table. The `ordering` is a sequential id number that uniquely identifies the
   * position of each event, also across different `persistenceId`. The `Offset` type is
   * `akka.persistence.query.Sequence` with the `ordering` as the offset value. This is the
   * same `ordering` number as is used in the offset of the `eventsByTag` query.
   *
   * The returned event stream is ordered by `sequenceNr`.
   *
   * Causality is guaranteed (`sequenceNr`s of events for a particular `persistenceId` are always ordered
   * in a sequence monotonically increasing by one). Multiple executions of the same bounded stream are
   * guaranteed to emit exactly the same stream of events.
   *
   * The stream is not completed when it reaches the end of the currently stored events,
   * but it continues to push new events when new events are persisted.
   * Corresponding query that is completed when it reaches the end of the currently
   * stored events is provided by `currentEventsByPersistenceId`.
   */
  override def eventsByPersistenceId(
      persistenceId: String,
      fromSequenceNr: Long,
      toSequenceNr: Long): Source[EventEnvelope, NotUsed] =
    eventsByPersistenceIdSource(
      persistenceId,
      fromSequenceNr,
      toSequenceNr,
      Some(readJournalConfig.refreshInterval -> system.scheduler))

  private def eventsByPersistenceIdSource(
      persistenceId: String,
      fromSequenceNr: Long,
      toSequenceNr: Long,
      refreshInterval: Option[(FiniteDuration, Scheduler)]): Source[EventEnvelope, NotUsed] = {
    val batchSize = readJournalConfig.maxBufferSize
    readJournalDao
      .messagesWithBatch(persistenceId, fromSequenceNr, toSequenceNr, batchSize, refreshInterval)
      .mapAsync(1)(reprAndOrdNr => Future.fromTry(reprAndOrdNr))
      .mapConcat {
        case (repr, ordNr) =>
          adaptEvents(repr).map(_ -> ordNr)
      }
      .map {
        case (repr, ordNr) =>
          EventEnvelope(Sequence(ordNr), repr.persistenceId, repr.sequenceNr, repr.payload, repr.timestamp)
      }
  }

  /**
   * Same type of query as `eventsByTag` but the event stream
   * is completed immediately when it reaches the end of the "result set". Events that are
   * stored after the query is completed are not included in the event stream.
   */
  override def currentEventsByTag(tag: String, offset: Offset): Source[EventEnvelope, NotUsed] =
    currentEventsByTag(tag, offset.value)

  private def currentJournalEventsByTag(
      tag: String,
      offset: Long,
      max: Long,
      latestOrdering: MaxOrderingId): Source[EventEnvelope, NotUsed] = {
    if (latestOrdering.maxOrdering < offset) Source.empty
    else {
      readJournalDao
        .eventsByTag(tag, offset, math.min(offset + max, latestOrdering.maxOrdering), max)
        .mapAsync(1)(Future.fromTry)
        .mapConcat {
          case (repr, ordering) =>
            adaptEvents(repr).map(r =>
              EventEnvelope(Sequence(ordering), r.persistenceId, r.sequenceNr, r.payload, r.timestamp))
        }
    }
  }

  /**
   * @param terminateAfterOffset If None, the stream never completes. If a Some, then the stream will complete once a
   *                             query has been executed which might return an event with this offset (or a higher offset).
   *                             The stream may include offsets higher than the value in terminateAfterOffset, since the last batch
   *                             will be returned completely.
   */
  private def eventsByTag(
      tag: String,
      offset: Long,
      terminateAfterOffset: Option[Long]): Source[EventEnvelope, NotUsed] = {
    import FlowControl._
    import akka.pattern.ask
    implicit val askTimeout: Timeout = Timeout(readJournalConfig.journalSequenceRetrievalConfiguration.askTimeout)
    val batchSize = readJournalConfig.maxBufferSize

    Source
      .unfoldAsync[(Long, FlowControl), Seq[EventEnvelope]]((math.max(0L, offset), Continue)) {
        case (from, control) =>
          def retrieveNextBatch() = {
            for {
              queryUntil <- journalSequenceActor.ask(GetMaxOrderingId).mapTo[MaxOrderingId]
              xs <- currentJournalEventsByTag(tag, from, batchSize, queryUntil).runWith(Sink.seq)
            } yield {
              val to = from + batchSize
              val highestOffset = xs.map(_.offset.value) match {
                case Nil     => to
                case offsets => offsets.max
              }
              val hasMoreEvents = {
                highestOffset < queryUntil.maxOrdering
              }
              val nextControl: FlowControl =
                terminateAfterOffset match {
                  // we may stop if target is behind queryUntil and we don't have more events to fetch
                  case Some(target) if !hasMoreEvents && target <= queryUntil.maxOrdering => Stop
                  // We may also stop if we have found an event with an offset >= target
                  case Some(target) if xs.exists(_.offset.value >= target) => Stop

                  // otherwise, disregarding if Some or None, we must decide how to continue
                  case _ =>
                    if (hasMoreEvents) Continue else ContinueDelayed
                }

              val nextStartingOffset = (xs, queryUntil.maxOrdering) match {
                case (Nil, 0L) =>
                  /* If `maxOrdering` is not known yet or journal is empty (min value for Postgres' bigserial is 1)
                   * then we should not move the query window forward. Otherwise we might miss (skip) some events.
                   * By setting nextStartingOffset to `from` we wait for either maxOrdering to be discovered or first
                   * event to be persisted in the journal. */
                  from
                case (Nil, maxOrdering) =>
                  /* If no events matched the tag between `from` and `to` (`from + batchSize`) and `maxOrdering` then
                   * there is no need to execute the exact same query again. We can continue querying from `to`,
                   * which will save some load on the db. */
                  math.min(to, maxOrdering)
                case _ =>
                  // Continue querying from the largest offset
                  highestOffset
              }

              log.trace(
                s"tag = $tag => ($nextStartingOffset, $nextControl), [highestOffset = $highestOffset, maxOrdering = ${queryUntil.maxOrdering}, hasMoreEvents = $hasMoreEvents, results = ${xs.size}, from = $from]")
              Some((nextStartingOffset, nextControl), xs)
            }
          }

          control match {
            case Stop     => Future.successful(None)
            case Continue => retrieveNextBatch()
            case ContinueDelayed =>
              akka.pattern.after(readJournalConfig.refreshInterval, system.scheduler)(retrieveNextBatch())
          }
      }
      .mapConcat(identity)
  }

  def currentEventsByTag(tag: String, offset: Long): Source[EventEnvelope, NotUsed] =
    Source.future(readJournalDao.maxJournalSequence()).flatMapConcat { maxOrderingInDb =>
      eventsByTag(tag, offset, terminateAfterOffset = Some(maxOrderingInDb))
    }

  /**
   * Query events that have a specific tag.
   *
   * The consumer can keep track of its current position in the event stream by storing the
   * `offset` and restart the query from a given `offset` after a crash/restart.
   * The offset is exclusive, i.e. the event corresponding to the given `offset` parameter is not
   * included in the stream.
   *
   * For akka-persistence-postgres the `offset` corresponds to the `ordering` column in the Journal table.
   * The `ordering` is a sequential id number that uniquely identifies the position of each event within
   * the event stream. The `Offset` type is `akka.persistence.query.Sequence` with the `ordering` as the
   * offset value.
   *
   * The returned event stream is ordered by `offset`.
   *
   * In addition to the `offset` the `EventEnvelope` also provides `persistenceId` and `sequenceNr`
   * for each event. The `sequenceNr` is the sequence number for the persistent actor with the
   * `persistenceId` that persisted the event. The `persistenceId` + `sequenceNr` is an unique
   * identifier for the event.
   *
   * The stream is not completed when it reaches the end of the currently stored events,
   * but it continues to push new events when new events are persisted.
   * Corresponding query that is completed when it reaches the end of the currently
   * stored events is provided by [[CurrentEventsByTagQuery#currentEventsByTag]].
   */
  override def eventsByTag(tag: String, offset: Offset): Source[EventEnvelope, NotUsed] =
    eventsByTag(tag, offset.value)

  def eventsByTag(tag: String, offset: Long): Source[EventEnvelope, NotUsed] =
    eventsByTag(tag, offset, terminateAfterOffset = None)
}
