-- Migration: Global profit margin system
-- - Adds tipo_refrigeracion to articulos (NINGUNA / REFRIGERADO / CONGELADO)
-- - Creates configuracion_margen table (INSERT-only, latest row = current)
-- - Seeds first default config: base=25%, ref=+5%, cong=+10%
-- - Migrates all existing articles to NINGUNA

-- Forward migration

-- 1. Add refrigeration type column to articulos
ALTER TABLE articulos ADD COLUMN tipo_refrigeracion VARCHAR(20) DEFAULT 'NINGUNA';

-- 2. Create margin configuration table
CREATE TABLE configuracion_margen (
    id              SERIAL PRIMARY KEY,
    margen_base    DECIMAL(5,2) NOT NULL,
    ajuste_refrigerado  DECIMAL(5,2) NOT NULL,
    ajuste_congelado    DECIMAL(5,2) NOT NULL,
    fecha_creacion  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    usuario_id     INTEGER REFERENCES users(id)
);

-- 3. Seed default configuration
INSERT INTO configuracion_margen (margen_base, ajuste_refrigerado, ajuste_congelado)
VALUES (25.00, 5.00, 10.00);

-- 4. Migrate existing articles to NINGUNA (null-safe, idempotent)
UPDATE articulos SET tipo_refrigeracion = 'NINGUNA' WHERE tipo_refrigeracion IS NULL;

-- Rollback section (commented out)
-- ALTER TABLE articulos DROP COLUMN tipo_refrigeracion;
-- DROP TABLE configuracion_margen;
