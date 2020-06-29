CREATE OR REPLACE PROCEDURE copy_piece_of_data(IN from_ordering BIGINT, IN to_ordering BIGINT, IN tag_separator TEXT) AS
$$
DECLARE
    row   record;
    tagss INT[];
BEGIN
    FOR row IN
        SELECT ordering, persistence_id, sequence_number, deleted, tags, message
        FROM public.journal
        WHERE ordering >= from_ordering
          AND ordering <= to_ordering
        ORDER BY ordering
        LOOP
            SELECT array_agg(id)
            INTO tagss
            FROM regexp_split_to_table(row.tags, tag_separator) AS single_tag
                     JOIN public.event_tag ON name = single_tag;

            INSERT INTO public.journal_partitioned(ordering, persistence_id, sequence_number, deleted, tags, message)
            VALUES (row.ordering, row.persistence_id, row.sequence_number, row.deleted, tagss, row.message)
            ON CONFLICT DO NOTHING;

        END LOOP;
    COMMIT;
END ;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE PROCEDURE copy_data(IN from_ordering BIGINT, IN batch_size BIGINT, IN tag_separator TEXT) AS
$$
DECLARE
    batch_number  BIGINT;
    max_ordering  BIGINT;
BEGIN
    SELECT max(ordering) INTO max_ordering FROM public.journal;
    FOR batch_number IN 0..((max_ordering - from_ordering) / batch_size)
        LOOP
            CALL copy_piece_of_data(from_ordering + batch_number * batch_size,
                                    from_ordering + batch_size + batch_number * batch_size - 1, tag_separator);
        END LOOP;
END ;
$$ LANGUAGE plpgsql;
