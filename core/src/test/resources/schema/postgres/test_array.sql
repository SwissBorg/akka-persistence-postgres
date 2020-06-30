DROP TABLE IF EXISTS public.journal;

CREATE TABLE IF NOT EXISTS public.journal
(
    ordering        BIGSERIAL,
    sequence_number BIGINT                     NOT NULL,
    deleted         BOOLEAN      DEFAULT FALSE NOT NULL,
    persistence_id  TEXT                       NOT NULL,
    message         BYTEA                      NOT NULL,
    tags            int[],
    PRIMARY KEY (persistence_id, sequence_number)
);

CREATE INDEX journal_ordering_idx ON public.journal USING BRIN (ordering);
CREATE INDEX journal_tags_idx ON public.journal USING GIN(tags);

INSERT INTO public.journal(persistence_id, sequence_number, deleted, tags, message)
values( 'p-1', -1, false , null, '\x0a0708141203612d3110011a03702d316a2461313164393136332d633365322d343136322d386630362d39623233396663386635383070a8ccefd2dd5c');
INSERT INTO public.journal(persistence_id, sequence_number, deleted, tags, message)
values( 'p-1', 0, false , '{}', '\x0a0708141203612d3110011a03702d316a2461313164393136332d633365322d343136322d386630362d39623233396663386635383070a8ccefd2dd5c');
INSERT INTO public.journal(persistence_id, sequence_number, deleted, tags, message)
values( 'p-1', 1, false , '{1}', '\x0a0708141203612d3110011a03702d316a2461313164393136332d633365322d343136322d386630362d39623233396663386635383070a8ccefd2dd5c');
INSERT INTO public.journal(persistence_id, sequence_number, deleted, tags, message)
values( 'p-1', 2, false , '{1,2}', '\x0a0708141203612d3110011a03702d316a2461313164393136332d633365322d343136322d386630362d39623233396663386635383070a8ccefd2dd5c');
INSERT INTO public.journal(persistence_id, sequence_number, deleted, tags, message)
values( 'p-1', 3, false , '{1,2,3}', '\x0a0708141203612d3110011a03702d316a2461313164393136332d633365322d343136322d386630362d39623233396663386635383070a8ccefd2dd5c');
INSERT INTO public.journal(persistence_id, sequence_number, deleted, tags, message)
values( 'p-1', 4, false , '{1,2,3,4}', '\x0a0708141203612d3110011a03702d316a2461313164393136332d633365322d343136322d386630362d39623233396663386635383070a8ccefd2dd5c');
SELECT pg_column_size(j), j.tags, j.sequence_number as number_of_tags from journal j;

-- docker=# SELECT pg_column_size(j), j.tags, j.sequence_number as number_of_tags from journal j;
--  pg_column_size |   tags    | number_of_tags
-- ----------------+-----------+----------------
--             107 |           |             -1
--             120 | {}        |              0
--             132 | {1}       |              1
--             136 | {1,2}     |              2
--             140 | {1,2,3}   |              3
--             144 | {1,2,3,4} |              4
