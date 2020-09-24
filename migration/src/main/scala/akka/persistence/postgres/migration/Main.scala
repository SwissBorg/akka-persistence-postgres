/*
 * Copyright (C) 2014 - 2019 Dennis Vriend <https://github.com/dnvriend>
 * Copyright (C) 2019 - 2020 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.persistence.postgres.migration

import com.typesafe.config.{Config, ConfigFactory}
import org.flywaydb.core.Flyway

object Main {

  def run(config: Config): Unit = {
    val migrationConf = config.getConfig("akka-persistence-postgres.migration")
    val url = migrationConf.getString("url")
    val user = migrationConf.getString("user")
    val password = migrationConf.getString("password")

    val flywayConfig = Flyway.configure
      .dataSource(url, user, password)
      .table("persistence_schema_history")
      .locations("classpath:db/migration")

    val flyway = flywayConfig.load
    flyway.baseline()
    flyway.migrate()

  }

  def main(args: Array[String]): Unit = {
    run(ConfigFactory.load())
  }
}
