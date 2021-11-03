-- Creates table and the amount of partitions defined by jpi_partitions_number. Default is 10.
DO $$
DECLARE
  -- replace with appropriate values
  schema CONSTANT TEXT := 'public';
  jpi_table_name CONSTANT TEXT := 'journal_persistence_ids';
  jpi_id_column CONSTANT TEXT := 'id';
  jpi_persistence_id_column CONSTANT TEXT := 'persistence_id';
  jpi_max_sequence_number_column CONSTANT TEXT := 'max_sequence_number';
  jpi_max_ordering_column CONSTANT TEXT := 'max_ordering';
  jpi_min_ordering_column CONSTANT TEXT := 'min_ordering';
  jpi_partitions_table_name_perfix CONSTANT TEXT := 'journal_persistence_ids_';
  jpi_partitions_number CONSTANT INTEGER := 10;

  -- variables
  jpi_table TEXT;
  sql TEXT;
BEGIN
  jpi_table := schema || '.' || jpi_table_name;

  sql := 'CREATE TABLE IF NOT EXISTS ' || jpi_table ||
       '(' ||
          jpi_id_column || ' BIGSERIAL, ' ||
          jpi_persistence_id_column || ' TEXT NOT NULL, ' ||
          jpi_max_sequence_number_column || ' BIGINT NOT NULL, ' ||
          jpi_max_ordering_column || ' BIGINT NOT NULL, ' ||
          jpi_min_ordering_column || ' BIGINT NOT NULL, ' ||
          'PRIMARY KEY (' || jpi_id_column || ', ' || jpi_persistence_id_column || ')' ||
        ') PARTITION BY HASH(' || jpi_id_column || ')';

  EXECUTE sql;

  FOR i IN 0..(jpi_partitions_number - 1) LOOP
    EXECUTE 'CREATE TABLE IF NOT EXISTS ' || jpi_partitions_table_name_perfix || i ||
            ' PARTITION OF ' || jpi_table ||
            ' FOR VALUES WITH (MODULUS 10, REMAINDER ' || i || ')';
  END LOOP;
END;
$$ LANGUAGE plpgsql;
