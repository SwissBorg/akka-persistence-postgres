/*
 * Copyright (C) 2014 - 2019 Dennis Vriend <https://github.com/dnvriend>
 * Copyright (C) 2019 - 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.postgres.query.dao

import akka.persistence.postgres.TablesTestSpec
import akka.persistence.postgres.journal.dao.{FlatJournalTable, NestedPartitionsJournalTable, PartitionedJournalTable}

class ReadJournalTablesTest extends TablesTestSpec {
  val readJournalTableConfiguration = readJournalConfig.journalTableConfiguration

  for {
    (journalName, journalTable) <- List(
      ("FlatJournalTable", FlatJournalTable(readJournalTableConfiguration)),
      ("PartitionedJournalTable", PartitionedJournalTable(readJournalTableConfiguration)),
      ("NestedPartitionsJournalTable", NestedPartitionsJournalTable(readJournalTableConfiguration)))
  } {
    s"Read $journalName" should "be configured with a schema name" in {
      journalTable.baseTableRow.schemaName shouldBe readJournalTableConfiguration.schemaName
    }

    it should "be configured with a table name" in {
      journalTable.baseTableRow.tableName shouldBe readJournalTableConfiguration.tableName
    }

    it should "be configured with column names" in {
      val colName = toColumnName(readJournalTableConfiguration.tableName)(_)
      journalTable.baseTableRow.persistenceId.toString shouldBe colName(
        readJournalTableConfiguration.columnNames.persistenceId)
      journalTable.baseTableRow.sequenceNumber.toString shouldBe colName(
        readJournalTableConfiguration.columnNames.sequenceNumber)
      journalTable.baseTableRow.tags.toString shouldBe colName(readJournalTableConfiguration.columnNames.tags)
    }
  }
}
