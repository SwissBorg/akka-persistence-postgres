export PGPASSWORD='docker'
export CONNECTION_OPTIONS=' --dbname=docker --username=docker --host=localhost '

JOURNAL_PARTITIONS_QUERY="SELECT nmsp_child.nspname  AS child_schema, child.relname AS child FROM pg_inherits JOIN pg_class parent ON pg_inherits.inhparent = parent.oid JOIN pg_class child ON pg_inherits.inhrelid = child.oid JOIN pg_namespace nmsp_parent ON nmsp_parent.oid = parent.relnamespace JOIN pg_namespace nmsp_child ON nmsp_child.oid = child.relnamespace WHERE parent.relname='journal' AND nmsp_parent.nspname ='public'"
J_P_1__PARTITIONS_QUERY="SELECT nmsp_child.nspname  AS child_schema, child.relname AS child FROM pg_inherits JOIN pg_class parent ON pg_inherits.inhparent = parent.oid JOIN pg_class child ON pg_inherits.inhrelid = child.oid JOIN pg_namespace nmsp_parent ON nmsp_parent.oid = parent.relnamespace JOIN pg_namespace nmsp_child ON nmsp_child.oid = child.relnamespace WHERE parent.relname='j_p_1' AND nmsp_parent.nspname ='public'"

function showStructure() {
  echo ""
  echo "existing tables:"
  psql -q ${CONNECTION_OPTIONS} --command="select table_name from information_schema.tables where table_schema = 'public'"
  sleep 1
  echo ""
  echo "journal partitions:"
  psql -q ${CONNECTION_OPTIONS} --command="$JOURNAL_PARTITIONS_QUERY"
  sleep 1
  echo ""
  echo "j_p_1 partitions:"
  psql -q ${CONNECTION_OPTIONS} --command="$J_P_1__PARTITIONS_QUERY"
  sleep 1
  echo ""
  echo "archivisation:"
  psql -q ${CONNECTION_OPTIONS} --command="SELECT * FROM public.archivisation;"
  sleep 3
}

# create schema for journal, snapshots, eventTag, drop schema for archivisation
psql -qt ${CONNECTION_OPTIONS} --file="../../core/src/test/resources/schema/postgres/partitioned-schema.sql"
psql -qt ${CONNECTION_OPTIONS} --file="demo-prepare.sql"

# create table
echo "create archivisation table"
psql -q ${CONNECTION_OPTIONS} --file="1-create-archivisation-table.sql"
showStructure

# create functions for selecting partitions
echo "create selecting function"
psql -q ${CONNECTION_OPTIONS} --file="2-select-partitions-to-detach.sql"
psql -q ${CONNECTION_OPTIONS} --command="CALL mark_sub_journal_partitions_to_detach('public','journal');"
showStructure

# show which tables are selected to remove
echo "checking how function select when snapshot is inserted"
psql -q ${CONNECTION_OPTIONS} --command="INSERT INTO public.SNAPSHOT(persistence_id, sequence_number, created, snapshot) VALUES ('p-1', 50, 0, '0x22');"
psql -q ${CONNECTION_OPTIONS} --command="CALL mark_sub_journal_partitions_to_detach('public','journal');"
showStructure

# show that table with sequenceNumber 89 is not added to remove, we are removing all tables up to the last snapshot
echo "checking how function select when snapshot is inserted for sequenceNumber 89"
psql -q ${CONNECTION_OPTIONS} --command="INSERT INTO public.SNAPSHOT(persistence_id, sequence_number, created, snapshot) VALUES ('p-1', 89, 0, '0x22');"
psql -q ${CONNECTION_OPTIONS} --command="CALL mark_sub_journal_partitions_to_detach('public','journal');"
showStructure

# create function for detaching and detaching itself
echo "detach"
psql -q ${CONNECTION_OPTIONS} --file="3-detach.sql"
psql -q ${CONNECTION_OPTIONS} --command="CALL detach_partitions_from_archivisation();"
showStructure

# crate dumps from detached tables
echo "export dumps"
./4-export-detached.sh
showStructure

# create function for delete dumped partitions and dump itself
echo "delete partitions"
psql -q ${CONNECTION_OPTIONS} --file="5-delete.sql"
psql -q ${CONNECTION_OPTIONS} --command="CALL drop_detached_partitions();"
showStructure

# import deleted partition from dump
echo "import partitions"
./8-import-deleted.sh 'public.j_p_1_1'
./8-import-deleted.sh 'public.j_p_1_2'
showStructure

# reattach partitions
echo "reattach partitions"
psql -q ${CONNECTION_OPTIONS} --file="9-attach.sql"
psql -q ${CONNECTION_OPTIONS} --command="CALL reattach_partitions_from_archivisation();"
showStructure

rm *.dump
