CREATE OR REPLACE PROCEDURE mark_journal_nested_partitions_to_detach(IN _basetableschema TEXT, IN _basetablename TEXT,
                                                                      IN archivisation_schema TEXT,
                                                                      IN archivisation_table TEXT,
                                                                      IN min_sequence_number_in_parent BIGINT) AS
$$
DECLARE
    row                   record;
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
        WHERE parent.relname = _basetablename
          AND nmsp_parent.nspname = _basetableschema
        LOOP

            EXECUTE 'SELECT max(sequence_number) AS max, min(sequence_number) AS min, persistence_id FROM ' ||
                    quote_ident(row.child_schema) || '.' || quote_ident(row.child) || ' GROUP BY persistence_id'
                INTO sequence_number_range;

--          > - because we would like that last event remain in journal
            IF min_sequence_number_in_parent > sequence_number_range.max THEN
                EXECUTE '
                INSERT INTO ' || archivisation_schema || '.' || archivisation_table || '(persistence_id, min_sequence_number, max_sequence_number, schemaname,
                                                 tablename, parent_schemaname, parent_tablename, status)
                VALUES (''' || sequence_number_range.persistence_id || ''',' || sequence_number_range.min || ', ' ||
                        sequence_number_range.max || ', ''' ||
                        row.child_schema || ''',''' || row.child || ''',''' || _basetableschema || ''',''' ||
                        _basetablename || ''', ''NEW'')
                ON CONFLICT DO NOTHING;
                    ';
            END IF;
        END LOOP;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE PROCEDURE find_and_mark_journal_nested_partitions_to_detach(IN schema TEXT, IN journal_table TEXT,
                                                                  IN snapshot_table TEXT,
                                                                  IN archivisation_table TEXT) AS
$$
DECLARE
    row                 record;
    min_sequence_number BIGINT;
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
            CALL mark_journal_nested_partitions_to_detach(row.child_schema, row.child, schema, archivisation_table,
                                                           min_sequence_number);
        END LOOP;
END;
$$ LANGUAGE plpgsql;
