-- replace schema value if required
CREATE OR REPLACE FUNCTION public.update_journal_metadata() RETURNS TRIGGER AS
$$
DECLARE
  -- replace with appropriate values
  schema CONSTANT TEXT := 'public';
  j_table_name CONSTANT TEXT := 'journal';
  j_persistence_id_column CONSTANT TEXT := 'persistence_id';
  j_sequence_number_column CONSTANT TEXT := 'sequence_number';
  j_ordering_column CONSTANT TEXT := 'ordering';
  jm_table_name CONSTANT TEXT := 'journal_metadata';
  jm_persistence_id_column CONSTANT TEXT := 'persistence_id';
  jm_max_sequence_number_column CONSTANT TEXT := 'max_sequence_number';
  jm_max_ordering_column CONSTANT TEXT := 'max_ordering';
  jm_min_ordering_column CONSTANT TEXT := 'min_ordering';
  first_sequence_number_value CONSTANT INTEGER := 1;
  unset_min_ordering_value CONSTANT INTEGER := -1;

  -- variables
  j_table TEXT;
  jm_table TEXT;
  cols TEXT;
  vals TEXT;
  upds TEXT;
  sql TEXT;
BEGIN
  j_table := schema || '.' || j_table_name;
  jm_table := schema || '.' || jm_table_name;
  cols := jm_persistence_id_column || ', ' || jm_max_sequence_number_column || ', ' || jm_max_ordering_column || ', ' || jm_min_ordering_column;
  vals := '($1).' || j_persistence_id_column || ', ($1).' || j_sequence_number_column || ', ($1).' || j_ordering_column ||
          ', CASE WHEN ($1).' || j_sequence_number_column || ' = ' || first_sequence_number_value || ' THEN ($1).' || j_ordering_column || ' ELSE ' || unset_min_ordering_value || ' END';
  upds := jm_max_sequence_number_column || ' = GREATEST(' || jm_table || '.' || jm_max_sequence_number_column || ', ($1).' || j_sequence_number_column || '), ' ||
          jm_max_ordering_column || ' = GREATEST(' || jm_table || '.' || jm_max_ordering_column || ', ($1).' || j_ordering_column || '), ' ||
          jm_min_ordering_column || ' = LEAST(' || jm_table || '.' || jm_min_ordering_column || ', ($1).' || j_ordering_column || ')';

  sql := 'INSERT INTO ' || jm_table || ' (' || cols || ') VALUES (' || vals || ') ' ||
         'ON CONFLICT (' || jm_persistence_id_column || ') DO UPDATE SET ' || upds;

  EXECUTE sql USING NEW;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;
