package akka.persistence.postgres
package journal.dao

import akka.persistence.postgres.config.JournalConfig
import akka.persistence.postgres.tag.{ CachedTagIdResolver, SimpleTagDao }
import akka.serialization.Serialization
import akka.stream.Materializer
import slick.jdbc.JdbcBackend._

import scala.concurrent.ExecutionContext

class FlatJournalDao(val db: Database, val journalConfig: JournalConfig, serialization: Serialization)(
    implicit val ec: ExecutionContext,
    val mat: Materializer)
    extends BaseByteArrayJournalDao {
  val queries = new JournalQueries(
    FlatJournalTable(journalConfig.journalTableConfiguration),
    JournalPersistenceIdsTable(journalConfig.journalPersistenceIdsTableConfiguration))
  val tagDao = new SimpleTagDao(db, journalConfig.tagsTableConfiguration)
  val eventTagConverter = new CachedTagIdResolver(tagDao, journalConfig.tagsConfig)
  val serializer = new ByteArrayJournalSerializer(serialization, eventTagConverter)
}
