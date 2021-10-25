-- Depending on your use case consider partitioning this table.
DO $$
DECLARE
  -- replace with appropriate values
  schema CONSTANT TEXT := 'public';
  jpi_table_name CONSTANT TEXT := 'journal_persistence_ids';
  jpi_persistence_id_column CONSTANT TEXT := 'persistence_id';
  jpi_max_sequence_number_column CONSTANT TEXT := 'max_sequence_number';
  jpi_max_ordering_column CONSTANT TEXT := 'max_ordering';
  jpi_min_ordering_column CONSTANT TEXT := 'min_ordering';

  -- variables
  jpi_table TEXT;
  sql TEXT;
BEGIN
  jpi_table := schema || '.' || jpi_table_name;

  sql := 'CREATE TABLE IF NOT EXISTS ' || jpi_table ||
       '(' ||
          jpi_persistence_id_column || ' TEXT NOT NULL, ' ||
          jpi_max_sequence_number_column || ' BIGINT NOT NULL, ' ||
          jpi_max_ordering_column || ' BIGINT NOT NULL, ' ||
          jpi_min_ordering_column || ' BIGINT NOT NULL, ' ||
          'PRIMARY KEY (' || jpi_persistence_id_column || ')' ||
        ')';

  EXECUTE sql;
END;
$$ LANGUAGE plpgsql;
