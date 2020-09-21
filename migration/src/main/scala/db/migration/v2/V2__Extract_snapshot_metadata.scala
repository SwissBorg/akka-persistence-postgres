package db.migration.v2

import java.nio.charset.StandardCharsets

import akka.actor.{ActorRef, ActorSystem, ExtendedActorSystem}
import akka.persistence.SnapshotMetadata
import akka.persistence.postgres.config.{JournalConfig, SnapshotConfig}
import akka.persistence.postgres.db.{ExtendedPostgresProfile, SlickExtension}
import akka.persistence.postgres.journal.dao.{ByteArrayJournalSerializer, FlatJournalTable, JournalQueries}
import akka.persistence.postgres.snapshot.dao.{ByteArraySnapshotSerializer, SnapshotQueries}
import akka.persistence.postgres.tag.{CachedTagIdResolver, SimpleTagDao}
import akka.serialization.{Serialization, SerializationExtension, SerializerWithStringManifest}
import akka.stream.{ActorMaterializer, Materializer, SystemMaterializer}
import akka.stream.scaladsl.{Sink, Source}
import com.typesafe.config.ConfigFactory
import io.circe.{Json, Printer}
import org.flywaydb.core.api.migration.{BaseJavaMigration, Context}
import slick.jdbc.{GetResult, SetParameter}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}

class V3__Extract_snapshot_metadata extends BaseJavaMigration {

  @throws[Exception]
  override def migrate(context: Context): Unit = {
    val config = ConfigFactory.load("general.conf")

    val system = ActorSystem("migration-tool-AS", config)
    import system.dispatcher
    implicit val met: Materializer = SystemMaterializer(system).materializer

    val migrationConf = config.getConfig("akka-persistence-postgres.migration")

    val serialization = SerializationExtension(system)
    val slickDb = SlickExtension(system).database(migrationConf.withFallback(config.getConfig("postgres-snapshot-store")))
    val db = slickDb.database

    val migrationBatchSize = migrationConf.getLong("v1.batchSize")
    val snapshotConfig = new SnapshotConfig(config.getConfig("postgres-snapshot-store"))

    val queries = new SnapshotQueries(snapshotConfig.snapshotTableConfiguration)

    import ExtendedPostgresProfile.api._

    implicit val GetByteArr: GetResult[Array[Byte]] = GetResult(_.nextBytes())
    implicit val SetByteArr: SetParameter[Array[Byte]] = SetParameter((arr, pp) => pp.setBytes(arr))
    implicit val SetJson: SetParameter[Json] = SetParameter(
      (json, pp) => pp.setString(json.printWith(Printer.noSpaces)))

    val deserializer = new OldSnapshotDeserializer(serialization)
    val serializer = new ByteArraySnapshotSerializer(serialization)

//    println(Await.result(db.run(sqlu"ALTER TABLE snapshot ADD COLUMN metadata jsonb"), 3.seconds))

    //    val n = 0
    val rows = Await.result(
      db.run(
        sql"select persistence_id, sequence_number, created, snapshot from snapshot" // where ordering >= #${n * migrationBatchSize} and ordering < #${(n + 1) * migrationBatchSize}"
          .as[(String, Long, Long, Array[Byte])]),
      5.seconds)
    val updateRes = rows.map {
      case (persistenceId, sequenceNumber, create, snapshot) =>
        val deserializedFut = for {
          sr <- deserializer.deserialize(snapshot)
          ser <- serializer.serialize(SnapshotMetadata(persistenceId, sequenceNumber, create), sr)
        } yield ser
        deserializedFut match {
          case Failure(ex) =>
            println(
              s"An error occurred while converting snapshot (pid = $persistenceId, seqNum = $sequenceNumber)"
            )
            ex.printStackTrace()
          case Success(row) => println(s"Successfully converted msg $row")
        }

        Future.fromTry(deserializedFut).flatMap { snapshotRow =>
            val fut = db.run(queries.insertOrUpdate(snapshotRow))
            fut.onComplete(r => println(s"Update resulted with: $r"))
            fut
        }
    }

    val r = Future.sequence(updateRes)
    r.flatMap(_ => system.terminate()).onComplete(_ => {
      db.close()
    })

    Await.result(r, 1.minute)
  }

}
