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
);

CREATE INDEX journal_ordering_idx ON public.journal USING BRIN (ordering);
CREATE INDEX journal_tags_idx ON public.journal USING GIN(tags);

DROP TABLE IF EXISTS public.tags;

CREATE TABLE IF NOT EXISTS public.tags
(
    id              BIGSERIAL,
    name            TEXT                        NOT NULL,
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX IF NOT EXISTS tags_name_idx on public.tags (name);

DROP TABLE IF EXISTS public.snapshot;

CREATE TABLE IF NOT EXISTS public.snapshot
(
    persistence_id  TEXT   NOT NULL,
    sequence_number BIGINT NOT NULL,
    created         BIGINT NOT NULL,
    snapshot        BYTEA  NOT NULL,
    PRIMARY KEY (persistence_id, sequence_number)
);
