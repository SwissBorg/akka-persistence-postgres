DO $$
DECLARE
  -- replace with appropriate values
  schema CONSTANT TEXT := 'public';
  jm_table_name CONSTANT TEXT := 'journal_metadata';

  -- variables
  jm_table TEXT;
  sql TEXT;
BEGIN
    jm_table := schema || '.' || jm_table_name;

   	sql := 'CREATE TRIGGER trig_check_persistence_id_max_sequence_number
            BEFORE UPDATE ON ' || jm_table || ' FOR EACH ROW
            EXECUTE PROCEDURE ' || schema || '.check_persistence_id_max_sequence_number()';

   	EXECUTE sql;
END ;
$$ LANGUAGE plpgsql;
