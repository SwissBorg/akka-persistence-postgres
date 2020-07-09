CREATE OR REPLACE PROCEDURE find_and_mark_journal_nested_partitions_to_detach(IN schema TEXT, IN journal_table TEXT,
                                                                              IN snapshot_table TEXT,
                                                                              IN archivisation_table TEXT) AS
$$
DECLARE
    row                   record;
    min_sequence_number   BIGINT;
    inner_row             record;
    sequence_number_range record;
BEGIN
    FOR row IN
        SELECT nmsp_child.nspname AS child_schema,
               child.relname      AS child
        FROM pg_inherits
                 JOIN pg_class parent ON pg_inherits.inhparent = parent.oid
                 JOIN pg_class child ON pg_inherits.inhrelid = child.oid
                 JOIN pg_namespace nmsp_parent ON nmsp_parent.oid = parent.relnamespace
                 JOIN pg_namespace nmsp_child ON nmsp_child.oid = child.relnamespace
        WHERE parent.relname = journal_table
          AND nmsp_parent.nspname = schema
        LOOP
            EXECUTE 'SELECT max(snp.sequence_number) ' ||
                    'FROM ' || quote_ident(schema) || '.' || quote_ident(snapshot_table) || ' AS snp ' ||
                    'JOIN ' || quote_ident(row.child_schema) || '.' || quote_ident(row.child) || ' AS jrn ' ||
                    'ON snp.persistence_id = jrn.persistence_id ' ||
                    'AND snp.sequence_number = jrn.sequence_number'
                INTO min_sequence_number;

            FOR inner_row IN
                SELECT nmsp_child.nspname AS child_schema,
                       child.relname      AS child
                FROM pg_inherits
                         JOIN pg_class parent ON pg_inherits.inhparent = parent.oid
                         JOIN pg_class child ON pg_inherits.inhrelid = child.oid
                         JOIN pg_namespace nmsp_parent ON nmsp_parent.oid = parent.relnamespace
                         JOIN pg_namespace nmsp_child ON nmsp_child.oid = child.relnamespace
                WHERE parent.relname = row.child
                  AND nmsp_parent.nspname = row.child_schema
                LOOP

                    EXECUTE 'SELECT max(sequence_number) AS max, min(sequence_number) AS min, persistence_id
                            FROM ' || quote_ident(inner_row.child_schema) || '.' || quote_ident(inner_row.child) ||
                            ' GROUP BY persistence_id'
                        INTO sequence_number_range;

--          > - because we would like that last event remain in journal
                    IF min_sequence_number > sequence_number_range.max THEN
                        EXECUTE '
                INSERT INTO ' || schema || '.' || archivisation_table || '(persistence_id, min_sequence_number, max_sequence_number, schemaname,
                                                 tablename, parent_schemaname, parent_tablename, status)
                VALUES (''' || sequence_number_range.persistence_id || ''',' || sequence_number_range.min || ', ' ||
                                sequence_number_range.max || ', ''' || inner_row.child_schema || ''',''' ||
                                inner_row.child || ''',''' || row.child_schema || ''',''' || row.child || ''', ''NEW'')
                ON CONFLICT DO NOTHING;
                    ';
                    END IF;
                END LOOP;
        END LOOP;
END;
$$ LANGUAGE plpgsql;
