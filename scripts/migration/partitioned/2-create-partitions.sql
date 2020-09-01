CREATE OR REPLACE PROCEDURE create_partitions(IN partition_size BIGINT, IN source_schema TEXT, IN source_journal_table_name TEXT, IN destination_schema TEXT, IN destination_journal_table_name TEXT, IN destination_partition_prefix TEXT) AS
$$
DECLARE
    row                      record;
    max_ordering BIGINT;
BEGIN
    EXECUTE 'SELECT max(ordering) as max_ordering FROM ' || source_schema || '.' || source_journal_table_name || ';' INTO max_ordering;
    FOR i IN 0..(max_ordering/partition_size) LOOP
        EXECUTE 'CREATE TABLE IF NOT EXISTS ' || destination_schema || '.' || destination_partition_prefix ||'_' || i || ' PARTITION OF ' || destination_schema || '.' || destination_journal_table_name || ' FOR VALUES FROM (' || i*partition_size || ') TO (' || (i+1)*partition_size || ');';
    END LOOP;
END ;
$$ LANGUAGE plpgsql;
