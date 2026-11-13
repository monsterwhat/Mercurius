-- 2026-11-13
-- esServicio por artículo: Hacienda v4.4 separa TotalServ* y TotalMercancias*
-- en el resumen. Antes el resumen declaraba todo como mercancía.
ALTER TABLE articulos
    ADD COLUMN IF NOT EXISTS es_servicio BOOLEAN NOT NULL DEFAULT FALSE;

-- Copia interna en la línea para que el DTO de Fides y los reportes sepan
-- qué bucket usar; no viaja en el XML de la línea.
ALTER TABLE linea_detalle
    ADD COLUMN IF NOT EXISTS es_servicio BOOLEAN;
