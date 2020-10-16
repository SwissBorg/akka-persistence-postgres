package akka.persistence.postgres.migration

import java.io.PrintWriter
import java.sql.{Connection, DriverManager}

import akka.actor.ActorSystem
import akka.persistence.postgres.config.{JournalConfig, SnapshotConfig}
import akka.persistence.postgres.db.SlickExtension
import akka.persistence.postgres.migration.v2.V2__Extract_journal_metadata
import akka.stream.{Materializer, SystemMaterializer}
import com.typesafe.config.Config
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.configuration.FluentConfiguration
import org.flywaydb.core.api.output.MigrateResult
import slick.jdbc.JdbcBackend

import scala.concurrent.Future
import scala.util.Try

class AkkaPersistencePostgresMigration private (flyway: Flyway, onComplete: Try[MigrateResult] => Unit)(implicit
    system: ActorSystem) {

  /**
   * Perform journal & snapshot store migrations.
   *
   * @return Future containing a number of executed migrations.
   */
  def run: Future[Int] = {
    import system.dispatcher
    implicit val met: Materializer = SystemMaterializer(system).materializer

    val migrationFut = Future {
      flyway.baseline()
      flyway.migrate()
    }

    migrationFut.onComplete(onComplete)

    migrationFut.map(_.migrationsExecuted)
  }
}

object AkkaPersistencePostgresMigration {

  def configure(config: Config): Builder = {
    val flywayConfig =
      Flyway.configure.table("persistence_schema_history")
    val journalConfigPath = config.getString("akka.persistence.journal.plugin")
    val schema = {
      val journalSchemaCfgKey = s"$journalConfigPath.tables.journal.schemaName"
      if (config.hasPath(journalSchemaCfgKey)) config.getString(journalSchemaCfgKey)
      else "public"
    }
    Builder(flywayConfig, config).withSchemaHistoryTableSchema(schema)
  }

  case class Builder private (flywayConfig: FluentConfiguration, config: Config) {

    /**
     * Sets the name of the schema history table that will be used by Flyway.
     *
     * @param tableName The name of the schema history table that will be used by Flyway. (default: persistence_schema_history)
     * @return a new Builder instance with customized table name
     */
    def withSchemaHistoryTableName(tableName: String): Builder =
      copy(flywayConfig = flywayConfig.table(tableName))

    /**
     * Sets the schema for the schema history table. This schema name is case-sensitive. If not specified, Flyway uses
     * the default schema for the database connection.
     *
     * @param schema The schema managed by Flyway. May not be {@code null}.
     * @return a new Builder instance with customized schema name
     */
    def withSchemaHistoryTableSchema(schema: String): Builder =
      copy(flywayConfig = flywayConfig.schemas(schema).defaultSchema(schema))

    /**
     * Builds and initializes migration tool instance
     *
     * @param system ActorSystem
     * @return A configured, ready to use Migration Tool instance
     */
    def build(implicit system: ActorSystem): AkkaPersistencePostgresMigration = {
      implicit val met: Materializer = SystemMaterializer(system).materializer

      val journalConfigPath = config.getString("akka.persistence.journal.plugin")
      val snapshotStoreConfigPath = config.getString("akka.persistence.snapshot-store.plugin")

      val slickDb = SlickExtension(system).database(config.getConfig(journalConfigPath))
      val db = slickDb.database

      val snapshotConfig = new SnapshotConfig(config.getConfig(snapshotStoreConfigPath))
      val journalConfig = new JournalConfig(config.getConfig(journalConfigPath))

      val flyway = flywayConfig
        .dataSource(new DatasourceAdapter(db))
        .locations("db/akka-persistence-postgres/migration")
        .javaMigrations(new V2__Extract_journal_metadata(config, journalConfig, snapshotConfig, db))
        .load()

      new AkkaPersistencePostgresMigration(flyway, _ => db.close())
    }
  }

  private[AkkaPersistencePostgresMigration] class DatasourceAdapter(database: JdbcBackend#Database) extends DataSource {
    override def getConnection: Connection = database.createSession().conn
    override def getConnection(username: String, password: String) = throw new UnsupportedOperationException()
    override def unwrap[T](iface: Class[T]): T =
      if (iface.isInstance(this)) this.asInstanceOf[T]
      else throw new IllegalArgumentException(getClass.getName + " is not a wrapper for " + iface)
    override def isWrapperFor(iface: Class[_]): Boolean = iface.isInstance(this)
    override def getLogWriter = throw new UnsupportedOperationException()
    override def setLogWriter(out: PrintWriter): Unit = throw new UnsupportedOperationException()
    override def setLoginTimeout(seconds: Int): Unit = DriverManager.setLoginTimeout(seconds)
    override def getLoginTimeout: Int = DriverManager.getLoginTimeout
    override def getParentLogger = throw new UnsupportedOperationException()
  }
}
