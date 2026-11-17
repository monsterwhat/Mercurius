-- Corrige total_impuesto y total_venta_neta de los comprobantes emitidos usando
-- el IVA por linea del sistema viejo (detafact*.IMP), sin doble conteo entre
-- archivos solapados.
--
-- El CSV lo genera scripts/conciliar_lineas.py a partir de un export de la base:
--   comprobante_id,iva,neta
--
-- Uso: copiar el CSV como /tmp/dbf_reconciliacion/iva_fix.csv y correr
--   psql -d mercurius -v aplicar=false -f corregir_iva.sql
--   psql -d mercurius -v aplicar=true  -f corregir_iva.sql
--
-- Deja respaldo en resumen_factura_bkp_iva_2026 (solo la primera vez que toca
-- cada resumen), asi que se puede revertir con un UPDATE ... FROM.

\pset pager off
\set ON_ERROR_STOP on

DROP TABLE IF EXISTS stg_iva_fix;
CREATE TEMP TABLE stg_iva_fix (comprobante_id bigint, iva numeric, neta numeric);
\copy stg_iva_fix FROM '/tmp/dbf_reconciliacion/iva_fix.csv' WITH (FORMAT csv, HEADER true)

DROP TABLE IF EXISTS iva_target;
CREATE TEMP TABLE iva_target AS
SELECT r.id AS resumen_id,
       c.id AS comprobante_id,
       coalesce(r.total_impuesto, 0) AS iva_antes,
       coalesce(r.total_venta_neta, 0) AS neta_antes,
       s.iva AS iva_nuevo,
       s.neta AS neta_nuevo
FROM stg_iva_fix s
JOIN comprobantesemitidos c ON c.id = s.comprobante_id
JOIN resumen_factura r ON r.id = c.resumen_id;

SELECT 'filas_csv' AS metrica, count(*)::text AS valor FROM stg_iva_fix
UNION ALL SELECT 'resumenes_en_base', count(*)::text FROM iva_target
UNION ALL SELECT 'iva_antes', round(sum(iva_antes), 2)::text FROM iva_target
UNION ALL SELECT 'iva_despues', round(sum(iva_nuevo), 2)::text FROM iva_target
UNION ALL SELECT 'neta_antes', round(sum(neta_antes), 2)::text FROM iva_target
UNION ALL SELECT 'neta_despues', round(sum(neta_nuevo), 2)::text FROM iva_target
UNION ALL SELECT 'a_cambiar', count(*)::text FROM iva_target
     WHERE iva_antes <> iva_nuevo OR neta_antes <> neta_nuevo;

\echo '--- detalle por mes (antes -> despues) ---'
SELECT to_char(h.fecha_emision, 'YYYY-MM') AS mes,
       count(*) AS resumenes,
       round(sum(t.iva_antes)::numeric, 2) AS iva_antes,
       round(sum(t.iva_nuevo)::numeric, 2) AS iva_despues,
       round(sum(t.iva_nuevo - t.iva_antes)::numeric, 2) AS diferencia
FROM iva_target t
JOIN comprobantesemitidos c ON c.id = t.comprobante_id
JOIN encabezado h ON h.id = c.encabezado_id
GROUP BY 1 ORDER BY 1;

BEGIN;

CREATE TABLE IF NOT EXISTS resumen_factura_bkp_iva_2026 (LIKE resumen_factura INCLUDING ALL);

INSERT INTO resumen_factura_bkp_iva_2026
SELECT r.*
FROM resumen_factura r
JOIN iva_target t ON t.resumen_id = r.id
WHERE NOT EXISTS (SELECT 1 FROM resumen_factura_bkp_iva_2026 b WHERE b.id = r.id);

UPDATE resumen_factura r
SET total_impuesto = t.iva_nuevo,
    total_venta_neta = t.neta_nuevo
FROM iva_target t
WHERE r.id = t.resumen_id
  AND (r.total_impuesto IS DISTINCT FROM t.iva_nuevo
       OR r.total_venta_neta IS DISTINCT FROM t.neta_nuevo);

\if :aplicar
COMMIT;
\echo 'APLICADO';
\else
ROLLBACK;
\echo 'DRY-RUN (rollback)';
\endif
