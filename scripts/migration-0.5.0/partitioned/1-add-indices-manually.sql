DO $$
DECLARE
    -- adapt those to match journal configuration
    v_journal_table_name constant text := 'journal';
    v_schema constant text := 'public';
    v_column_persistence_id text = 'persistence_id';
    v_column_sequence_number text = 'sequence_number';
    -- do not change values below
    v_persistence_seq_idx constant text := '_persistence_sequence_idx';
    v_rec record;
    v_sql text;
BEGIN
    FOR v_rec IN
        -- get list of partitions
        SELECT
            child.relname AS child
        FROM
            pg_inherits
            JOIN pg_class parent ON pg_inherits.inhparent = parent.oid
            JOIN pg_class child ON pg_inherits.inhrelid = child.oid
            JOIN pg_namespace nmsp_parent ON nmsp_parent.oid = parent.relnamespace
        WHERE
            parent.relname = v_journal_table_name AND
            nmsp_parent.nspname = v_schema
    LOOP
        PERFORM
        FROM
            pg_indexes
        WHERE
            schemaname = v_schema
            AND tablename = v_rec.child
            AND indexname = v_rec.child || v_persistence_seq_idx;
        IF NOT FOUND THEN
            -- unique btree on (persistence_id, sequence_number)
            v_sql := 'CREATE UNIQUE INDEX CONCURRENTLY ' || quote_ident(v_rec.child || v_persistence_seq_idx) || ' ON ' || quote_ident(v_schema) || '.' || quote_ident(v_rec.child) || ' USING BTREE (' || quote_ident(v_column_persistence_id) || ',' || quote_ident(v_column_sequence_number) || ');';
            RAISE notice 'Run DDL: %', v_sql;
        END IF;

    END LOOP;
END;
$$;
