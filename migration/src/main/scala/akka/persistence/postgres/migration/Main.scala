package akka.persistence.postgres.migration

import java.io.File

import akka.actor.ActorSystem
import akka.persistence.postgres.db.SlickExtension
import akka.persistence.postgres.migration.v2.V2__Extract_journal_metadata
import akka.serialization.SerializationExtension
import akka.stream.{ Materializer, SystemMaterializer }
import com.typesafe.config.{ Config, ConfigFactory }
import org.flywaydb.core.Flyway
import org.slf4j.{ Logger, LoggerFactory }

object Main {

  val log: Logger = LoggerFactory.getLogger(this.getClass)

  def run(config: Config): Unit = {
    val migrationConf = config.getConfig("akka-persistence-postgres.migration")
    val url = migrationConf.getString("jdbcUrl")
    val user = migrationConf.getString("user")
    val password = migrationConf.getString("password")

    val system = ActorSystem("migration-tool-AS", config)
    import system.dispatcher
    implicit val met: Materializer = SystemMaterializer(system).materializer

    val serialization = SerializationExtension(system)

    val slickDb = SlickExtension(system).database(migrationConf)
    val db = slickDb.database

    try {
      val flywayConfig = Flyway.configure
        .dataSource(url, user, password)
        .table(migrationConf.getString("history-table-name"))
        .locations("classpath:db/migration")
        .javaMigrations(new V2__Extract_journal_metadata(config, db, serialization))

      val flyway = flywayConfig.load
      flyway.baseline()
      flyway.migrate()
    } finally {
      system.terminate()
      db.close()
    }
  }

  def main(args: Array[String]): Unit = {
    val configPath = args.head
    val configFile = new File(configPath)
    if (configFile.exists()) {
      val config =
        ConfigFactory.parseFile(configFile).withFallback(ConfigFactory.defaultReferenceUnresolved()).resolve()
      run(config)
    } else
      log.error(s"Cannot load migration config - '$configPath' does not exist. Aborting.")
  }
}
