CREATE OR REPLACE PROCEDURE create_sub_partitions(IN partition_size BIGINT, IN source_schema TEXT, IN source_journal_table_name TEXT, IN destination_schema TEXT, IN destination_journal_table_name TEXT, IN destination_partition_prefix TEXT) AS
$$
DECLARE
    row                      record;
    transformed_partition_id TEXT;
BEGIN
    FOR row IN EXECUTE 'SELECT persistence_id, max(sequence_number) as max_sequence_number FROM ' || source_schema || '.' || source_journal_table_name || ' GROUP BY persistence_id'
        LOOP
            transformed_partition_id = REGEXP_REPLACE(row.persistence_id, '\W', '_', 'g');
            EXECUTE 'CREATE TABLE IF NOT EXISTS ' || destination_schema || '.' || destination_partition_prefix ||'_' || transformed_partition_id || ' PARTITION OF ' || destination_schema || '.' || destination_journal_table_name || ' FOR VALUES IN (''' || row.persistence_id || ''') PARTITION BY RANGE (sequence_number);';

            FOR i IN 0..(row.max_sequence_number/partition_size) LOOP
                    EXECUTE 'CREATE TABLE IF NOT EXISTS ' || destination_schema || '.' || destination_partition_prefix ||'_' ||  transformed_partition_id || '_' || i ||' PARTITION OF ' || destination_schema || '.' || destination_partition_prefix ||'_' || transformed_partition_id || ' FOR VALUES FROM (' || i*partition_size || ') TO (' || (i+1)*partition_size || ');';
            END LOOP ;
        END LOOP;
END ;
$$ LANGUAGE plpgsql;
