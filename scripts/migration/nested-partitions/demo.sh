export PGPASSWORD='docker'
export CONNECTION_OPTIONS=' --dbname=docker --username=docker --host=localhost '

###############################
# DO NOT RUN THIS ON PRODUCTION
echo ""
echo "prepare demo, fill table with data"
psql -qt ${CONNECTION_OPTIONS} --file="demo-prepare.sql"
###############################

#############################
# SCHEMA configuration
# demo is not prepared for other values, however for your case it could be really useful
#############################
export SOURCE_SCHEMA='public'
export SOURCE_JOURNAL_TABLE_NAME='journal'
export SOURCE_TAG_SEPARATOR=','
export DESTINATION_SCHEMA='public'
export DESTINATION_JOURNAL_TABLE_NAME='journal_nested'
export DESTINATION_TAGS_TABLE_NAME='tags'
export DESTINATION_JOURNAL_PARTITION_PREFIX='j'
export PARTITION_SIZE=100000 # should be much much bigger on production, we suggest at least 1000000
export BATCH_SIZE=10000 # size of single commit during migration
#############################

function showStructure() {
  echo ""
  echo "existing tables:"
  VALUES=$(psql -qt ${CONNECTION_OPTIONS} --command="select table_name from information_schema.tables where table_schema IN ('${DESTINATION_SCHEMA}', '${SOURCE_SCHEMA}')")
  echo "$VALUES"
  sleep 1
  echo "partitions:"
  VALUES=$(psql -qt ${CONNECTION_OPTIONS} --command="SELECT child.relname AS child, parent.relname as parent FROM pg_inherits JOIN pg_class parent ON pg_inherits.inhparent = parent.oid JOIN pg_class child ON pg_inherits.inhrelid = child.oid JOIN pg_namespace nmsp_parent ON nmsp_parent.oid = parent.relnamespace JOIN pg_namespace nmsp_child ON nmsp_child.oid = child.relnamespace WHERE (parent.relname='${DESTINATION_JOURNAL_TABLE_NAME}' OR parent.relname like '${DESTINATION_JOURNAL_PARTITION_PREFIX}_%')  AND nmsp_parent.nspname ='${DESTINATION_SCHEMA}' AND child.relkind='r' ORDER BY parent.relname, child.relname")
  echo "$VALUES"
  sleep 2
}

#
showStructure

#
echo ""
echo "create journal_nested"
psql -q ${CONNECTION_OPTIONS} --file="1-create-schema.sql"
psql -q ${CONNECTION_OPTIONS} --command="CALL create_schema('${DESTINATION_SCHEMA}', '${DESTINATION_JOURNAL_TABLE_NAME}', '${DESTINATION_TAGS_TABLE_NAME}');"
showStructure

#
echo ""
echo "create partitions"
psql -q ${CONNECTION_OPTIONS} --file="2-create-partitions.sql"
psql -q ${CONNECTION_OPTIONS} --command="CALL create_sub_partitions(${PARTITION_SIZE}, '${SOURCE_SCHEMA}', '${SOURCE_JOURNAL_TABLE_NAME}', '${DESTINATION_SCHEMA}', '${DESTINATION_JOURNAL_TABLE_NAME}', '${DESTINATION_JOURNAL_PARTITION_PREFIX}');"
showStructure

#
echo ""
echo "fill event-tags table"
psql -q ${CONNECTION_OPTIONS} --file="3-fill-event-tag.sql"
psql -q ${CONNECTION_OPTIONS} --command="CALL fill_tags('${SOURCE_TAG_SEPARATOR}', '${SOURCE_SCHEMA}', '${SOURCE_JOURNAL_TABLE_NAME}', '${DESTINATION_SCHEMA}', '${DESTINATION_TAGS_TABLE_NAME}');"
VALUES=$(psql -q ${CONNECTION_OPTIONS} --command="SELECT * from ${DESTINATION_SCHEMA}.${DESTINATION_TAGS_TABLE_NAME}")
echo "$VALUES"

#
echo ""
echo "copy data"
psql -q ${CONNECTION_OPTIONS} --file="4-copy-data.sql"
time psql -q ${CONNECTION_OPTIONS} --command="CALL copy_data(0, ${BATCH_SIZE}, '${SOURCE_TAG_SEPARATOR}', '${SOURCE_SCHEMA}', '${SOURCE_JOURNAL_TABLE_NAME}', '${DESTINATION_SCHEMA}', '${DESTINATION_JOURNAL_TABLE_NAME}', '${DESTINATION_TAGS_TABLE_NAME}');"
psql -q ${CONNECTION_OPTIONS} --command="SELECT count(*) as events, persistence_id from ${DESTINATION_SCHEMA}.${DESTINATION_JOURNAL_TABLE_NAME} group by persistence_id"

sleep 3
echo ""
echo "set sequence to last ordering value"
psql -q ${CONNECTION_OPTIONS} --file="5-move-sequence.sql"
psql -q ${CONNECTION_OPTIONS} --command="CALL move_sequence('${DESTINATION_SCHEMA}', '${DESTINATION_JOURNAL_TABLE_NAME}');"

sleep 3
echo ""
echo "create indexes on partitioned table"
psql -q ${CONNECTION_OPTIONS} --file="6-create-indexes.sql"
psql -q ${CONNECTION_OPTIONS} --command="CALL create_indexes('${DESTINATION_SCHEMA}', '${DESTINATION_JOURNAL_TABLE_NAME}');"

echo ""
echo "Remember to change journal table name in plugin configuration"
#
# TESTING CORRECTNESS OF MIGRATION
#
function showMissingOrdering() {
  table_name=$1
  echo ""
  echo "missing ordering for ${table_name}:"
  MAX_ORDERING=$(psql -qt ${CONNECTION_OPTIONS} --command="SELECT max(ordering) from ${table_name}")
  VALUES=$(psql -qt ${CONNECTION_OPTIONS} --command="select i from generate_series(1, ${MAX_ORDERING}) s(i) LEFT JOIN ${table_name} jrn ON jrn.ordering=i where jrn.ordering IS NULL ORDER BY i;")
  # empty means that it is ok
  echo "$VALUES"
  sleep 1
}

sleep 3
echo ""
echo "show missing ordering for original and partitioned table"
showMissingOrdering "${SOURCE_SCHEMA}.${SOURCE_JOURNAL_TABLE_NAME}"
showMissingOrdering "${DESTINATION_SCHEMA}.${DESTINATION_JOURNAL_TABLE_NAME}"

#echo ""
#echo "next sequence number in partitioned"
#psql -q ${CONNECTION_OPTIONS} --command="SELECT nextval('journal_nested_ordering_seq');" # dont do it on production !!!

#psql -qt ${CONNECTION_OPTIONS} --command="INSERT INTO public.journal(persistence_id, sequence_number, deleted, tags, message) VALUES ('pp-1', 1000001, true, '{}', '0x22');"
#psql -qt ${CONNECTION_OPTIONS} --command="INSERT INTO public.journal_nested(persistence_id, sequence_number, deleted, tags, message) VALUES ('pp-1', 1000001, true, '{}', '0x22');"

sleep 3
echo ""
echo "missing orderings in journal or journal_nested"
psql -q ${CONNECTION_OPTIONS} --command="select jrn.ordering as original, jrp.ordering as partitioned from ${SOURCE_SCHEMA}.${SOURCE_JOURNAL_TABLE_NAME} jrn FULL JOIN ${DESTINATION_SCHEMA}.${DESTINATION_JOURNAL_TABLE_NAME} jrp ON jrn.ordering=jrp.ordering where jrn.ordering IS NULL OR jrp.ordering IS NULL ORDER BY jrp.ordering, jrn.ordering;"

echo ""
echo "Removing migration and demo procedures"
psql -q ${CONNECTION_OPTIONS} --file="7-drop-migration-procedures.sql"
