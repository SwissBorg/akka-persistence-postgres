export PGPASSWORD='docker'
export CONNECTION_OPTIONS=' --dbname=docker --username=docker --host=localhost '

JOURNAL_PARTITIONS_QUERY="SELECT child.relname AS child FROM pg_inherits JOIN pg_class parent ON pg_inherits.inhparent = parent.oid JOIN pg_class child ON pg_inherits.inhrelid = child.oid JOIN pg_namespace nmsp_parent ON nmsp_parent.oid = parent.relnamespace JOIN pg_namespace nmsp_child ON nmsp_child.oid = child.relnamespace WHERE parent.relname='journal' AND nmsp_parent.nspname ='public'"
J_P_1__PARTITIONS_QUERY="SELECT child.relname AS child FROM pg_inherits JOIN pg_class parent ON pg_inherits.inhparent = parent.oid JOIN pg_class child ON pg_inherits.inhrelid = child.oid JOIN pg_namespace nmsp_parent ON nmsp_parent.oid = parent.relnamespace JOIN pg_namespace nmsp_child ON nmsp_child.oid = child.relnamespace WHERE parent.relname='j_p_1' AND nmsp_parent.nspname ='public'"

function showPartitions() {
  PARENT=$1
  echo ""
  echo "$PARENT partitions:"
  VALUES=$(psql -qt ${CONNECTION_OPTIONS} --command="SELECT child.relname AS child FROM pg_inherits JOIN pg_class parent ON pg_inherits.inhparent = parent.oid JOIN pg_class child ON pg_inherits.inhrelid = child.oid JOIN pg_namespace nmsp_parent ON nmsp_parent.oid = parent.relnamespace JOIN pg_namespace nmsp_child ON nmsp_child.oid = child.relnamespace WHERE parent.relname='${PARENT}' AND nmsp_parent.nspname ='public'")
  echo "$VALUES"
  sleep 1
}

function showStructure() {
  echo ""
  echo "existing tables:"
  VALUES=$(psql -qt ${CONNECTION_OPTIONS} --command="select table_name from information_schema.tables where table_schema = 'public'")
  echo "$VALUES"
  sleep 1
  showPartitions "journal_partitioned"
  showPartitions "j_p_1"
  showPartitions "j_p_2"
  showPartitions "j_p_3"
  showPartitions "j_p_4"
  showPartitions "j_p_5"
  sleep 3
}

#
echo ""
echo "prepare demo, fill table with data"
showStructure
psql -qt ${CONNECTION_OPTIONS} --file="demo-prepare.sql"
showStructure

#
echo ""
echo "create journal_partitioned"
psql -q ${CONNECTION_OPTIONS} --file="1-create-tables.sql"
showStructure

#
echo ""
echo "create partitions"
psql -q ${CONNECTION_OPTIONS} --file="2-create-partitions.sql"
psql -q ${CONNECTION_OPTIONS} --command="CALL create_sub_partitions(10000);"
showStructure

#
echo ""
echo "fill event-tag"
psql -q ${CONNECTION_OPTIONS} --file="3-fill-event-tag.sql"
psql -q ${CONNECTION_OPTIONS} --command="CALL fill_event_tag(',');"
#echo "$PARENT partitions:"
VALUES=$(psql -q ${CONNECTION_OPTIONS} --command="SELECT * from public.event_tag")
echo "$VALUES"

#
echo ""
echo "copy data"
psql -q ${CONNECTION_OPTIONS} --file="4-copy-data.sql"
psql -q ${CONNECTION_OPTIONS} --command="CALL copy_data(0, 10000, ',');"
psql -q ${CONNECTION_OPTIONS} --command="SELECT count(*) from public.j_p_1"
psql -q ${CONNECTION_OPTIONS} --command="SELECT count(*) from public.j_p_2"
psql -q ${CONNECTION_OPTIONS} --command="SELECT count(*) from public.j_p_3"
psql -q ${CONNECTION_OPTIONS} --command="SELECT count(*) from public.j_p_4"
psql -q ${CONNECTION_OPTIONS} --command="SELECT count(*) from public.j_p_5"

echo ""
echo "set sequence to prepare value"
psql -q ${CONNECTION_OPTIONS} --file="5-move-sequence.sql"
psql -q ${CONNECTION_OPTIONS} --command="CALL move_sequence();"
psql -q ${CONNECTION_OPTIONS} --command="SELECT currval('journal_partitioned_ordering_seq');"

#
# TESTING CORRECTNESS OF MIGRATION
#
function showMissingOrdering() {
  table_name=$1
  echo ""
  echo "missing ordering for ${table_name}:"
  MAX_ORDERING=$(psql -qt ${CONNECTION_OPTIONS} --command="SELECT max(ordering) from public.${table_name}")
  VALUES=$(psql -qt ${CONNECTION_OPTIONS} --command="select i from generate_series(1, ${MAX_ORDERING}) s(i) LEFT JOIN public.${table_name} jrn ON jrn.ordering=i where jrn.ordering IS NULL ORDER BY i;")
  # empty means that it is ok
  echo "$VALUES"
  sleep 1
}

echo ""
echo "show missing ordering for original and partitioned table"
showMissingOrdering journal
showMissingOrdering journal_partitioned

echo ""
echo "missing orderings in journal or journal_partitioned"
psql -qt ${CONNECTION_OPTIONS} --command="select jrn.ordering as original, jrp.ordering as partitioned from public.journal jrn LEFT JOIN public.journal_partitioned jrp ON jrn.ordering=jrp.ordering where jrn.ordering IS NULL OR jrp.ordering IS NULL ORDER BY jrp.ordering, jrn.ordering;"


#psql -qt ${CONNECTION_OPTIONS} --command="INSERT INTO public.journal_partitioned(persistence_id, sequence_number, deleted, tags, message) VALUES ('p-1', 1000001, true, '{}', '0x22');"

#echo ""
#echo "next sequence number in partitioned"
#psql -q ${CONNECTION_OPTIONS} --command="SELECT nextval('journal_partitioned_ordering_seq');" # dont do it on production !!!
