-- Completa el IVA de los resumenes que quedaron en cero cuando las lineas SI
-- tienen impuesto. Pasa con comprobantes importados de XML (sep-oct 2026) y con
-- viejos importes historicos: el resumen se creo antes de que las lineas
-- tuvieran su impuesto_neto.
--
-- Solo toca resumenes con total_impuesto = 0 y lineas con IVA > 0: nunca
-- sobreescribe un valor existente.
--
-- Uso: psql -v aplicar=false|true -f corregir_iva_desde_lineas.sql

\pset pager off
\set ON_ERROR_STOP on

DROP TABLE IF EXISTS iva_lineas;
CREATE TEMP TABLE iva_lineas AS
SELECT c.id AS comprobante_id,
       r.id AS resumen_id,
       round(sum(coalesce(l.impuesto_neto, 0)), 5) AS iva,
       round(coalesce(r.total_comprobante, 0), 5) AS total
FROM linea_detalle l
JOIN comprobantesemitidos c ON c.detalle_servicio_id = l.detalle_servicio_id
JOIN resumen_factura r ON r.id = c.resumen_id
WHERE coalesce(r.total_impuesto, 0) = 0
GROUP BY c.id, r.id, r.total_comprobante
HAVING sum(coalesce(l.impuesto_neto, 0)) > 0;

SELECT to_char(h.fecha_emision, 'YYYY-MM') AS mes,
       count(*) AS resumenes,
       round(sum(t.iva)::numeric, 2) AS iva_a_completar
FROM iva_lineas t
JOIN comprobantesemitidos c ON c.id = t.comprobante_id
JOIN encabezado h ON h.id = c.encabezado_id
GROUP BY 1 ORDER BY 1;

BEGIN;

CREATE TABLE IF NOT EXISTS resumen_factura_bkp_iva_2026 (LIKE resumen_factura INCLUDING ALL);

INSERT INTO resumen_factura_bkp_iva_2026
SELECT r.*
FROM resumen_factura r
JOIN iva_lineas t ON t.resumen_id = r.id
WHERE NOT EXISTS (SELECT 1 FROM resumen_factura_bkp_iva_2026 b WHERE b.id = r.id);

UPDATE resumen_factura r
SET total_impuesto = t.iva,
    total_venta_neta = greatest(t.total - t.iva, 0)
FROM iva_lineas t
WHERE r.id = t.resumen_id;

\echo '===== despues'
SELECT to_char(h.fecha_emision, 'YYYY-MM') AS mes,
       round(sum(r.total_impuesto)::numeric, 0) AS iva
FROM comprobantesemitidos c
JOIN encabezado h ON h.id = c.encabezado_id
JOIN resumen_factura r ON r.id = c.resumen_id
WHERE h.fecha_emision >= '2026-09-01'
GROUP BY 1 ORDER BY 1;

\if :aplicar
COMMIT;
\echo 'APLICADO';
\else
ROLLBACK;
\echo 'DRY-RUN (rollback)';
\endif
