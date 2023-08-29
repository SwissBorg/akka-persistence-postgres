-- Creates table and the amount of partitions defined by jm_partitions_number. Default is 10.
DO $$
DECLARE
  -- replace with appropriate values
  schema CONSTANT TEXT := 'public';
  jm_table_name CONSTANT TEXT := 'journal_metadata';
  jm_id_column CONSTANT TEXT := 'id';
  jm_persistence_id_column CONSTANT TEXT := 'persistence_id';
  jm_max_sequence_number_column CONSTANT TEXT := 'max_sequence_number';
  jm_max_ordering_column CONSTANT TEXT := 'max_ordering';
  jm_min_ordering_column CONSTANT TEXT := 'min_ordering';
  jm_partitions_table_name_prefix CONSTANT TEXT := 'journal_metadata_';
  jm_partitions_number CONSTANT INTEGER := 10;

  -- variables
  jm_table TEXT;
  jm_partition_table TEXT;
  sql TEXT;
BEGIN
  jm_table := schema || '.' || jm_table_name;
  jm_partition_table := schema || '.' || jm_partitions_table_name_prefix;

  sql := 'CREATE TABLE IF NOT EXISTS ' || jm_table ||
       '(' ||
          jm_id_column || ' BIGINT GENERATED ALWAYS AS IDENTITY, ' ||
          jm_max_sequence_number_column || ' BIGINT NOT NULL, ' ||
          jm_max_ordering_column || ' BIGINT NOT NULL, ' ||
          jm_min_ordering_column || ' BIGINT NOT NULL, ' ||
          jm_persistence_id_column || ' TEXT NOT NULL, ' ||
          'PRIMARY KEY (' || jm_persistence_id_column || ')' ||
        ') PARTITION BY HASH(' || jm_persistence_id_column || ')';

  EXECUTE sql;

  FOR i IN 0..(jm_partitions_number - 1) LOOP
    EXECUTE 'CREATE TABLE IF NOT EXISTS ' || jm_partition_table || i ||
            ' PARTITION OF ' || jm_table ||
            ' FOR VALUES WITH (MODULUS ' || jm_partitions_number || ', REMAINDER ' || i || ')';
  END LOOP;
END;
$$ LANGUAGE plpgsql;
