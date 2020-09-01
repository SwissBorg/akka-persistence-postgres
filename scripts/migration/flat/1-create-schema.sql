CREATE OR REPLACE PROCEDURE create_schema(IN destination_schema TEXT, IN destination_journal_table TEXT, IN destination_tag_table TEXT) AS
$$
DECLARE
    destination_journal TEXT;
    destination_tag TEXT;
BEGIN
    destination_journal := destination_schema || '.' || destination_journal_table;
    destination_tag := destination_schema || '.' || destination_tag_table;
    EXECUTE 'CREATE TABLE ' || destination_journal || '
    (
        ordering        BIGSERIAL,
        sequence_number BIGINT                NOT NULL,
        deleted         BOOLEAN DEFAULT FALSE NOT NULL,
        persistence_id  TEXT                  NOT NULL,
        message         BYTEA                 NOT NULL,
        tags            int[],
        PRIMARY KEY (persistence_id, sequence_number)
    );';

    EXECUTE 'CREATE TABLE ' || destination_tag || '
    (
        id   BIGSERIAL,
        name TEXT NOT NULL,
        PRIMARY KEY (id)
    );';

    EXECUTE 'CREATE UNIQUE INDEX ' || destination_tag_table || '_name_idx on ' || destination_tag || ' (name);';
END ;
$$ LANGUAGE plpgsql;
