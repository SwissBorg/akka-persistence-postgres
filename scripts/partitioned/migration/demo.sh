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
  showPartitions "journal"
  showPartitions "j_p_1"
  showPartitions "j_p_2"
  showPartitions "j_p_3"
  showPartitions "j_p_4"
  showPartitions "j_p_5"
  sleep 3
}

#
echo "prepare demo, fill table with data"
showStructure
psql -qt ${CONNECTION_OPTIONS} --file="demo-prepare.sql"
showStructure

#
echo "create journal_partitioned"
psql -q ${CONNECTION_OPTIONS} --file="1-create-tables.sql"
showStructure

#
echo "create partitions"
psql -q ${CONNECTION_OPTIONS} --file="2-create-partitions.sql"
psql -q ${CONNECTION_OPTIONS} --command="CALL create_sub_partitions(10000);"
showStructure

#
echo "fill event-tag"
psql -q ${CONNECTION_OPTIONS} --file="3-fill-event-tag.sql"
psql -q ${CONNECTION_OPTIONS} --command="CALL fill_event_tag(',');"
#echo "$PARENT partitions:"
VALUES=$(psql -q ${CONNECTION_OPTIONS} --command="SELECT * from public.event_tag")
echo "$VALUES"

#
echo "copy data"
psql -q ${CONNECTION_OPTIONS} --file="4-copy-data.sql"
psql -q ${CONNECTION_OPTIONS} --command="CALL copy_data(0, 10000, ',');"
#echo "$PARENT partitions:"
psql -q ${CONNECTION_OPTIONS} --command="SELECT count(*) from public.j_p_1"
psql -q ${CONNECTION_OPTIONS} --command="SELECT count(*) from public.j_p_2"
psql -q ${CONNECTION_OPTIONS} --command="SELECT count(*) from public.j_p_3"
psql -q ${CONNECTION_OPTIONS} --command="SELECT count(*) from public.j_p_4"
psql -q ${CONNECTION_OPTIONS} --command="SELECT count(*) from public.j_p_5"


