PARTITION_NAME=$1
SCHEMA=$2
ARCHIVISATION_TABLE=$3

COUNT=$(psql -qt ${CONNECTION_OPTIONS} --command="SELECT COUNT(*) FROM ${SCHEMA}.${ARCHIVISATION_TABLE} WHERE status='DROPPED' AND schemaname || '.' || tablename = '${PARTITION_NAME}';")
if [ "$COUNT" -eq "0" ]; then
   echo "Partition '${PARTITION_NAME}' does not exist in table $COUNT";
else
  psql --set ON_ERROR_STOP=on ${CONNECTION_OPTIONS} < "${PARTITION_NAME}.dump"
  psql -qt ${CONNECTION_OPTIONS} --command="UPDATE ${SCHEMA}.${ARCHIVISATION_TABLE} SET status='REIMPORTED' WHERE schemaname || '.' || tablename = '${PARTITION_NAME}';"
  echo "Dump ${PARTITION_NAME} imported"
fi
