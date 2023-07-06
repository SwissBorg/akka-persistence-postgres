package akka.persistence.postgres.query.dao

import akka.persistence.postgres.config.ReadJournalConfig
import akka.persistence.postgres.journal.dao.{ ByteArrayJournalSerializer, FlatJournalTable }
import akka.persistence.postgres.tag.{ CachedTagIdResolver, SimpleTagDao, TagIdResolver }
import akka.serialization.Serialization
import akka.stream.Materializer
import slick.jdbc.JdbcBackend.Database

import scala.concurrent.ExecutionContext

class FlatReadJournalDao(
    val db: Database,
    val readJournalConfig: ReadJournalConfig,
    serialization: Serialization,
    val tagIdResolver: TagIdResolver)(implicit val ec: ExecutionContext, val mat: Materializer)
    extends BaseByteArrayReadJournalDao {
  val queries = new ReadJournalQueries(
    FlatJournalTable(readJournalConfig.journalTableConfiguration),
    readJournalConfig.includeDeleted)
  val serializer = new ByteArrayJournalSerializer(
    serialization,
    new CachedTagIdResolver(
      new SimpleTagDao(db, readJournalConfig.tagsTableConfiguration),
      readJournalConfig.tagsConfig))
}
