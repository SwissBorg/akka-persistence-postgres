DO $$
DECLARE
  -- replace with appropriate values
  schema CONSTANT TEXT := 'public';
  jpi_table_name CONSTANT TEXT := 'journal_persistence_ids';

  -- variables
  jpi_table TEXT;
  sql TEXT;
BEGIN
  jpi_table := schema || '.' || jpi_table_name;
  sql := 'CREATE TRIGGER trig_check_persistence_id_max_sequence_number
          BEFORE UPDATE ON ' || jpi_table || ' FOR EACH ROW
          EXECUTE PROCEDURE ' || schema || '.check_persistence_id_max_sequence_number()';

  EXECUTE sql;
END;
$$ LANGUAGE plpgsql;
