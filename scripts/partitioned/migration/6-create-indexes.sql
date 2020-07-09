CREATE OR REPLACE PROCEDURE create_schema(IN destination_schema TEXT, IN destination_journal_table TEXT) AS
$$
DECLARE
    destination_journal TEXT;
BEGIN
    destination_journal := destination_schema || '.' || destination_journal_table;

    EXECUTE 'CREATE INDEX ' || destination_journal_table || '_ordering_idx ON ' || destination_journal || ' USING BRIN (ordering);';
    EXECUTE 'CREATE INDEX ' || destination_journal_table || '_tags_idx ON ' || destination_journal || ' USING GIN (tags);';
END ;
$$ LANGUAGE plpgsql;
