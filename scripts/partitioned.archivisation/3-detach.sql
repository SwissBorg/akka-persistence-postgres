CREATE OR REPLACE PROCEDURE detach_partitions_from_archivisation() AS $$
DECLARE
    row         record;
BEGIN
    FOR row IN
        SELECT schemaname, tablename, parent_schemaname, parent_tablename
        FROM public.archivisation
        WHERE STATUS = 'NEW'
    LOOP
        EXECUTE 'ALTER TABLE ' || row.parent_schemaname || '.' || row.parent_tablename || ' DETACH PARTITION ' || row.schemaname || '.' || row.tablename;
        UPDATE public.archivisation SET STATUS = 'DETACHED' WHERE schemaname = row.schemaname AND tablename = row.tablename;
    END LOOP;
END; $$ LANGUAGE plpgsql;

CALL detach_partitions_from_archivisation();
