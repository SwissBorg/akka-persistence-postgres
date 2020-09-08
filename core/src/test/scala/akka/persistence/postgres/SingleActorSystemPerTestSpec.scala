/*
 * Copyright (C) 2014 - 2019 Dennis Vriend <https://github.com/dnvriend>
 * Copyright (C) 2019 - 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.postgres

import akka.actor.ActorSystem
import akka.persistence.postgres.config.{ JournalConfig, ReadJournalConfig, SlickConfiguration }
import akka.persistence.postgres.db.SlickDatabase
import akka.persistence.postgres.query.javadsl.PostgresReadJournal
import akka.persistence.postgres.util.DropCreate
import akka.util.Timeout
import com.typesafe.config.{ Config, ConfigFactory, ConfigValue }
import org.scalatest.BeforeAndAfterEach
import slick.jdbc.JdbcBackend.Database

import scala.concurrent.duration._

abstract class SingleActorSystemPerTestSpec(val config: Config)
    extends SimpleSpec
    with DropCreate
    with BeforeAndAfterEach {
  def this(config: String = "plain-application.conf", configOverrides: Map[String, ConfigValue] = Map.empty) =
    this(configOverrides.foldLeft(ConfigFactory.load(config)) {
      case (conf, (path, configValue)) => conf.withValue(path, configValue)
    })

  implicit val pc: PatienceConfig = PatienceConfig(timeout = 1.minute)
  implicit val timeout: Timeout = Timeout(1.minute)

  val cfg: Config = config.getConfig("postgres-journal")
  val journalConfig = new JournalConfig(cfg)
  val readJournalConfig = new ReadJournalConfig(config.getConfig(PostgresReadJournal.Identifier))

  // The db is initialized in the before and after each bocks
  var dbOpt: Option[Database] = None
  def db: Database = {
    dbOpt.getOrElse {
      val newDb = if (cfg.hasPath("slick.profile")) {
        SlickDatabase.database(cfg, new SlickConfiguration(cfg.getConfig("slick")), "slick.db")
      } else
        SlickDatabase.database(
          config,
          new SlickConfiguration(config.getConfig("akka-persistence-postgres.shared-databases.slick")),
          "akka-persistence-postgres.shared-databases.slick.db")

      dbOpt = Some(newDb)
      newDb
    }
  }

  def closeDb(): Unit = {
    dbOpt.foreach(_.close())
    dbOpt = None
  }

  override protected def afterEach(): Unit = {
    super.afterEach()
    closeDb()
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    closeDb()
  }

  def withActorSystem(f: ActorSystem => Unit): Unit = {
    implicit val system: ActorSystem = ActorSystem("test", config)
    f(system)
    system.terminate().futureValue
  }

  def withActorSystem(config: Config = config)(f: ActorSystem => Unit): Unit = {
    implicit val system: ActorSystem = ActorSystem("test", config)
    f(system)
    system.terminate().futureValue
  }
}
