CREATE OR REPLACE PROCEDURE fill_tags(IN source_tag_separator TEXT, IN source_schema TEXT, IN source_journal_table_name TEXT, IN destination_schema TEXT, IN destination_tag_table TEXT) AS
$$
DECLARE
    row record;
    tag TEXT;
BEGIN
    FOR row IN EXECUTE 'SELECT DISTINCT tags FROM ' || source_schema || '.' || source_journal_table_name
        LOOP
            FOR tag in (SELECT single_tag FROM regexp_split_to_table(row.tags, source_tag_separator) AS single_tag)
                LOOP
                    EXECUTE 'INSERT INTO ' || destination_schema || '.' || destination_tag_table || '(name) VALUES (''' || tag || ''') ON CONFLICT DO NOTHING;';
                END LOOP;
        END LOOP;
END ;
$$ LANGUAGE plpgsql;
