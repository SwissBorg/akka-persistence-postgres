/*
ATTENTION: This is a simplistic migration, which is not prepared to handle a large number of rows.
If that is your situation, please consider running some kind of batched ad-hoc program that will read the journal,
compute the necessary values and then insert them to the journal metadata table.

When you upgrade to the 0.6.x series, the crucial part is adding the metadata insert trigger, which will take care of all new events,
meaning that it is totally safe to solve the back filling of data in a ad-hoc manner.
*/
DO $$
DECLARE
  -- replace with appropriate values
  schema CONSTANT TEXT := 'public';
  j_table_name CONSTANT TEXT := 'journal';
  j_persistence_id_column CONSTANT TEXT := 'persistence_id';
  j_sequence_number_column CONSTANT TEXT := 'sequence_number';
  j_ordering_column CONSTANT TEXT := 'ordering';
  jpi_table_name CONSTANT TEXT := 'journal_persistence_ids';
  jpi_max_sequence_number_column CONSTANT TEXT := 'max_sequence_number';
  jpi_max_ordering_column CONSTANT TEXT := 'max_ordering';
  jpi_min_ordering_column CONSTANT TEXT := 'min_ordering';

  -- variables
  j_table TEXT;
  jpi_table TEXT;
  sql TEXT;
BEGIN
  j_table := schema || '.' || j_table_name;
  jpi_table := schema || '.' || jpi_table_name;
  sql := 'INSERT INTO ' || jpi_table ||
         ' SELECT ' ||
           j_persistence_id_column || ', ' ||
           'max(' || j_sequence_number_column || '), ' ||
           'max(' || j_ordering_column || '), ' ||
           'min(' || j_ordering_column || ')' ||
         ' FROM ' || j_table || ' GROUP BY ' || j_persistence_id_column;

  EXECUTE sql;
END;
$$ LANGUAGE plpgsql;