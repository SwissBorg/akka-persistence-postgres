
CREATE OR REPLACE PROCEDURE fifi(IN _basetableschema TEXT, IN _basetablename TEXT, IN min_sequence_number_in_parent BIGINT) AS $$
DECLARE
    row     record;
max_sequence_number BIGINT;
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

            EXECUTE 'select max(sequence_number) from ' || quote_ident(row.child_schema) || '.' || quote_ident(row.child)
                INTO max_sequence_number;
            IF min_sequence_number_in_parent > max_sequence_number THEN
                raise notice 'can be detached: % for %' , max_sequence_number, quote_ident(row.child);
                raise notice 'ALTER TABLE %.% DETACH PARTITION %.%;', quote_ident(_basetableschema), quote_ident(_basetablename), quote_ident(row.child_schema), quote_ident(row.child);
            ELSE
                raise notice 'should stay: % for %' , max_sequence_number, quote_ident(row.child);
            END IF;
        END LOOP;
END; $$ LANGUAGE plpgsql;

CREATE OR REPLACE PROCEDURE mimi(IN _basetableschema TEXT, IN _basetablename TEXT) AS $$
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
            EXECUTE 'select min(sequence_number) from ' || quote_ident(row.child_schema) || '.' || quote_ident(row.child) || ' where deleted = false'
                INTO min_sequence_number;
            CALL fifi(row.child_schema, row.child, min_sequence_number);
        END LOOP;
END; $$ LANGUAGE plpgsql;

CALL mimi('public','journal');
