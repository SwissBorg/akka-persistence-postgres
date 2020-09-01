CREATE OR REPLACE PROCEDURE detach_partitions_from_archivisation(IN schema TEXT, IN archivisation_table TEXT) AS
$$
DECLARE
    row record;
BEGIN
    FOR row IN EXECUTE '
        SELECT schemaname, tablename, parent_schemaname, parent_tablename
        FROM ' || schema || '.' || archivisation_table || '
        WHERE STATUS = ''NEW'''
        LOOP
            EXECUTE 'ALTER TABLE ' || row.parent_schemaname || '.' || row.parent_tablename ||
                    ' DETACH PARTITION ' || row.schemaname || '.' || row.tablename;
            EXECUTE 'UPDATE ' || schema || '.' || archivisation_table ||
                    ' SET STATUS = ''DETACHED''
                    WHERE schemaname = ''' || row.schemaname || ''' AND tablename = ''' || row.tablename || ''';';
        END LOOP;
END ;
$$ LANGUAGE plpgsql;
