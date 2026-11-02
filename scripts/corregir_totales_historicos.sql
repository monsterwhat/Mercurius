-- Corrige los totales historicos que quedaron inconsistentes en la migracion vieja.
--
--   1) 2013-2015: `total_comprobante` no es el monto cobrado (la migracion guardo
--      otro valor). El monto real es `total_venta_neta`, que si coincide con el
--      archivo de facturas de vSuper.
--   2) 2018-2025: `total_venta_neta` quedo en 0; se iguala a `total_comprobante`
--      (el monto cobrado) para que el detalle de la factura muestre el subtotal.
--
-- Solo toca comprobantes EMITIDOS. Uso: psql -v aplicar=false|true
\pset pager off
\set ON_ERROR_STOP on

\echo '===== antes'
SELECT to_char(e.fecha_emision, 'YYYY') AS anio, count(*) AS filas,
       round(sum(r.total_comprobante)::numeric, 0) AS comprobante,
       round(sum(r.total_venta_neta)::numeric, 0) AS neta
FROM encabezado e
JOIN comprobantesemitidos c ON c.encabezado_id = e.id
JOIN resumen_factura r ON r.id = c.resumen_id
WHERE e.fecha_emision >= '2013-01-01' AND e.fecha_emision < '2026-01-01'
GROUP BY 1 ORDER BY 1;

\echo '===== filas a corregir'
SELECT to_char(e.fecha_emision, 'YYYY') AS anio, 'comprobante=neta' AS accion, count(*) AS filas
FROM encabezado e
JOIN comprobantesemitidos c ON c.encabezado_id = e.id
JOIN resumen_factura r ON r.id = c.resumen_id
WHERE e.fecha_emision < '2016-01-01'
  AND r.total_venta_neta > 0
  AND coalesce(r.total_comprobante, 0) <> r.total_venta_neta
GROUP BY 1
UNION ALL
SELECT to_char(e.fecha_emision, 'YYYY'), 'neta=comprobante', count(*)
FROM encabezado e
JOIN comprobantesemitidos c ON c.encabezado_id = e.id
JOIN resumen_factura r ON r.id = c.resumen_id
WHERE e.fecha_emision >= '2018-01-01' AND e.fecha_emision < '2026-01-01'
  AND coalesce(r.total_venta_neta, 0) = 0
  AND coalesce(r.total_comprobante, 0) > 0
GROUP BY 1
ORDER BY 1, 2;

BEGIN;

UPDATE resumen_factura r
SET total_comprobante = r.total_venta_neta
FROM comprobantesemitidos c
JOIN encabezado e ON e.id = c.encabezado_id
WHERE r.id = c.resumen_id
  AND e.fecha_emision < '2016-01-01'
  AND r.total_venta_neta > 0
  AND coalesce(r.total_comprobante, 0) <> r.total_venta_neta;

UPDATE resumen_factura r
SET total_venta_neta = r.total_comprobante
FROM comprobantesemitidos c
JOIN encabezado e ON e.id = c.encabezado_id
WHERE r.id = c.resumen_id
  AND e.fecha_emision < '2016-01-01'
  AND coalesce(r.total_venta_neta, 0) = 0
  AND coalesce(r.total_comprobante, 0) > 0;

UPDATE resumen_factura r
SET total_venta_neta = r.total_comprobante
FROM comprobantesemitidos c
JOIN encabezado e ON e.id = c.encabezado_id
WHERE r.id = c.resumen_id
  AND e.fecha_emision >= '2018-01-01' AND e.fecha_emision < '2026-01-01'
  AND coalesce(r.total_venta_neta, 0) = 0
  AND coalesce(r.total_comprobante, 0) > 0;

\echo '===== despues'
SELECT to_char(e.fecha_emision, 'YYYY') AS anio, count(*) AS filas,
       round(sum(r.total_comprobante)::numeric, 0) AS comprobante,
       round(sum(r.total_venta_neta)::numeric, 0) AS neta
FROM encabezado e
JOIN comprobantesemitidos c ON c.encabezado_id = e.id
JOIN resumen_factura r ON r.id = c.resumen_id
WHERE e.fecha_emision >= '2013-01-01' AND e.fecha_emision < '2026-01-01'
GROUP BY 1 ORDER BY 1;

\if :aplicar
COMMIT;
\echo 'APLICADO';
\else
ROLLBACK;
\echo 'DRY-RUN (rollback)';
\endif
