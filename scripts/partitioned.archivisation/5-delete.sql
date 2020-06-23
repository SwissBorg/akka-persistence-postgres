CREATE OR REPLACE PROCEDURE drop_detached_partitions() AS $$
DECLARE
    row         record;
BEGIN
    FOR row IN
        SELECT schemaname, tablename
        FROM public.archivisation
        WHERE STATUS = 'DUMPED'
    LOOP
        EXECUTE 'DROP TABLE ' || row.schemaname || '.' || row.tablename;
        UPDATE public.archivisation SET STATUS = 'DROPPED' WHERE schemaname = row.schemaname AND tablename = row.tablename;
    END LOOP;
END; $$ LANGUAGE plpgsql;

CALL drop_detached_partitions();
