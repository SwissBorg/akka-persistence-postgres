-- Ensure indexes exist on partitions, actual indexes are created manually before migration using CONCURRENTLY option.
-- This block is needed to avoid missing indexes if there was a new partition created between manual index creation and
-- actual migration. We cannot create indexes CONCURRENTLY here, as is not possible to create indexes CONCURRENTLY
-- inside transaction and functions are executed inside transaction.
DO $$
DECLARE
    -- adapt those to match journal configuration
    v_journal_table_name constant text := 'journal';
    v_column_persistence_id text = 'persistence_id';
    v_column_sequence_number text = 'sequence_number';
    -- do not change values below
    v_persistence_seq_idx constant text := '_persistence_sequence_idx';
    -- detect why schema flyway uses
    v_schema constant text := (select trim(both '"' from split_part(setting,',',1)) FROM pg_settings WHERE name = 'search_path');
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
        -- unique btree on (persistence_id, sequence_number)
        v_sql := 'CREATE UNIQUE INDEX IF NOT EXISTS ' || quote_ident(v_rec.child || v_persistence_seq_idx) || ' ON ' || quote_ident(v_schema) || '.' || quote_ident(v_rec.child) || ' USING BTREE (' || quote_ident(v_column_persistence_id) || ',' || quote_ident(v_column_sequence_number) || ');';
        RAISE notice 'Running DDL: %', v_sql;
        EXECUTE v_sql;

    END LOOP;
END;
$$;

-- drop global, non-unique index
DROP INDEX IF EXISTS journal_persistence_id_sequence_number_idx;
