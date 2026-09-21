-- Migration: Change TipoCambio columns from DOUBLE to DECIMAL(18,5)
-- Reversible: Yes (see rollback section)

-- Forward migration
ALTER TABLE tipo_cambio ALTER COLUMN valor_compra TYPE DECIMAL(18,5);
ALTER TABLE tipo_cambio ALTER COLUMN valor_venta TYPE DECIMAL(18,5);

-- Backfill existing values (preserve precision)
UPDATE tipo_cambio SET valor_compra = CAST(valor_compra AS DECIMAL(18,5)) WHERE valor_compra IS NOT NULL;
UPDATE tipo_cambio SET valor_venta = CAST(valor_venta AS DECIMAL(18,5)) WHERE valor_venta IS NOT NULL;

-- Rollback section (commented out)
-- ALTER TABLE tipo_cambio ALTER COLUMN valor_compra TYPE DOUBLE;
-- ALTER TABLE tipo_cambio ALTER COLUMN valor_venta TYPE DOUBLE;