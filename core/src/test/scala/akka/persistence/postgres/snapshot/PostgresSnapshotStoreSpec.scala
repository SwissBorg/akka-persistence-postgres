/*
 * Copyright (C) 2014 - 2019 Dennis Vriend <https://github.com/dnvriend>
 * Copyright (C) 2019 - 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.postgres.snapshot

import akka.persistence.postgres.config._
import akka.persistence.postgres.db.SlickDatabase
import akka.persistence.postgres.util.Schema._
import akka.persistence.postgres.util.{ ClasspathResources, DropCreate }
import akka.persistence.snapshot.SnapshotStoreSpec
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.duration._

abstract class PostgresSnapshotStoreSpec(schemaType: SchemaType)
    extends SnapshotStoreSpec(ConfigFactory.load(schemaType.configName))
    with BeforeAndAfterAll
    with ScalaFutures
    with ClasspathResources
    with DropCreate {
  implicit val pc: PatienceConfig = PatienceConfig(timeout = 10.seconds)

  implicit lazy val ec = system.dispatcher

  lazy val cfg = system.settings.config.getConfig("postgres-journal")

  lazy val journalConfig = new JournalConfig(cfg)

  lazy val db = SlickDatabase.database(cfg, new SlickConfiguration(cfg.getConfig("slick")), "slick.db")

  override def beforeAll(): Unit = {
    dropCreate(schemaType)
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    db.close()
  }
}

class PlainSnapshotStoreSpec extends PostgresSnapshotStoreSpec(Plain)
