DROP TABLE IF EXISTS public.journal;
DROP TABLE IF EXISTS public.tag_definition;

CREATE TABLE IF NOT EXISTS public.journal
(
    ordering        BIGSERIAL,
    persistence_id  VARCHAR(255)               NOT NULL,
    sequence_number BIGINT                     NOT NULL,
    deleted         BOOLEAN      DEFAULT FALSE NOT NULL,
    tags            VARCHAR(255) DEFAULT NULL,
    message         BYTEA                      NOT NULL,
    PRIMARY KEY (persistence_id, sequence_number)
);

CREATE UNIQUE INDEX journal_ordering_idx ON public.journal (ordering);


CREATE TABLE IF NOT EXISTS public.tag_definition
(
    orders INT,
    tag    VARCHAR(255) DEFAULT NULL,
    PRIMARY KEY (orders)
);
-- tagSeparator = ","
INSERT INTO public.tag_definition(orders, tag)
VALUES (0, ''),
       (1, 'firstEvent'),
       (2, 'longtag'),
       (3, 'multiT1,multiT2'),
       (4, 'firstUnique'),
       (5, 'tag'),
       (6, 'expected'),
       (7, 'multi,companion'),
       (8, 'companion,multiT1'),
       (9, 'xxx'),
       (10, 'ended'),
       (11, 'expected');

INSERT INTO public.journal(persistence_id, sequence_number, deleted, tags, message)
select 'p-1', i, false, tag, '0x22'
from generate_series(1, 1000000) s(i)
         JOIN public.tag_definition on orders = mod(i, 12);

INSERT INTO public.journal(persistence_id, sequence_number, deleted, tags, message)
select 'p-2', i, false, tag, '0x22'
from generate_series(1, 100000) s(i)
         JOIN public.tag_definition on orders =  mod(i, 12);

INSERT INTO public.journal(persistence_id, sequence_number, deleted, tags, message)
select 'p-3', i, false, tag, '0x22'
from generate_series(1, 100000) s(i)
         JOIN public.tag_definition on orders =  mod(i, 12);

INSERT INTO public.journal(persistence_id, sequence_number, deleted, tags, message)
select 'p-4', i, false, tag, '0x22'
from generate_series(1, 100000) s(i)
         JOIN public.tag_definition on orders =  mod(i, 12);

INSERT INTO public.journal(persistence_id, sequence_number, deleted, tags, message)
select 'p-5', i, false, tag, '0x22'
from generate_series(1, 100000) s(i)
         JOIN public.tag_definition on orders =  mod(i, 12);
