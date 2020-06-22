
CREATE OR REPLACE PROCEDURE mark_sub_sub_journal_partitions_to_detach(IN _basetableschema TEXT, IN _basetablename TEXT, IN min_sequence_number_in_parent BIGINT) AS $$
DECLARE
    row                     record;
    sequence_number_range   record;
BEGIN
    FOR row IN
        SELECT
            nmsp_child.nspname  AS child_schema,
            child.relname       AS child
        FROM pg_inherits
                 JOIN pg_class parent            ON pg_inherits.inhparent = parent.oid
                 JOIN pg_class child             ON pg_inherits.inhrelid   = child.oid
                 JOIN pg_namespace nmsp_parent   ON nmsp_parent.oid  = parent.relnamespace
                 JOIN pg_namespace nmsp_child    ON nmsp_child.oid   = child.relnamespace
        WHERE parent.relname=_basetablename
          AND nmsp_parent.nspname =_basetableschema
        LOOP

            EXECUTE 'SELECT max(sequence_number) AS max, min(sequence_number) AS min, persistence_id FROM ' || quote_ident(row.child_schema) || '.' || quote_ident(row.child) || ' GROUP BY persistence_id'
                INTO sequence_number_range;

--          > - because we would like that last event remain in journal
            IF min_sequence_number_in_parent > sequence_number_range.max THEN
                INSERT INTO public.archivisation(persistence_id, min_sequence_number, max_sequence_number, schemaname, tablename, parent_schemaname, parent_tablename, status)
                VALUES(sequence_number_range.persistence_id, sequence_number_range.min, sequence_number_range.max, row.child_schema, row.child, _basetableschema, _basetablename, 'NEW');
            END IF;
        END LOOP;
END; $$ LANGUAGE plpgsql;

CREATE OR REPLACE PROCEDURE mark_sub_journal_partitions_to_detach(IN _basetableschema TEXT, IN _basetablename TEXT) AS $$
DECLARE
    row     record;
    min_sequence_number BIGINT;
BEGIN
    FOR row IN
        SELECT
            nmsp_child.nspname  AS child_schema,
            child.relname       AS child
        FROM pg_inherits
                 JOIN pg_class parent            ON pg_inherits.inhparent = parent.oid
                 JOIN pg_class child             ON pg_inherits.inhrelid   = child.oid
                 JOIN pg_namespace nmsp_parent   ON nmsp_parent.oid  = parent.relnamespace
                 JOIN pg_namespace nmsp_child    ON nmsp_child.oid   = child.relnamespace
        WHERE parent.relname=_basetablename
          AND nmsp_parent.nspname =_basetableschema
        LOOP
            EXECUTE 'SELECT max(snp.sequence_number) ' ||
                    'FROM snapshot AS snp ' ||
                        'JOIN ' || quote_ident(row.child_schema) || '.' || quote_ident(row.child) || ' AS jrn ' ||
                            'ON snp.persistence_id = jrn.persistence_id ' ||
                            'AND snp.sequence_number = jrn.sequence_number'
                INTO min_sequence_number;
            CALL mark_sub_sub_journal_partitions_to_detach(row.child_schema, row.child, min_sequence_number);
        END LOOP;
END; $$ LANGUAGE plpgsql;

CALL mark_sub_journal_partitions_to_detach('public','journal');
