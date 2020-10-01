package akka.persistence.postgres.migration

import java.io.File

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import org.slf4j.{ Logger, LoggerFactory }

object Main {

  val log: Logger = LoggerFactory.getLogger(this.getClass)

  def main(args: Array[String]): Unit = {
    val configPath = args.head
    val configFile = new File(configPath)
    if (configFile.exists()) {
      val config =
        ConfigFactory.parseFile(configFile).withFallback(ConfigFactory.defaultReferenceUnresolved()).resolve()

      implicit val system: ActorSystem = ActorSystem("migration-tool-AS", config)
      import system.dispatcher

      val migration = AkkaPersistencePostgresMigration.configure(config).build
      migration.run.onComplete(_ => system.terminate())
    } else
      log.error(s"Cannot load migration config - '$configPath' does not exist. Aborting.")
  }
}
