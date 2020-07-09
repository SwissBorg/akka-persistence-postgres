CREATE OR REPLACE PROCEDURE drop_detached_partitions(IN schema TEXT, IN archivisation_table TEXT) AS
$$
DECLARE
    row record;
BEGIN
    FOR row IN EXECUTE 'SELECT schemaname, tablename
        FROM ' || schema || '.' || archivisation_table || '
        WHERE STATUS = ''DUMPED'''
        LOOP
            EXECUTE 'DROP TABLE ' || row.schemaname || '.' || row.tablename;
            EXECUTE 'UPDATE ' || schema || '.' || archivisation_table ||
                    ' SET STATUS = ''DROPPED'' WHERE schemaname = ''' || row.schemaname || ''' AND tablename = ''' || row.tablename || ''';';
        END LOOP;
END ;
$$ LANGUAGE plpgsql;
