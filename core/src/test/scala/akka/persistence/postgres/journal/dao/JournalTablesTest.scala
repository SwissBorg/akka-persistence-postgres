/*
 * Copyright (C) 2014 - 2019 Dennis Vriend <https://github.com/dnvriend>
 * Copyright (C) 2019 - 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.postgres.journal.dao

import akka.persistence.postgres.TablesTestSpec

class JournalTablesTest extends TablesTestSpec {
  val journalTableConfiguration = journalConfig.journalTableConfiguration

  for {
    (journalName, journalTable) <- List(
      ("FlatJournalTable", FlatJournalTable(journalTableConfiguration)),
      ("PartitionedJournalTable", PartitionedJournalTable(journalTableConfiguration)),
      ("NestedPartitionsJournalTable", NestedPartitionsJournalTable(journalTableConfiguration)))
  } {
    journalName should "be configured with a schema name" in {
      journalTable.baseTableRow.schemaName shouldBe journalTableConfiguration.schemaName

    }

    it should "be configured with a table name" in {
      journalTable.baseTableRow.tableName shouldBe journalTableConfiguration.tableName
    }

    it should "be configured with column names" in {
      val colName = toColumnName(journalTableConfiguration.tableName)(_)
      journalTable.baseTableRow.persistenceId.toString shouldBe colName(
        journalTableConfiguration.columnNames.persistenceId)
      journalTable.baseTableRow.deleted.toString shouldBe colName(journalTableConfiguration.columnNames.deleted)
      journalTable.baseTableRow.sequenceNumber.toString shouldBe colName(
        journalTableConfiguration.columnNames.sequenceNumber)
      journalTable.baseTableRow.tags.toString shouldBe colName(journalTableConfiguration.columnNames.tags)
    }
  }

  val journalMetadataTableConfiguration = journalConfig.journalMetadataTableConfiguration
  val journalMetadataTable = JournalMetadataTable(journalMetadataTableConfiguration)

  "JournalMetadataTable" should "be configured with a schema name" in {
    journalMetadataTable.baseTableRow.schemaName shouldBe journalMetadataTableConfiguration.schemaName
  }

  it should "be configured with a table name" in {
    journalMetadataTable.baseTableRow.tableName shouldBe journalMetadataTableConfiguration.tableName
  }

  it should "be configured with column names" in {
    val colName = toColumnName(journalMetadataTableConfiguration.tableName)(_)
    journalMetadataTable.baseTableRow.persistenceId.toString shouldBe colName(
      journalMetadataTableConfiguration.columnNames.persistenceId)
    journalMetadataTable.baseTableRow.maxSequenceNumber.toString shouldBe colName(
      journalMetadataTableConfiguration.columnNames.maxSequenceNumber)
    journalMetadataTable.baseTableRow.maxOrdering.toString shouldBe colName(
      journalMetadataTableConfiguration.columnNames.maxOrdering)
    journalMetadataTable.baseTableRow.minOrdering.toString shouldBe colName(
      journalMetadataTableConfiguration.columnNames.minOrdering)
  }
}
