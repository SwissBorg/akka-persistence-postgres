CREATE OR REPLACE PROCEDURE copy_piece_of_data(IN from_ordering BIGINT, IN to_ordering BIGINT,
                                               IN source_tag_separator TEXT, IN source_schema TEXT, IN source_journal_table_name TEXT,
                                               IN destination_schema TEXT, IN destination_journal_table_name TEXT, IN destination_tag_table TEXT) AS
$$
BEGIN
    EXECUTE '
        INSERT INTO ' || destination_schema || '.' || destination_journal_table_name || '(ordering, persistence_id, sequence_number, deleted, tags, message)
            SELECT jrn.ordering , persistence_id , sequence_number , deleted , (SELECT array_agg(id) FROM regexp_split_to_table(tags, ''' || source_tag_separator || ''') AS single_tag JOIN ' || destination_schema || '.' || destination_tag_table || ' ON name = single_tag) , message
                FROM ' || source_schema || '.' || source_journal_table_name || ' jrn
                WHERE ordering >= ' || from_ordering || ' AND ordering <= ' || to_ordering || '
            ON CONFLICT DO NOTHING;';
    COMMIT;
END ;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE PROCEDURE copy_data(IN from_ordering BIGINT, IN batch_size BIGINT,
                                      IN source_tag_separator TEXT, IN source_schema TEXT, IN source_journal_table_name TEXT,
                                      IN destination_schema TEXT, IN destination_journal_table_name TEXT, IN destination_tag_table TEXT) AS
$$
DECLARE
    batch_number BIGINT;
    max_ordering BIGINT;
BEGIN
    EXECUTE 'SELECT max(ordering) FROM ' || source_schema || '.' || source_journal_table_name || ';' INTO max_ordering;
    FOR batch_number IN 0..((max_ordering - from_ordering) / batch_size)
        LOOP
            CALL copy_piece_of_data(from_ordering + batch_number * batch_size,
                                    from_ordering + batch_size + batch_number * batch_size - 1, source_tag_separator,
                                    source_schema, source_journal_table_name, destination_schema,
                                    destination_journal_table_name, destination_tag_table);
        END LOOP;
END ;
$$ LANGUAGE plpgsql;
