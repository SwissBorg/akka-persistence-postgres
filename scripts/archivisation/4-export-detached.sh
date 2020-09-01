SCHEMA=$1
ARCHIVISATION_TABLE=$2

for i in $(psql -qt ${CONNECTION_OPTIONS} --command="SELECT schemaname || '.' || tablename FROM ${SCHEMA}.${ARCHIVISATION_TABLE} WHERE status='DETACHED';")
do
   echo "dumping $i"
   pg_dump ${CONNECTION_OPTIONS} --table="$i" > "$i.dump"
   psql -qt ${CONNECTION_OPTIONS} --command="UPDATE ${SCHEMA}.${ARCHIVISATION_TABLE} SET status='DUMPED' WHERE schemaname || '.' || tablename = '$i';"
   echo "dumped $i"
done
