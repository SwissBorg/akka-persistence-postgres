DO $$
DECLARE
  -- replace with appropriate values
  schema CONSTANT TEXT := 'public';
  j_table_name CONSTANT TEXT := 'journal';

  -- variables
  j_table TEXT;
  sql TEXT;
BEGIN
  j_table := schema || '.' || j_table_name;

  sql := 'CREATE TRIGGER trig_update_journal_persistence_id
    AFTER INSERT ON ' || j_table || ' FOR EACH ROW
    EXECUTE PROCEDURE ' || schema || '.update_journal_persistence_ids()';

  EXECUTE sql;
END;
$$ LANGUAGE plpgsql;
