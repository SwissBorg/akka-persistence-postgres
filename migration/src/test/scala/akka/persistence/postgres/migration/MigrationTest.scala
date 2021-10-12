package akka.persistence.postgres.migration

import akka.NotUsed
import akka.actor.ActorSystem
import akka.persistence.postgres.config.{ JournalConfig, SnapshotConfig }
import akka.persistence.postgres.db.ExtendedPostgresProfile.api._
import akka.persistence.postgres.db.SlickExtension
import akka.persistence.postgres.migration.journal.Jdbc4JournalMigration
import akka.persistence.postgres.migration.snapshot.Jdbc4SnapshotStoreMigration
import akka.persistence.postgres.query.scaladsl.PostgresReadJournal
import akka.persistence.query.PersistenceQuery
import akka.stream.scaladsl.Source
import com.typesafe.config.{ Config, ConfigFactory }
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.migration.{ BaseJavaMigration, Context }
import org.scalatest.concurrent.{ IntegrationPatience, ScalaFutures }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach, Suite }
import slick.jdbc.{ ResultSetConcurrency, ResultSetType }

import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, ExecutionContext }

class FlatMigrationTest extends BaseMigrationTest("flat-migration")
class PartitionedMigrationTest extends BaseMigrationTest("partitioned-migration")
class NestedPartitionsMigrationTest extends BaseMigrationTest("nested-partitions-migration")

abstract class BaseMigrationTest(configBasename: String)
    extends AnyFlatSpec
    with Matchers
    with ScalaFutures
    with PrepareDatabase
    with BeforeAndAfterAll
    with IntegrationPatience {

  lazy val config: Config = ConfigFactory.load(configBasename)
  implicit val system: ActorSystem = ActorSystem("MigrationTest-AS", config)

  override val tempJournalTableName = "tmp_journal"
  override val tempSnapshotTableName = "tmp_snapshot"

  "Migration" should "migrate journal and snapshot store" in {
    val flyway = Flyway
      .configure()
      .dataSource("jdbc:postgresql://localhost:5432/docker", "docker", "docker")
      .schemas("migration")
      .javaMigrations(
        new V2_0__MigrateJournal(config, tempJournalTableName),
        new V2_1__MigrateSnapshots(config, tempSnapshotTableName))
      .load()
    flyway.baseline()
    val migrationRes = flyway.migrate()

    migrationRes.migrationsExecuted shouldBe 2
    migrationRes.schemaName should equal("migration")
    migrationRes.initialSchemaVersion should equal("1")
    migrationRes.targetSchemaVersion should equal("2.1")

    val readJournal =
      PersistenceQuery(system).readJournalFor[PostgresReadJournal](PostgresReadJournal.Identifier)
    val persistenceIds = readJournal.currentPersistenceIds().runFold(List.empty[String])(_ :+ _).futureValue
    persistenceIds should contain theSameElementsAs List("pp-1", "pp-2", "pp-3")

    // Gaps should be preserved
    val orderings = getOrderings.runFold(List.empty[Long])(_ :+ _).futureValue
    val expectedOrderings = (1 to 100).toList ++ (104 to 203).toList ++ (205 to 304).toList
    (orderings should contain).theSameElementsInOrderAs(expectedOrderings)

    // Tags should be migrated
    readJournal
      .currentEventsByTag("longtag", 0L)
      .runFold(0) { (sum, _) =>
        sum + 1
      }
      .futureValue
  }

  def getOrderings: Source[Long, NotUsed] = {
    val journalConfig = new JournalConfig(config.getConfig("postgres-journal"))
    import journalConfig.journalTableConfiguration.columnNames._
    Source.fromPublisher {
      db.stream {
        sql"""SELECT #$ordering FROM migration.#${journalConfig.journalTableConfiguration.tableName} ORDER BY #$ordering"""
          .as[Long]
          .withStatementParameters(
            rsType = ResultSetType.ForwardOnly,
            rsConcurrency = ResultSetConcurrency.ReadOnly,
            fetchSize = 200)
          .transactionally
      }
    }
  }

  override protected def afterAll(): Unit = {
    system.terminate()
    super.afterAll()
  }
}

class V2_0__MigrateJournal(config: Config, tmpTableName: String)(implicit system: ActorSystem)
    extends BaseJavaMigration {
  override def migrate(context: Context): Unit = {
    val migration = new Jdbc4JournalMigration(config, tmpTableName)
    Await.ready(migration.run(), Duration.Inf)
  }
}

class V2_1__MigrateSnapshots(config: Config, tmpTableName: String)(implicit system: ActorSystem)
    extends BaseJavaMigration {
  override def migrate(context: Context): Unit = {
    val migration = new Jdbc4SnapshotStoreMigration(config, tmpTableName)
    Await.ready(migration.run(), Duration.Inf)
  }
}

trait PrepareDatabase extends BeforeAndAfterEach with BeforeAndAfterAll with ScalaFutures with IntegrationPatience {
  this: Suite =>

  implicit def system: ActorSystem
  implicit def ec: ExecutionContext = system.dispatcher

  def config: Config
  def tempJournalTableName: String
  def tempSnapshotTableName: String

  lazy val db = {
    val slickDb = SlickExtension(system).database(config.getConfig("postgres-snapshot-store"))
    slickDb.database
  }

  override protected def beforeEach(): Unit = {
    super.beforeEach()
    db.run(prepareTables).futureValue
  }

  override protected def afterAll(): Unit = {
    db.close()
    super.afterAll()
  }

  lazy val prepareTables = for {
    _ <- sqlu"""CREATE SCHEMA IF NOT EXISTS migration"""
    _ <- sqlu"""DROP TABLE IF EXISTS migration.flyway_schema_history"""
    _ <- prepareJournal
    _ <- prepareSnapshot
  } yield ()

  private lazy val prepareSnapshot = {
    val snapshotConfig = new SnapshotConfig(config.getConfig("postgres-snapshot-store"))
    val snapshotTableConfig = snapshotConfig.snapshotTableConfiguration
    val snapshotTableName = snapshotTableConfig.tableName
    import snapshotTableConfig.columnNames._
    for {
      _ <- sqlu"""DROP TABLE IF EXISTS migration.#$snapshotTableName"""
      _ <- sqlu"""DROP TABLE IF EXISTS migration.old_#$snapshotTableName"""
      _ <- sqlu"""DROP TABLE IF EXISTS migration.#$tempSnapshotTableName"""
      _ <- sqlu"""CREATE TABLE IF NOT EXISTS migration.#$snapshotTableName
       (
           #$persistenceId  TEXT   NOT NULL,
           #$sequenceNumber BIGINT NOT NULL,
           #$created        BIGINT NOT NULL,
           #$snapshot       BYTEA  NOT NULL,
           PRIMARY KEY (#$persistenceId, #$sequenceNumber)
       )"""
      _ <- sqlu"""insert into migration.#$snapshotTableName (
            #$persistenceId, #$sequenceNumber, #$created, #$snapshot
        ) VALUES ('pp-1', 8, 1604142151007, '\x0400000014000000622d31')"""
      _ <- sqlu"""insert into migration.#$snapshotTableName (
            #$persistenceId, #$sequenceNumber, #$created, #$snapshot
        ) VALUES ('pp-2', 8, 1604142151007, '\x0400000014000000622d31')"""
    } yield ()
  }

  private lazy val prepareJournal = {
    val journalConfig = new JournalConfig(config.getConfig("postgres-journal"))
    val journalTableConfig = journalConfig.journalTableConfiguration
    val journalTableName = journalTableConfig.tableName

    val journalPersistenceIdsTableConfig = journalConfig.journalPersistenceIdsTableConfiguration
    val journalPersistenceIdsTableName = journalPersistenceIdsTableConfig.tableName

    val tagsTableConfig = journalConfig.tagsTableConfiguration
    import journalTableConfig.columnNames.{ tags => tagsCol, _ }
    for {
      _ <- sqlu"""DROP TABLE IF EXISTS migration.#${tagsTableConfig.tableName}"""
      _ <- sqlu"""DROP TABLE IF EXISTS migration.old_#${tagsTableConfig.tableName}"""
      _ <- sqlu"""DROP TABLE IF EXISTS migration.old_#$journalTableName"""
      _ <- sqlu"""DROP TABLE IF EXISTS migration.#$tempJournalTableName"""
      _ <- sqlu"""DROP TABLE IF EXISTS migration.#$journalTableName"""
      _ <- sqlu"""DROP TRIGGER IF EXISTS trig_update_journal_persistence_ids ON migration.#$journalTableName"""
      _ <- sqlu"""DROP FUNCTION IF EXISTS migration.update_journal_persistence_ids()"""
      _ <- sqlu"""DROP TABLE IF EXISTS migration.#$journalPersistenceIdsTableName"""
      _ <- sqlu"""CREATE TABLE IF NOT EXISTS migration.#$journalTableName
        (
            #$ordering       BIGSERIAL,
            #$persistenceId  VARCHAR(255)               NOT NULL,
            #$sequenceNumber BIGINT                     NOT NULL,
            #$deleted        BOOLEAN      DEFAULT FALSE NOT NULL,
            #$tagsCol        VARCHAR(255) DEFAULT NULL,
            #$message        BYTEA                      NOT NULL,
            PRIMARY KEY (#$persistenceId, #$sequenceNumber)
        )"""
      _ <- sqlu"""DROP INDEX IF EXISTS old_#${journalTableName}_#${ordering}_idx"""
      _ <-
        sqlu"""CREATE UNIQUE INDEX #${journalTableName}_#${ordering}_idx ON migration.#$journalTableName (#$ordering)"""
      // create & fill temp tags dictionary
      _ <- sqlu"""DROP TABLE IF EXISTS migration.tag_definition"""
      _ <- sqlu"""CREATE TABLE IF NOT EXISTS migration.tag_definition
       (
           orders INT,
           tag    VARCHAR(255) DEFAULT NULL,
           PRIMARY KEY (orders)
       )"""
      _ <- sqlu"""INSERT INTO migration.tag_definition(orders, tag) VALUES
            (0, ''),
            (1, 'firstEvent'),
            (2, 'longtag'),
            (3, 'multiT1,multiT2'),
            (4, 'firstUnique'),
            (5, 'tag'),
            (6, 'expected'),
            (7, 'multi,companion'),
            (8, 'companion,multiT1,T3,T4'),
            (9, 'xxx'),
            (10, 'ended'),
            (11, 'expected')"""
      _ <- sqlu"""INSERT INTO migration.#$journalTableName(#$persistenceId, #$sequenceNumber, #$deleted, #$tagsCol, #$message)
                     select 'pp-1', i, false, tag, '\x0a0708141203612d3110011a03702d316a2461313164393136332d633365322d343136322d386630362d39623233396663386635383070a8ccefd2dd5c'
                     from generate_series(1, 100) s(i)
                              JOIN migration.tag_definition on orders = mod(i, 12)"""
      // make a gap between ordering values
      _ <- sql"""SELECT nextval('migration.#${journalTableName}_#${ordering}_seq'::regclass)""".as[Int]
      _ <- sql"""SELECT nextval('migration.#${journalTableName}_#${ordering}_seq'::regclass)""".as[Int]
      _ <- sql"""SELECT nextval('migration.#${journalTableName}_#${ordering}_seq'::regclass)""".as[Int]
      _ <- sqlu"""INSERT INTO migration.#$journalTableName(#$persistenceId, #$sequenceNumber, #$deleted, #$tagsCol, #$message)
        select 'pp-2', i, false, tag, '\x0a0708141203612d3110011a03702d316a2461313164393136332d633365322d343136322d386630362d39623233396663386635383070a8ccefd2dd5c'
        from generate_series(1, 100) s(i)
                 JOIN migration.tag_definition on orders =  mod(i, 12)"""
      // make a gap between ordering values
      _ <- sql"""SELECT nextval('migration.#${journalTableName}_#${ordering}_seq'::regclass)""".as[Int]
      _ <- sqlu"""INSERT INTO migration.#$journalTableName(#$persistenceId, #$sequenceNumber, #$deleted, #$tagsCol, #$message)
        select 'pp-3', i, false, tag, '\x0a0708141203612d3110011a03702d316a2461313164393136332d633365322d343136322d386630362d39623233396663386635383070a8ccefd2dd5c'
        from generate_series(1, 100) s(i)
                 JOIN migration.tag_definition on orders =  mod(i, 12)"""
      // drop temp tags dictionary used by data generators
      _ <- sqlu"""DROP TABLE migration.tag_definition"""
    } yield ()
  }
}
