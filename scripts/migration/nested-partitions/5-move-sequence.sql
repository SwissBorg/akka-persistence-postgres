CREATE OR REPLACE PROCEDURE move_sequence(IN destination_schema TEXT, IN destination_journal_table_name TEXT) AS
$$
DECLARE
    max_ordering BIGINT;
BEGIN
    EXECUTE 'SELECT max(ordering) FROM ' || destination_schema || '.' || destination_journal_table_name ||
            ';' INTO max_ordering;
    PERFORM setval(destination_schema || '.' || destination_journal_table_name || '_ordering_seq', max_ordering, true);
END ;
$$ LANGUAGE plpgsql;
