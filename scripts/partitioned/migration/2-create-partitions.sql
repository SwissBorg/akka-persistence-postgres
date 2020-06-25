CREATE OR REPLACE PROCEDURE create_sub_partitions(IN table_size BIGINT) AS
$$
DECLARE
    row                      record;
    transformed_partition_id TEXT;
BEGIN
    FOR row IN
        SELECT persistence_id, max(sequence_number) as max_sequence_number FROM public.journal GROUP BY persistence_id
        LOOP
            transformed_partition_id = REGEXP_REPLACE(row.persistence_id, '\W', '_', 'g');
            EXECUTE 'CREATE TABLE IF NOT EXISTS public.j_' || transformed_partition_id || ' PARTITION OF public.journal_partitioned FOR VALUES IN (''' || row.persistence_id || ''') PARTITION BY RANGE (sequence_number);';

            FOR i IN 1..((row.max_sequence_number/table_size) + 1) LOOP
                    EXECUTE 'CREATE TABLE IF NOT EXISTS public.j_' || transformed_partition_id || '_' || i ||' PARTITION OF public.j_' || transformed_partition_id || ' FOR VALUES FROM (' || (i-1)*table_size || ') TO (' || i*table_size || ');';
            END LOOP ;
        END LOOP;
END ;
$$ LANGUAGE plpgsql;
