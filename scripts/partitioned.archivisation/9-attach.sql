CREATE OR REPLACE PROCEDURE attach_partitions_from_archivisation() AS $$
DECLARE
    row         record;
BEGIN
    FOR row IN
        SELECT schemaname, tablename, parent_schemaname, parent_tablename, min_sequence_number, max_sequence_number
        FROM public.archivisation
        WHERE STATUS = 'REIMPORTED'
    LOOP
        EXECUTE 'ALTER TABLE ' || row.parent_schemaname || '.' || row.parent_tablename || ' ATTACH PARTITION ' || row.schemaname || '.' || row.tablename || ' FOR VALUES FROM ( ' || row.min_sequence_number || ' ) TO (' || row.max_sequence_number + 1 || ' ) ' ;
        DELETE FROM public.archivisation WHERE schemaname = row.schemaname AND tablename = row.tablename;
    END LOOP;
END; $$ LANGUAGE plpgsql;

CALL attach_partitions_from_archivisation();
