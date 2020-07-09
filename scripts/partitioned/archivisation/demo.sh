export PGPASSWORD='docker'
export CONNECTION_OPTIONS=' --dbname=docker --username=docker --host=localhost '
#############################
# There is a lot of inserts which you should not run on production.
# demo.sh presents only how scripts can help you with archivisation partitions.
#############################

#############################
# SCHEMA configuration
# demo is not prepared for other values, however for your case it could be really useful to change them
#############################
export SCHEMA='public'
export JOURNAL_TABLE='journal'
export SNAPSHOT_TABLE='snapshot'
export ARCHIVISATION_TABLE='archivisation'
export JOURNAL_PARTITION_PREFIX='j'
#############################

function showStructure() {
  echo ""
  echo "existing tables:"
  VALUES=$(psql -qt ${CONNECTION_OPTIONS} --command="select table_name from information_schema.tables where table_schema = '${SCHEMA}'")
  echo "$VALUES"
  sleep 1
  echo ""
  echo "partitions:"
  VALUES=$(psql -qt ${CONNECTION_OPTIONS} --command="SELECT child.relname AS child, parent.relname as parent FROM pg_inherits JOIN pg_class parent ON pg_inherits.inhparent = parent.oid JOIN pg_class child ON pg_inherits.inhrelid = child.oid JOIN pg_namespace nmsp_parent ON nmsp_parent.oid = parent.relnamespace JOIN pg_namespace nmsp_child ON nmsp_child.oid = child.relnamespace WHERE (parent.relname='${JOURNAL_TABLE}' OR parent.relname like '${JOURNAL_PARTITION_PREFIX}_%')  AND nmsp_parent.nspname ='${SCHEMA}' AND child.relkind='r' ORDER BY parent.relname, child.relname")
  echo "$VALUES"
  sleep 1
  echo ""
  echo "archivisation:"
  VALUES=$(psql -q ${CONNECTION_OPTIONS} --command="SELECT * FROM ${SCHEMA}.${ARCHIVISATION_TABLE};")
  echo "$VALUES"
  sleep 3
}

# create schema for journal, snapshots, eventTag, drop schema for archivisation
showStructure
psql -qt ${CONNECTION_OPTIONS} --file="../../../core/src/test/resources/schema/postgres/partitioned-schema.sql"
showStructure
psql -qt ${CONNECTION_OPTIONS} --file="demo-prepare.sql"
showStructure

# create table
echo "create archivisation table"
psql -q ${CONNECTION_OPTIONS} --file="1-create-archivisation-table.sql"
psql -q ${CONNECTION_OPTIONS} --command="CALL create_archivisation_table('${SCHEMA}','${ARCHIVISATION_TABLE}');"
showStructure

# create functions for selecting partitions
echo "create selecting function"
psql -q ${CONNECTION_OPTIONS} --file="2-select-partitions-to-detach.sql"
psql -q ${CONNECTION_OPTIONS} --command="CALL find_and_mark_journal_nested_partitions_to_detach('${SCHEMA}','${JOURNAL_TABLE}','${SNAPSHOT_TABLE}','${ARCHIVISATION_TABLE}');"
showStructure

# show which tables are selected to remove
echo "checking how function select when snapshot is inserted"
psql -q ${CONNECTION_OPTIONS} --command="INSERT INTO ${SCHEMA}.${SNAPSHOT_TABLE}(persistence_id, sequence_number, created, snapshot) VALUES ('p-1', 50, 0, '0x22');"
psql -q ${CONNECTION_OPTIONS} --command="CALL find_and_mark_journal_nested_partitions_to_detach('${SCHEMA}','${JOURNAL_TABLE}','${SNAPSHOT_TABLE}','${ARCHIVISATION_TABLE}');"
showStructure

# show that table with sequenceNumber 89 is not added to remove, we are removing all tables up to the last snapshot
echo "checking how function select when snapshot is inserted for sequenceNumber 89"
psql -q ${CONNECTION_OPTIONS} --command="INSERT INTO ${SCHEMA}.${SNAPSHOT_TABLE}(persistence_id, sequence_number, created, snapshot) VALUES ('p-1', 89, 0, '0x22');"
psql -q ${CONNECTION_OPTIONS} --command="CALL find_and_mark_journal_nested_partitions_to_detach('${SCHEMA}','${JOURNAL_TABLE}','${SNAPSHOT_TABLE}','${ARCHIVISATION_TABLE}');"
showStructure

# create function for detaching and detaching itself
echo "detach"
psql -q ${CONNECTION_OPTIONS} --file="3-detach.sql"
psql -q ${CONNECTION_OPTIONS} --command="CALL detach_partitions_from_archivisation('${SCHEMA}', '${ARCHIVISATION_TABLE}');"
showStructure

# crate dumps from detached tables
echo "export dumps"
./4-export-detached.sh "${SCHEMA}" "${ARCHIVISATION_TABLE}"
showStructure

# create function for delete dumped partitions and dump itself
echo "delete partitions"
psql -q ${CONNECTION_OPTIONS} --file="5-drop-detached.sql"
psql -q ${CONNECTION_OPTIONS} --command="CALL drop_detached_partitions('${SCHEMA}','${ARCHIVISATION_TABLE}');"
showStructure

# import deleted partition from dump
echo "import partitions"
./8-import-deleted.sh "${SCHEMA}.j_p_1_1" "${SCHEMA}" "${ARCHIVISATION_TABLE}"
./8-import-deleted.sh "${SCHEMA}.j_p_1_2" "${SCHEMA}" "${ARCHIVISATION_TABLE}"
showStructure

# reattach partitions
echo "reattach partitions"
psql -q ${CONNECTION_OPTIONS} --file="9-attach.sql"
psql -q ${CONNECTION_OPTIONS} --command="CALL reattach_partitions_from_archivisation('${SCHEMA}','${ARCHIVISATION_TABLE}');"
showStructure

rm *.dump
