CREATE OR REPLACE PROCEDURE create_archivisation_table(IN schema TEXT, IN archivisation_table TEXT) AS
$$
BEGIN
    EXECUTE 'CREATE TABLE IF NOT EXISTS ' || schema || '.' || archivisation_table || '
    (
        persistence_id      TEXT   NOT NULL,
        min_sequence_number BIGINT NOT NULL,
        max_sequence_number BIGINT NOT NULL,
        tablename           TEXT   NOT NULL,
        schemaname          TEXT   NOT NULL,
        parent_tablename    TEXT   NOT NULL,
        parent_schemaname   TEXT   NOT NULL,
        status              TEXT   NOT NULL,
        PRIMARY KEY (schemaname,tablename)
    );';
END ;
$$ LANGUAGE plpgsql;
