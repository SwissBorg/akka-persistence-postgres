package akka.persistence.postgres.migration

import java.io.PrintWriter
import java.sql.{ Connection, DriverManager }

import akka.actor.ActorSystem
import akka.persistence.postgres.db.SlickExtension
import akka.persistence.postgres.migration.v2.V2__Extract_journal_metadata
import akka.stream.{ Materializer, SystemMaterializer }
import com.typesafe.config.Config
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.configuration.FluentConfiguration
import slick.jdbc.JdbcBackend

import scala.concurrent.Future
import scala.util.Try

class AkkaPersistencePostgresMigration private (flyway: Flyway, onComplete: Try[Int] => Unit)(
    implicit system: ActorSystem) {

  /**
   * Perform journal & snapshot store migrations.
   *
   * @return Future containing a number of successfully applied migrations.
   */
  def run: Future[Int] = {
    import system.dispatcher
    implicit val met: Materializer = SystemMaterializer(system).materializer

    val migrationFut = Future {
      flyway.baseline()
      flyway.migrate()
    }

    migrationFut.onComplete(onComplete)

    migrationFut
  }
}

object AkkaPersistencePostgresMigration {

  def configure(config: Config): Builder =
    Builder(Flyway.configure.table("persistence_migration_log"), config)

  case class Builder private (flywayConfig: FluentConfiguration, config: Config) {

    def withMigrationLogTableName(tableName: String): Builder =
      copy(flywayConfig = flywayConfig.table(tableName))

    def withMigrationLogTableSchema(schema: String): Builder =
      copy(flywayConfig = flywayConfig.schemas(schema).defaultSchema(schema))

    def build(implicit system: ActorSystem): AkkaPersistencePostgresMigration = {
      implicit val met: Materializer = SystemMaterializer(system).materializer

      val slickDb = SlickExtension(system).database(config.getConfig("postgres-journal"))
      val db = slickDb.database

      val flyway = flywayConfig
        .dataSource(new DatasourceAdapter(db))
        .javaMigrations(new V2__Extract_journal_metadata(config, db))
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
