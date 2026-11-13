-- 2026-11-13
-- Tipo de cambio por moneda (USD/EUR). Las filas históricas eran todas USD.
ALTER TABLE tipocambio
    ADD COLUMN IF NOT EXISTS moneda VARCHAR(3) DEFAULT 'USD';

UPDATE tipocambio
SET moneda = 'USD'
WHERE moneda IS NULL;

CREATE INDEX IF NOT EXISTS ix_tipocambio_moneda_fecha
    ON tipocambio (moneda, fecha);
