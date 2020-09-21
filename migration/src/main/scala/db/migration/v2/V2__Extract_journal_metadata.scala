package db.migration.v2

import java.nio.charset.StandardCharsets

import akka.actor.{ActorRef, ActorSystem, ExtendedActorSystem}
import akka.persistence.postgres.config.JournalConfig
import akka.persistence.postgres.db.{ExtendedPostgresProfile, SlickExtension}
import akka.persistence.postgres.journal.dao.{ByteArrayJournalSerializer, FlatJournalTable, JournalQueries}
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

class V2__Extract_journal_metadata extends BaseJavaMigration {

  @throws[Exception]
  override def migrate(context: Context): Unit = {
    val config = ConfigFactory.load("general.conf")

    val system = ActorSystem("migration-tool-AS", config)
    import system.dispatcher
    implicit val met: Materializer = SystemMaterializer(system).materializer

    val migrationConf = config.getConfig("akka-persistence-postgres.migration")

    val serialization = SerializationExtension(system)
    val slickDb = SlickExtension(system).database(migrationConf.withFallback(config.getConfig("postgres-journal")))
    val db = slickDb.database

    val migrationBatchSize = migrationConf.getLong("v1.batchSize")
    val journalConfig = new JournalConfig(config.getConfig("postgres-journal"))

    val queries = new JournalQueries(FlatJournalTable(journalConfig.journalTableConfiguration))

    import ExtendedPostgresProfile.api._

    implicit val GetByteArr: GetResult[Array[Byte]] = GetResult(_.nextBytes())
    implicit val SetByteArr: SetParameter[Array[Byte]] = SetParameter((arr, pp) => pp.setBytes(arr))
    implicit val SetJson: SetParameter[Json] = SetParameter(
      (json, pp) => pp.setString(json.printWith(Printer.noSpaces)))

    val deserializer = new OldDeserializer(serialization)
    val serializer = new ByteArrayJournalSerializer(
      serialization,
      new CachedTagIdResolver(new SimpleTagDao(db, journalConfig.tagsTableConfiguration), journalConfig.tagsConfig))

    println(Await.result(db.run(sqlu"ALTER TABLE journal ADD COLUMN metadata jsonb"), 3.seconds))

//    val n = 0
    val rows = Await.result(
      db.run(
        sql"select ordering, persistence_id, sequence_number, message from journal" // where ordering >= #${n * migrationBatchSize} and ordering < #${(n + 1) * migrationBatchSize}"
          .as[(Long, String, Long, Array[Byte])]),
      5.seconds)
    val updateRes = rows.map {
      case (ordering, persistenceId, sequenceNumber, message) =>
        println(s"read msg for ordering = $ordering")
        val deserializedFut = for {
          pr <- Future.fromTry(deserializer.deserialize(message))
          ser <- serializer.serialize(pr)
        } yield (ordering, persistenceId, sequenceNumber, ser.message, ser.metadata)
        deserializedFut.onComplete {
          case Failure(ex) =>
            println(
              s"An error occurred during deserialization od message with ordering = $ordering"
            ) // (batch $n of ${maxOrdering / migrationBatchSize})")
            ex.printStackTrace()
          case Success(row) => println(s"Successfully deserialized msg $row")
        }
        deserializedFut.flatMap {
          case (_, persistenceId, sequenceNumber, message, metadata) =>
            val fut = db.run(queries.update(persistenceId, sequenceNumber, message, metadata))
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
