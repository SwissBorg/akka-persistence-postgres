PARTITION_NAME=$1

COUNT=$(psql -qt ${CONNECTION_OPTIONS} --command="SELECT COUNT(*) FROM public.archivisation WHERE status='DROPPED' AND schemaname || '.' || tablename = '${PARTITION_NAME}';")
if [ "$COUNT" -eq "0" ]; then
   echo "Partition '${PARTITION_NAME}' does not exist in table $COUNT";
else
  psql --set ON_ERROR_STOP=on ${CONNECTION_OPTIONS} < "${PARTITION_NAME}.dump"
  psql -qt ${CONNECTION_OPTIONS} --command="UPDATE public.archivisation SET status='REIMPORTED' WHERE schemaname || '.' || tablename = '${PARTITION_NAME}';"
  echo "Dump ${PARTITION_NAME} imported"
fi
