package akka.persistence.postgres.query

import akka.actor.ActorSystem
import akka.persistence.PersistentRepr
import akka.persistence.postgres.journal.dao.{ ByteArrayJournalSerializer, JournalQueries }
import akka.persistence.postgres.tag.{ CachedTagIdResolver, SimpleTagDao }
import akka.persistence.postgres.util.Schema
import akka.persistence.postgres.util.Schema.SchemaType
import akka.persistence.query.NoOffset
import akka.serialization.SerializationExtension
import akka.stream.scaladsl.{ Sink, Source }
import com.typesafe.config.{ ConfigValue, ConfigValueFactory }

import scala.concurrent.duration._

object CurrentEventsByTagWithGapsTest {
  private val maxBufferSize = 10000
  private val refreshInterval = 500.milliseconds

  val configOverrides: Map[String, ConfigValue] = Map(
    "postgres-read-journal.max-buffer-size" -> ConfigValueFactory.fromAnyRef(maxBufferSize.toString),
    "postgres-read-journal.refresh-interval" -> ConfigValueFactory.fromAnyRef(refreshInterval.toString()))
}

class CurrentEventsByTagWithGapsTest
    extends QueryTestSpec(
      s"${Schema.Partitioned.resourceNamePrefix}-shared-db-application.conf",
      CurrentEventsByTagWithGapsTest.configOverrides) {

  // We are using Partitioned variant because it does not override values for an `ordering` field
  override val schemaType: SchemaType = Schema.Partitioned

  it should "read all events regardless of the ordering gaps" in {

    withActorSystem { implicit system: ActorSystem =>
      import system.dispatcher
      withDatabase { db =>
        import akka.persistence.postgres.db.ExtendedPostgresProfile.api._
        db.run {
          val tableConf = journalConfig.journalTableConfiguration
          val schema = tableConf.schemaName.getOrElse("public")
          val partitionPrefix = journalConfig.partitionsConfig.prefix
          val partitionName = s"$schema.${partitionPrefix}_1"
          val journalTableName = s"$schema.${tableConf.tableName}"
          sqlu"""CREATE TABLE IF NOT EXISTS #$partitionName PARTITION OF #$journalTableName FOR VALUES FROM (0) TO (#${Long.MaxValue})"""
        }.futureValue

        val journalTable = schemaType.table(journalConfig.journalTableConfiguration)
        val journalPersistenceIdsTable =
          schemaType.persistenceIdsTable(journalConfig.journalPersistenceIdsTableConfiguration)
        val journalQueries = new JournalQueries(journalTable, journalPersistenceIdsTable)
        val journalOps = new JavaDslPostgresReadJournalOperations(system)
        val tag = "testTag"

        val tagDao = new SimpleTagDao(db, journalConfig.tagsTableConfiguration)
        val serializer = new ByteArrayJournalSerializer(
          SerializationExtension(system),
          new CachedTagIdResolver(tagDao, journalConfig.tagsConfig))

        val numElements = 1000
        val gapSize = 10000
        val firstElement = 100000000
        val lastElement = firstElement + (numElements * gapSize)
        val expectedTotalNumElements = 1 + numElements
        Source
          .fromIterator(() => (firstElement to lastElement by gapSize).iterator)
          .flatMapConcat { id =>
            Source.future {
              serializer.serialize(PersistentRepr("Hello", id, "perId"), Set(tag)).map(_.copy(ordering = id))
            }
          }
          .grouped(10000)
          .mapAsync(4) { rows =>
            db.run(journalQueries.writeJournalRows(rows))
          }
          .runWith(Sink.ignore)
          .futureValue

        journalOps.withCurrentEventsByTag(5.minutes)(tag, NoOffset) { tp =>
          val allEvents = tp.toStrict(atMost = 5.minutes)
          allEvents.size should equal(expectedTotalNumElements)
        }

      }
    }

  }

}
