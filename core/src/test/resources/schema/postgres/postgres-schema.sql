DROP TABLE IF EXISTS public.journal;

CREATE TABLE IF NOT EXISTS public.journal
(
    ordering        BIGSERIAL,
    persistence_id  TEXT                       NOT NULL,
    sequence_number BIGINT                     NOT NULL,
    deleted         BOOLEAN      DEFAULT FALSE NOT NULL,
    tags            VARCHAR(255) DEFAULT NULL,
    message         BYTEA                      NOT NULL,
    PRIMARY KEY (persistence_id, sequence_number)
) PARTITION BY LIST (persistence_id);

CREATE INDEX journal_ordering_idx ON journal USING BRIN (ordering);

DROP TABLE IF EXISTS public.snapshot;

CREATE TABLE IF NOT EXISTS public.snapshot
(
    persistence_id  TEXT   NOT NULL,
    sequence_number BIGINT NOT NULL,
    created         BIGINT NOT NULL,
    snapshot        BYTEA  NOT NULL,
    PRIMARY KEY (persistence_id, sequence_number)
);
