/*
 * Copyright (C) 2014 - 2019 Dennis Vriend <https://github.com/dnvriend>
 * Copyright (C) 2019 - 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.postgres

import akka.actor.ActorSystem
import akka.persistence.postgres.config.{ JournalConfig, ReadJournalConfig }
import akka.persistence.postgres.db.SlickExtension
import akka.persistence.postgres.query.javadsl.PostgresReadJournal
import akka.persistence.postgres.util.DropCreate
import akka.serialization.SerializationExtension
import akka.stream.{ Materializer, SystemMaterializer }
import akka.util.Timeout
import com.typesafe.config.{ Config, ConfigFactory, ConfigValue }
import org.scalatest.BeforeAndAfterAll

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

abstract class SharedActorSystemTestSpec(val config: Config) extends SimpleSpec with DropCreate with BeforeAndAfterAll {
  def this(config: String = "plain-application.conf", configOverrides: Map[String, ConfigValue] = Map.empty) =
    this(configOverrides.foldLeft(ConfigFactory.load(config)) { case (conf, (path, configValue)) =>
      conf.withValue(path, configValue)
    })

  implicit lazy val system: ActorSystem = ActorSystem("test", config)
  implicit lazy val mat: Materializer = SystemMaterializer(system).materializer

  implicit lazy val ec: ExecutionContext = system.dispatcher
  implicit val pc: PatienceConfig = PatienceConfig(timeout = 2.minutes)
  implicit val timeout = Timeout(1.minute)

  lazy val serialization = SerializationExtension(system)

  val cfg = config.getConfig("postgres-journal")
  val journalConfig = new JournalConfig(cfg)
  lazy val db = SlickExtension(system).database(cfg).database
  val readJournalConfig = new ReadJournalConfig(config.getConfig(PostgresReadJournal.Identifier))

  override protected def afterAll(): Unit = {
    super.afterAll()
    db.close()
    system.terminate().futureValue
  }
}
