CREATE OR REPLACE PROCEDURE dropPartitionsOf(IN _basetableschema TEXT, IN _basetablename TEXT) AS $$
DECLARE
    row     record;
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
            CALL dropPartitionsOf(row.child_schema, row.child);
            EXECUTE 'DROP TABLE ' || quote_ident(row.child_schema) || '.' || quote_ident(row.child);
            COMMIT;
        END LOOP;
END; $$ LANGUAGE plpgsql;

CALL dropPartitionsOf('public','journal');

DROP TABLE IF EXISTS public.journal;

CREATE TABLE IF NOT EXISTS public.journal
(
    ordering        BIGSERIAL,
    persistence_id  TEXT                       NOT NULL,
    sequence_number BIGINT                     NOT NULL,
    deleted         BOOLEAN      DEFAULT FALSE NOT NULL,
    tags            int[],
    message         BYTEA                      NOT NULL,
    PRIMARY KEY (persistence_id, sequence_number)
) PARTITION BY LIST (persistence_id);

CREATE INDEX journal_ordering_idx ON public.journal USING BRIN (ordering);

DROP TABLE IF EXISTS public.event_tag;

CREATE TABLE IF NOT EXISTS public.event_tag
(
    id              BIGSERIAL,
    name            TEXT                        NOT NULL,
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX IF NOT EXISTS event_tag_name_idx on public.event_tag (name);

DROP TABLE IF EXISTS public.snapshot;

CREATE TABLE IF NOT EXISTS public.snapshot
(
    persistence_id  TEXT   NOT NULL,
    sequence_number BIGINT NOT NULL,
    created         BIGINT NOT NULL,
    snapshot        BYTEA  NOT NULL,
    PRIMARY KEY (persistence_id, sequence_number)
);
