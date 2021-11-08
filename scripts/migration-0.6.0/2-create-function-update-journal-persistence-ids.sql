-- replace schema value if required
CREATE OR REPLACE FUNCTION public.update_journal_persistence_ids() RETURNS TRIGGER AS
$$
DECLARE
  -- replace with appropriate values
  schema CONSTANT TEXT := 'public';
  j_table_name CONSTANT TEXT := 'journal';
  j_persistence_id_column CONSTANT TEXT := 'persistence_id';
  j_sequence_number_column CONSTANT TEXT := 'sequence_number';
  j_ordering_column CONSTANT TEXT := 'ordering';
  jpi_table_name CONSTANT TEXT := 'journal_persistence_ids';
  jpi_persistence_id_column CONSTANT TEXT := 'persistence_id';
  jpi_max_sequence_number_column CONSTANT TEXT := 'max_sequence_number';
  jpi_max_ordering_column CONSTANT TEXT := 'max_ordering';
  jpi_min_ordering_column CONSTANT TEXT := 'min_ordering';

  -- variables
  j_table TEXT;
  jpi_table TEXT;
  cols TEXT;
  vals TEXT;
  upds TEXT;
  sql TEXT;
BEGIN
  j_table := schema || '.' || j_table_name;
  jpi_table := schema || '.' || jpi_table_name;
  cols := jpi_persistence_id_column || ', ' || jpi_max_sequence_number_column || ', ' || jpi_max_ordering_column || ', ' || jpi_min_ordering_column;
  vals := '($1).' || j_persistence_id_column || ', ($1).' || j_sequence_number_column || ', ($1).' || j_ordering_column || ',($1).' || j_ordering_column;
  upds := jpi_max_sequence_number_column || ' = ($1).' || j_sequence_number_column || ', ' ||
          jpi_max_ordering_column || ' = ($1).' || j_ordering_column || ', ' ||
          jpi_min_ordering_column || ' = LEAST(' || jpi_table || '.' || jpi_min_ordering_column || ', ($1).' || j_ordering_column || ')';

  sql := 'INSERT INTO ' || jpi_table || ' (' || cols || ') VALUES (' || vals || ') ' ||
         'ON CONFLICT (' || jpi_persistence_id_column || ') DO UPDATE SET ' || upds;

  EXECUTE sql USING NEW;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;
