export PGPASSWORD='docker'
CONNECTION_OPTIONS=' --dbname=docker --username=docker --host=localhost '

for i in $(psql -qt ${CONNECTION_OPTIONS} --command="SELECT schemaname || '.' || tablename FROM public.archivisation WHERE status='DETACHED';")
do
   echo "dumping $i"
   pg_dump ${CONNECTION_OPTIONS} --table="$i" > "$i.dump"
   psql -qt ${CONNECTION_OPTIONS} --command="UPDATE public.archivisation SET status='DUMPED' WHERE schemaname || '.' || tablename = '$i';"
   echo "dumped $i"
done
