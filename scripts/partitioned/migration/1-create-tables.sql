CREATE TABLE public.journal_partitioned
(
    ordering        BIGSERIAL,
    sequence_number BIGINT                NOT NULL,
    deleted         BOOLEAN DEFAULT FALSE NOT NULL,
    persistence_id  TEXT                  NOT NULL,
    message         BYTEA                 NOT NULL,
    tags            int[],
    PRIMARY KEY (persistence_id, sequence_number)
) PARTITION BY LIST (persistence_id);

CREATE INDEX journal_partitioned_ordering_idx ON public.journal_partitioned USING BRIN (ordering);
CREATE INDEX journal_partitioned_tags_idx ON public.journal USING GIN (tags);

CREATE TABLE public.event_tag
(
    id   BIGSERIAL,
    name TEXT NOT NULL,
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX event_tag_name_idx on public.event_tag (name);
