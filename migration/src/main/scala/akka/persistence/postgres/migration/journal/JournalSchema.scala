package akka.persistence.postgres.migration.journal

import akka.actor.{ ActorSystem, ExtendedActorSystem }
import akka.persistence.postgres.config.{ JournalConfig, JournalPartitionsConfiguration, JournalTableConfiguration }
import akka.persistence.postgres.db.ExtendedPostgresProfile.api._
import akka.persistence.postgres.journal.dao.{ JournalDao, NestedPartitionsJournalDao, PartitionedJournalDao }
import org.slf4j.{ Logger, LoggerFactory }

import scala.concurrent.ExecutionContext

private[journal] trait JournalSchema {
  implicit def ec: ExecutionContext
  protected def journalCfg: JournalConfig
  protected def tempTableName: String
  protected lazy val journalTableCfg: JournalTableConfiguration = journalCfg.journalTableConfiguration
  protected lazy val partitionsCfg: JournalPartitionsConfiguration = journalCfg.partitionsConfig
  protected lazy val schema: String = journalTableCfg.schemaName.getOrElse("public")
  protected lazy val fullTmpTableName = s"$schema.$tempTableName"
  protected lazy val fullSourceTableName =
    s"${journalTableCfg.schemaName.getOrElse("public")}.${journalTableCfg.tableName}"

  import journalTableCfg.columnNames._

  def getTable: TableQuery[TempJournalTable]
  def createTable: DBIOAction[Unit, NoStream, Effect.Write]

  def createJournalPersistenceIdsTable: DBIOAction[Unit, NoStream, Effect.Write] = {
    val journalPersistenceIdsTableCfg = journalCfg.journalPersistenceIdsTableConfiguration
    val fullTableName =
      s"${journalPersistenceIdsTableCfg.schemaName.getOrElse("public")}.${journalPersistenceIdsTableCfg.tableName}"

    import journalPersistenceIdsTableCfg.columnNames._
    for {
      _ <- sqlu"""CREATE TABLE #$fullTableName (
            #$id BIGSERIAL,
            #$persistenceId TEXT NOT NULL,
            #$maxSequenceNumber BIGINT       NOT NULL,
            #$maxOrdering       BIGINT       NOT NULL,
            #$minOrdering       BIGINT       NOT NULL,
            PRIMARY KEY (#$persistenceId)
          ) PARTITION BY HASH(#$persistenceId)"""
      _ <-
        sqlu"""CREATE TABLE #${fullTableName}_0 PARTITION OF #$fullTableName FOR VALUES WITH (MODULUS 2, REMAINDER 0)"""
      _ <-
        sqlu"""CREATE TABLE #${fullTableName}_1 PARTITION OF #$fullTableName FOR VALUES WITH (MODULUS 2, REMAINDER 1)"""
    } yield ()
  }

  def createTagsTable: DBIOAction[Unit, NoStream, Effect.Write] = {
    val tagsTableConfig = journalCfg.tagsTableConfiguration
    import tagsTableConfig.columnNames._
    val fullTableName = s"${tagsTableConfig.schemaName.getOrElse("public")}.${tagsTableConfig.tableName}"
    for {
      _ <- sqlu"""CREATE TABLE #$fullTableName (
            #$id   BIGSERIAL,
            #$name TEXT NOT NULL,
            PRIMARY KEY (#$id)
          )"""
      _ <- sqlu"CREATE UNIQUE INDEX #${tagsTableConfig.tableName}_name_idx on #$fullTableName (#$name)"
    } yield ()
  }

  def createIndexes: DBIOAction[Unit, NoStream, Effect.Write] =
    for {
      _ <- sqlu"CREATE EXTENSION IF NOT EXISTS intarray WITH SCHEMA public"
      _ <-
        sqlu"CREATE INDEX #${tempTableName}_#${tags}_idx ON #$fullTmpTableName USING GIN (#$tags public.gin__int_ops)"
      _ <- sqlu"CREATE INDEX #${tempTableName}_#${ordering}_idx ON #$fullTmpTableName USING BRIN (#$ordering)"
    } yield ()

  def createSequence: DBIOAction[Unit, NoStream, Effect.Write] =
    for {
      lastOrderingValue <- sql"SELECT last_value FROM #${fullSourceTableName}_#${ordering}_seq".as[Int].map(_.head)
      _ <-
        sqlu"CREATE SEQUENCE #${fullTmpTableName}_#${ordering}_seq START WITH #$lastOrderingValue OWNED BY #$fullTmpTableName.#$ordering"
      _ <-
        sqlu"ALTER TABLE #$fullTmpTableName ALTER COLUMN #$ordering SET DEFAULT nextval('#${fullTmpTableName}_#${ordering}_seq')"
    } yield ()

  def swapJournals: DBIOAction[Unit, NoStream, Effect.Write] =
    for {
      _ <- sqlu"""ALTER TABLE #$fullSourceTableName RENAME TO old_#${journalTableCfg.tableName}"""
      _ <-
        sqlu"""ALTER SEQUENCE #${fullSourceTableName}_#${ordering}_seq RENAME TO old_#${journalTableCfg.tableName}_#${ordering}_seq"""
      _ <- sqlu"""ALTER INDEX #${fullSourceTableName}_pkey RENAME TO old_#${journalTableCfg.tableName}_pkey"""
      _ <-
        sqlu"""ALTER INDEX #${fullSourceTableName}_#${ordering}_idx RENAME TO old_#${journalTableCfg.tableName}_#${ordering}_idx"""
      _ <- sqlu"""ALTER TABLE #$fullTmpTableName RENAME TO #${journalTableCfg.tableName}"""
      _ <-
        sqlu"""ALTER SEQUENCE #${fullTmpTableName}_#${ordering}_seq RENAME TO #${journalTableCfg.tableName}_#${ordering}_seq"""
      _ <-
        sqlu"""ALTER INDEX #${fullTmpTableName}_#${ordering}_idx RENAME TO #${journalTableCfg.tableName}_#${ordering}_idx"""
      _ <- sqlu"""ALTER INDEX #${fullTmpTableName}_#${tags}_idx RENAME TO #${journalTableCfg.tableName}_#${tags}_idx"""
      _ <- sqlu"""ALTER INDEX #${fullTmpTableName}_pkey RENAME TO #${journalTableCfg.tableName}_pkey"""
    } yield ()

  def createTriggers: DBIOAction[Unit, NoStream, Effect.Write] = {
    val journalTableCfg = journalCfg.journalTableConfiguration
    val journalPersistenceIdsTableCfg = journalCfg.journalPersistenceIdsTableConfiguration
    val schema = journalPersistenceIdsTableCfg.schemaName.getOrElse("public")
    val fullTableName = s"$schema.${journalPersistenceIdsTableCfg.tableName}"
    val journalFullTableName = s"$schema.${journalTableCfg.tableName}"

    import journalPersistenceIdsTableCfg.columnNames._
    import journalTableCfg.columnNames.{ persistenceId => jPersistenceId, _ }

    for {
      _ <- sqlu"""
            CREATE OR REPLACE FUNCTION #$schema.update_journal_persistence_ids() RETURNS TRIGGER AS $$$$
            DECLARE
            BEGIN
              INSERT INTO #$fullTableName (#$persistenceId, #$maxSequenceNumber, #$maxOrdering, #$minOrdering)
              VALUES (NEW.#$jPersistenceId, NEW.#$sequenceNumber, NEW.#$ordering, NEW.#$ordering)
              ON CONFLICT (#$persistenceId) DO UPDATE
              SET
                #$maxSequenceNumber = NEW.#$sequenceNumber,
                #$maxOrdering = NEW.#$ordering,
                #$minOrdering = LEAST(#$fullTableName.#$minOrdering, NEW.#$ordering);
            
              RETURN NEW;
            END;
            $$$$ LANGUAGE plpgsql;
           """

      _ <- sqlu"""
            CREATE TRIGGER trig_update_journal_persistence_ids
            AFTER INSERT ON #$journalFullTableName
            FOR EACH ROW
            EXECUTE PROCEDURE #$schema.update_journal_persistence_ids();
           """

      _ <- sqlu"""
            CREATE TRIGGER trig_update_journal_persistence_ids
            AFTER INSERT ON #$fullTmpTableName
            FOR EACH ROW
            EXECUTE PROCEDURE #$schema.update_journal_persistence_ids();
           """

      _ <- sqlu"""
            CREATE OR REPLACE FUNCTION #$schema.check_persistence_id_max_sequence_number() RETURNS TRIGGER AS $$$$
            DECLARE
            BEGIN
              IF NEW.#$maxSequenceNumber <= OLD.#$maxSequenceNumber THEN
                RAISE EXCEPTION 'New max_sequence_number not higher than previous value';
              END IF;
              
              RETURN NEW;
            END;
            $$$$ LANGUAGE plpgsql;
           """

      _ <- sqlu"""
            CREATE TRIGGER trig_check_persistence_id_max_sequence_number
            BEFORE UPDATE ON #$fullTableName
            FOR EACH ROW
            EXECUTE PROCEDURE #$schema.check_persistence_id_max_sequence_number();
           """
    } yield ()
  }
}

private[journal] object JournalSchema {

  lazy val log: Logger = LoggerFactory.getLogger(this.getClass)

  def apply(journalConfig: JournalConfig, tempTableName: String)(implicit system: ActorSystem): JournalSchema = {
    import system.dispatcher
    val fqcn = journalConfig.pluginConfig.dao
    val daoClass =
      system.asInstanceOf[ExtendedActorSystem].dynamicAccess.getClassFor[JournalDao](fqcn).fold(throw _, identity)
    // DAO matters - different implementations might be using different schemas with different compound primary keys.
    if (classOf[PartitionedJournalDao].isAssignableFrom(daoClass)) {
      log.info(s"Using Partitioned journal schema (dao = '$fqcn')")
      new PartitionedJournal(journalConfig, tempTableName)
    } else if (classOf[NestedPartitionsJournalDao].isAssignableFrom(daoClass)) {
      log.info(s"Using Nested Partitions journal schema (dao = '$fqcn')")
      new NestedPartitionsJournal(journalConfig, tempTableName)
    } else {
      log.info(s"Using default (flat) journal schema (dao = '$fqcn')")
      new FlatJournal(journalConfig, tempTableName)
    }
  }

  class FlatJournal private[JournalSchema] (
      protected val journalCfg: JournalConfig,
      protected val tempTableName: String)(implicit val ec: ExecutionContext)
      extends JournalSchema {

    import journalTableCfg.columnNames._

    override def getTable: TableQuery[TempJournalTable] =
      TempFlatJournalTable(journalCfg.journalTableConfiguration, tempTableName)

    override def createTable: DBIOAction[Unit, NoStream, Effect.Write] = {
      for {
        _ <- sqlu"""CREATE TABLE #$fullTmpTableName (
          #$ordering       BIGINT,
          #$sequenceNumber BIGINT                NOT NULL,
          #$deleted        BOOLEAN DEFAULT FALSE NOT NULL,
          #$persistenceId  TEXT                  NOT NULL,
          #$message        BYTEA                 NOT NULL,
          #$metadata       jsonb                 NOT NULL,
          #$tags           int[],
          PRIMARY KEY (#$persistenceId, #$sequenceNumber)
        )"""
      } yield ()
    }
  }

  class PartitionedJournal private[JournalSchema] (
      protected val journalCfg: JournalConfig,
      protected val tempTableName: String)(implicit val ec: ExecutionContext)
      extends JournalSchema {

    import journalTableCfg.columnNames._

    override def getTable: TableQuery[TempJournalTable] =
      TempPartitionedJournalTable(journalCfg.journalTableConfiguration, tempTableName)

    override def createTable: DBIOAction[Unit, NoStream, Effect.Write] = {
      val partitionSize = partitionsCfg.size
      val partitionPrefix = partitionsCfg.prefix
      for {
        _ <- sqlu"""CREATE TABLE #$fullTmpTableName (
          #$ordering       BIGINT,
          #$sequenceNumber BIGINT                NOT NULL,
          #$deleted        BOOLEAN DEFAULT FALSE NOT NULL,
          #$persistenceId  TEXT                  NOT NULL,
          #$message        BYTEA                 NOT NULL,
          #$metadata       jsonb                 NOT NULL,
          #$tags           int[],
          PRIMARY KEY (#$ordering)
        ) PARTITION BY RANGE (#$ordering)"""
        maxOrdering <- sql"SELECT max(#$ordering) from #$fullSourceTableName".as[Int].map(_.headOption.getOrElse(0))
        _ <- DBIO.sequence {
          (0 to (maxOrdering / partitionSize)).map { i =>
            sqlu"CREATE TABLE IF NOT EXISTS #$schema.#${partitionPrefix}_#$i PARTITION OF #$fullTmpTableName FOR VALUES FROM (#${i * partitionSize}) TO (#${(i + 1) * partitionSize})"
          }
        }
      } yield ()
    }

    override def createIndexes: DBIOAction[Unit, NoStream, Effect.Write] =
      for {
        _ <- sqlu"CREATE EXTENSION IF NOT EXISTS intarray WITH SCHEMA public"
        _ <-
          sqlu"CREATE INDEX #${tempTableName}_#${tags}_idx ON #$fullTmpTableName USING GIN (#$tags public.gin__int_ops)"
        _ <-
          sqlu"CREATE INDEX #${tempTableName}_#${persistenceId}_#${sequenceNumber}_idx ON #$fullTmpTableName USING BTREE (#$persistenceId, #$sequenceNumber)"
      } yield ()

    override def createSequence: DBIOAction[Unit, NoStream, Effect.Write] =
      for {
        lastOrderingValue <- sql"SELECT last_value FROM #${fullSourceTableName}_#${ordering}_seq".as[Int].map(_.head)
        _ <-
          sqlu"CREATE SEQUENCE #${fullTmpTableName}_#${ordering}_seq START WITH #$lastOrderingValue OWNED BY #$fullTmpTableName.#$ordering"
      } yield ()

    override def swapJournals: DBIOAction[Unit, NoStream, Effect.Write] =
      for {
        _ <- sqlu"""ALTER TABLE #$fullSourceTableName RENAME TO old_#${journalTableCfg.tableName}"""
        _ <-
          sqlu"""ALTER SEQUENCE #${fullSourceTableName}_#${ordering}_seq RENAME TO old_#${journalTableCfg.tableName}_#${ordering}_seq"""
        _ <- sqlu"""ALTER INDEX #${fullSourceTableName}_pkey RENAME TO old_#${journalTableCfg.tableName}_pkey"""
        _ <-
          sqlu"""ALTER INDEX #${fullSourceTableName}_#${ordering}_idx RENAME TO old_#${journalTableCfg.tableName}_#${ordering}_idx"""
        _ <- sqlu"""ALTER TABLE #$fullTmpTableName RENAME TO #${journalTableCfg.tableName}"""
        _ <-
          sqlu"""ALTER SEQUENCE #${fullTmpTableName}_#${ordering}_seq RENAME TO #${journalTableCfg.tableName}_#${ordering}_seq"""
        _ <-
          sqlu"""ALTER INDEX #${fullTmpTableName}_#${persistenceId}_#${sequenceNumber}_idx RENAME TO #${journalTableCfg.tableName}_#${persistenceId}_#${sequenceNumber}_idx"""
        _ <-
          sqlu"""ALTER INDEX #${fullTmpTableName}_#${tags}_idx RENAME TO #${journalTableCfg.tableName}_#${tags}_idx"""
        _ <- sqlu"""ALTER INDEX #${fullTmpTableName}_pkey RENAME TO #${journalTableCfg.tableName}_pkey"""
      } yield ()

  }

  class NestedPartitionsJournal private[JournalSchema] (
      protected val journalCfg: JournalConfig,
      protected val tempTableName: String)(implicit val ec: ExecutionContext)
      extends JournalSchema {

    import journalTableCfg.columnNames._

    override def getTable: TableQuery[TempJournalTable] =
      TempNestedPartitionsJournalTable(journalCfg.journalTableConfiguration, tempTableName)

    override def createTable: DBIOAction[Unit, NoStream, Effect.Write] = {
      val partitionSize = partitionsCfg.size
      val partitionPrefix = partitionsCfg.prefix
      for {
        _ <- sqlu"""CREATE TABLE #$fullTmpTableName (
          #$ordering       BIGINT,
          #$sequenceNumber BIGINT                NOT NULL,
          #$deleted        BOOLEAN DEFAULT FALSE NOT NULL,
          #$persistenceId  TEXT                  NOT NULL,
          #$message        BYTEA                 NOT NULL,
          #$metadata       jsonb                 NOT NULL,
          #$tags           int[],
          PRIMARY KEY (#$persistenceId, #$sequenceNumber)
        ) PARTITION BY LIST (#$persistenceId)"""
        maxSeqByPid <-
          sql"SELECT #$persistenceId, max(#$sequenceNumber) FROM #$fullSourceTableName GROUP BY #$persistenceId"
            .as[(String, Int)]
        _ <- DBIO.sequence {
          maxSeqByPid.map { case (pid, maxSeqNum) =>
            val sanitizedPartitionId = pid.replaceAll("\\W", "_")
            val fullPartitionName = s"$schema.${partitionPrefix}_$sanitizedPartitionId"
            for {
              _ <-
                sqlu"CREATE TABLE IF NOT EXISTS #$fullPartitionName PARTITION OF #$fullTmpTableName FOR VALUES IN ('#$pid') PARTITION BY RANGE (#$sequenceNumber)"
              _ <- DBIO.sequence {
                (0 to maxSeqNum / partitionSize).map { i =>
                  sqlu"CREATE TABLE IF NOT EXISTS #${fullPartitionName}_#$i PARTITION OF #$fullPartitionName FOR VALUES FROM (#${i * partitionSize}) TO (#${(i + 1) * partitionSize})"
                }
              }
            } yield ()
          }
        }
      } yield ()
    }
  }

}
