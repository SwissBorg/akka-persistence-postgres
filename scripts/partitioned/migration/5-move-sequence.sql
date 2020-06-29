CREATE OR REPLACE PROCEDURE move_sequence() AS
$$
DECLARE
    max_ordering  BIGINT;
BEGIN
    SELECT max(ordering) INTO max_ordering FROM public.journal_partitioned;
    PERFORM setval('journal_partitioned_ordering_seq', max_ordering, true);
END ;
$$ LANGUAGE plpgsql;
