-- replace schema value if required
CREATE OR REPLACE FUNCTION public.check_persistence_id_max_sequence_number() RETURNS TRIGGER AS
$$
DECLARE
  -- replace with appropriate values
  jpi_max_sequence_number_column CONSTANT TEXT := 'max_sequence_number';

  -- variables
  sql TEXT;
BEGIN
  sql := 'IF NEW.' || jpi_max_sequence_number_column || ' <= OLD.' || jpi_max_sequence_number_column || ' THEN
            RAISE EXCEPTION ''New max_sequence_number not higher than previous value'';
          END IF;';

  EXECUTE sql;

  RETURN NEW;
END;
$$ LANGUAGE plpgsql;
