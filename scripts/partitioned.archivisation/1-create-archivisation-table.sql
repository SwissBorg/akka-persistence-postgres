CREATE TABLE IF NOT EXISTS public.archivisation
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
);
