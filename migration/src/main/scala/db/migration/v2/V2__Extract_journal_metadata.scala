package db.migration.v2

import java.nio.charset.StandardCharsets

import akka.Done
import akka.actor.{ ActorRef, ActorSystem, ExtendedActorSystem }
import akka.persistence.SnapshotMetadata
import akka.persistence.postgres.config.{ JournalConfig, SnapshotConfig }
import akka.persistence.postgres.db.{ ExtendedPostgresProfile, SlickExtension }
import akka.persistence.postgres.journal.dao._
import akka.serialization.{ Serialization, SerializationExtension, SerializerWithStringManifest }
import akka.stream.scaladsl.Source
import akka.stream.{ Materializer, SystemMaterializer }
import com.typesafe.config.{ Config, ConfigFactory }
import io.circe.{ Json, Printer }
import org.flywaydb.core.api.migration.{ BaseJavaMigration, Context }
import org.slf4j.{ Logger, LoggerFactory }
import slick.jdbc.{ GetResult, JdbcBackend, SetParameter }
import slick.lifted.TableQuery

import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

class V2__Extract_journal_metadata extends BaseJavaMigration {

  val log: Logger = LoggerFactory.getLogger(this.getClass)

  val config: Config = ConfigFactory.load()

  val migrationConf: Config = config.getConfig("akka-persistence-postgres.migration")
  val journalConfig = new JournalConfig(config.getConfig("postgres-journal"))

  val snapshotConfig = new SnapshotConfig(config.getConfig("postgres-snapshot-store"))

  lazy val journalQueries: NewJournalQueries = {
    val daoFqcn = journalConfig.pluginConfig.dao

    import akka.persistence.postgres.journal.dao.FlatJournalDao

    val table: TableQuery[NewJournalTable] =
      if (daoFqcn == classOf[FlatJournalDao].getName) NewFlatJournalTable(journalConfig.journalTableConfiguration)
      else if (daoFqcn == classOf[PartitionedJournalDao].getName)
        NewPartitionedJournalTable(journalConfig.journalTableConfiguration)
      else if (daoFqcn == classOf[NestedPartitionsJournalDao].getName)
        NewNestedPartitionsJournalTable(journalConfig.journalTableConfiguration)
      else throw new IllegalStateException(s"Unsupported DAO class - '$daoFqcn'")

    new NewJournalQueries(table)
  }

  lazy val snapshotQueries = new NewSnapshotQueries(snapshotConfig.snapshotTableConfiguration)

  import ExtendedPostgresProfile.api._

  implicit val GetByteArr: GetResult[Array[Byte]] = GetResult(_.nextBytes())
  implicit val SetByteArr: SetParameter[Array[Byte]] = SetParameter((arr, pp) => pp.setBytes(arr))
  implicit val SetJson: SetParameter[Json] = SetParameter((json, pp) => pp.setString(json.printWith(Printer.noSpaces)))

  val migrationBatchSize: Long =
    if (config.hasPath("v2.batchSize"))
      migrationConf.getLong("v2.batchSize")
    else 500L

  @throws[Exception]
  override def migrate(context: Context): Unit = {
    val system = ActorSystem("migration-tool-AS", config)
    import system.dispatcher
    implicit val met: Materializer = SystemMaterializer(system).materializer

    val slickDb = SlickExtension(system).database(migrationConf)
    val db = slickDb.database

    val serialization = SerializationExtension(system)

    val migrationRes = for {
      _ <- migrateJournal(db, serialization)
      _ <- migrateSnapshots(db, serialization)
    } yield Done

    migrationRes.onComplete(((r: Try[Done]) =>
      r match {
        case Failure(exception) => log.error(s"Metadata extraction has failed", exception)
        case Success(_)         => log.info(s"Metadata extraction is now completed")
      }).andThen(_ => system.terminate().map(_ => db.close())))

    Await.result(migrationRes, Duration.Inf)
  }

  def migrateJournal(db: JdbcBackend.Database, serialization: Serialization)(
      implicit ec: ExecutionContext,
      mat: Materializer): Future[Done] = {
    val deserializer = new OldDeserializer(serialization)
    // because we neither rely on nor touch tags column
    val serializer = new NewJournalSerializer(serialization)

    log.info(s"Start migrating journal entries...")

    val ddl = db.run(sqlu"ALTER TABLE journal ADD COLUMN metadata jsonb")

    val eventsPublisher =
      db.stream(sql"select persistence_id, sequence_number, message from journal".as[(String, Long, Array[Byte])])

    val dml = Source
      .fromPublisher(eventsPublisher)
      .mapAsync(8) {
        case (persistenceId, sequenceNumber, message) =>
          for {
            pr <- Future.fromTry(deserializer.deserialize(message))
            (message, metadata) <- serializer.serialize(pr)
          } yield (persistenceId, sequenceNumber, message, metadata)
      }
      .map {
        case (persistenceId, sequenceNumber, message, metadata) =>
          journalQueries.update(persistenceId, sequenceNumber, message, metadata)
      }
      .batch(migrationBatchSize, List(_))(_ :+ _)
      .foldAsync(0L) { (cnt, actions) =>
        val numOfEvents = actions.length
        log.info(s"Updating $numOfEvents events...")
        db.run(DBIO.sequence(actions).transactionally).map(_ => cnt + numOfEvents)
      }

    for {
      _ <- ddl
      cnt <- dml.runReduce(_ + _)
    } yield {
      log.info(s"Journal metadata extraction completed - $cnt events have been successfully updated")
      Done
    }
  }

  def migrateSnapshots(db: JdbcBackend.Database, serialization: Serialization)(
      implicit ec: ExecutionContext,
      mat: Materializer): Future[Done] = {
    val deserializer = new OldSnapshotDeserializer(serialization)
    val serializer = new NewSnapshotSerializer(serialization)

    log.info(s"Start migrating snapshots...")

    val ddl = db.run(sqlu"ALTER TABLE snapshot ADD COLUMN metadata jsonb")

    val eventsPublisher =
      db.stream {
        sql"select persistence_id, sequence_number, created, snapshot from snapshot"
          .as[(String, Long, Long, Array[Byte])]
      }

    val dml = Source
      .fromPublisher(eventsPublisher)
      .mapAsync(8) {
        case (persistenceId, sequenceNumber, created, snapshot) =>
          Future.fromTry {
            for {
              oldSnapshotRow <- deserializer.deserialize(snapshot)
              newSnapshotRow <- serializer.serialize(
                SnapshotMetadata(persistenceId, sequenceNumber, created),
                oldSnapshotRow)
            } yield newSnapshotRow
          }
      }
      .map(snapshotQueries.insertOrUpdate)
      .batch(migrationBatchSize, List(_))(_ :+ _)
      .foldAsync(0L) { (cnt, actions) =>
        val numOfEvents = actions.length
        log.info(s"Updating $numOfEvents events...")
        db.run(DBIO.sequence(actions).transactionally).map(_ => cnt + numOfEvents)
      }

    for {
      _ <- ddl
      cnt <- dml.runReduce(_ + _)
    } yield {
      log.info(s"Snapshot metadata extraction completed - $cnt events have been successfully updated")
      Done
    }
  }

}

// Test serializers below - TO BE REMOVED

case object ResetCounter

case class Cmd(mode: String, payload: Int)

class CmdSerializer extends SerializerWithStringManifest {
  override def identifier: Int = 293562

  override def manifest(o: AnyRef): String = ""

  override def toBinary(o: AnyRef): Array[Byte] =
    o match {
      case Cmd(mode, payload) =>
        s"$mode|$payload".getBytes(StandardCharsets.UTF_8)
      case _ =>
        throw new IllegalArgumentException(s"Can't serialize object of type ${o.getClass} in [${getClass.getName}]")
    }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
    val str = new String(bytes, StandardCharsets.UTF_8)
    val i = str.indexOf('|')
    Cmd(str.substring(0, i), str.substring(i + 1).toInt)
  }
}

final case class TestPayload(ref: ActorRef)

class TestSerializer(system: ExtendedActorSystem) extends SerializerWithStringManifest {
  def identifier: Int = 666
  def manifest(o: AnyRef): String = o match {
    case _: TestPayload => "A"
  }
  def toBinary(o: AnyRef): Array[Byte] = o match {
    case TestPayload(ref) =>
      val refStr = Serialization.serializedActorPath(ref)
      refStr.getBytes(StandardCharsets.UTF_8)
  }
  def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
    manifest match {
      case "A" =>
        val refStr = new String(bytes, StandardCharsets.UTF_8)
        val ref = system.provider.resolveActorRef(refStr)
        TestPayload(ref)
    }
  }
}
