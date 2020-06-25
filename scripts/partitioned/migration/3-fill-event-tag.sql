CREATE OR REPLACE PROCEDURE fill_event_tag(IN separator TEXT) AS
$$
DECLARE
    row record;
    tag TEXT;
BEGIN
    FOR row IN SELECT DISTINCT tags FROM public.journal
        LOOP
            FOR tag in (SELECT single_tag FROM regexp_split_to_table(row.tags, separator) AS single_tag)
                LOOP
                    INSERT INTO public.event_tag(name) VALUES (tag) ON CONFLICT DO NOTHING;
                END LOOP;
        END LOOP;
END ;
$$ LANGUAGE plpgsql;
