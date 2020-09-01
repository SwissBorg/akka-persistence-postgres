CREATE OR REPLACE PROCEDURE reattach_partitions_from_archivisation(IN schema TEXT, IN archivisation_table TEXT) AS
$$
DECLARE
    row record;
BEGIN
    FOR row IN EXECUTE '
        SELECT schemaname, tablename, parent_schemaname, parent_tablename, min_sequence_number, max_sequence_number
        FROM ' || schema || '.' || archivisation_table || '
        WHERE STATUS = ''REIMPORTED'''
        LOOP
            EXECUTE 'ALTER TABLE ' || row.parent_schemaname || '.' || row.parent_tablename ||
                    ' ATTACH PARTITION ' || row.schemaname || '.' || row.tablename ||
                    ' FOR VALUES FROM (' || row.min_sequence_number || ') TO (' || row.max_sequence_number + 1 || ')';
            EXECUTE 'DELETE FROM ' || schema || '.' || archivisation_table ||
                    ' WHERE schemaname = ''' || row.schemaname || ''' AND tablename = ''' || row.tablename || ''';';
        END LOOP;
END ;
$$ LANGUAGE plpgsql;
