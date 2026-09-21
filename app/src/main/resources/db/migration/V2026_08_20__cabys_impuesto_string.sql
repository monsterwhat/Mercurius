-- Migration: Change Cabys.impuesto from INT to VARCHAR(2)
-- Reversible: Yes (see rollback section)

-- Forward migration
ALTER TABLE cabys ALTER COLUMN impuesto TYPE VARCHAR(2);

-- Backfill existing values (convert int to zero-padded string)
UPDATE cabys SET impuesto = LPAD(CAST(impuesto AS VARCHAR), 2, '0') WHERE impuesto IS NOT NULL;

-- Rollback section (commented out)
-- ALTER TABLE cabys ALTER COLUMN impuesto TYPE INT;
-- UPDATE cabys SET impuesto = CAST(impuesto AS INT) WHERE impuesto IS NOT NULL;