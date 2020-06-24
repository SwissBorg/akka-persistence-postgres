DROP TABLE IF EXISTS public.journal_partitioned CASCADE;
CREATE TABLE IF NOT EXISTS public.journal_partitioned
(
    ordering        BIGSERIAL,
    persistence_id  TEXT                  NOT NULL,
    sequence_number BIGINT                NOT NULL,
    deleted         BOOLEAN DEFAULT FALSE NOT NULL,
    tags            int[],
    message         BYTEA                 NOT NULL,
    PRIMARY KEY (persistence_id, sequence_number)
) PARTITION BY LIST (persistence_id);

CREATE INDEX journal_partitioned_ordering_idx ON public.journal_partitioned USING BRIN (ordering);

DROP TABLE IF EXISTS public.event_tag;
CREATE TABLE IF NOT EXISTS public.event_tag
(
    id   BIGSERIAL,
    name TEXT NOT NULL,
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX IF NOT EXISTS event_tag_name_idx on public.event_tag (name);

-- tables only for migration

DROP TABLE IF EXISTS public.migration_missing_orderings;
CREATE TABLE IF NOT EXISTS public.migration_missing_orderings
(
    ordering BIGINT,
    PRIMARY KEY (ordering)
);

DROP TABLE IF EXISTS public.migration_progress;
CREATE TABLE IF NOT EXISTS public.migration_progress
(
    id                BIGSERIAL,
    migrated_ordering BIGINT,
    PRIMARY KEY (id)
);
