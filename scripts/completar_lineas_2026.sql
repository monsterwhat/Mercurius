-- Completa la metadata de las lineas de venta 2026 (enero-julio) desde los DBF
-- del sistema viejo: codigo de barras, CAByS, base/impuesto y precio neto.
--
-- El CSV lo genera scripts/conciliar_lineas_2026.py y trae comprobante_id, asi
-- que no hay ambiguedad de numeros repetidos entre 2025 y 2026.
--
-- El DBF trae lineas repetidas con el mismo NNUMELINEA (productos identicos);
-- se agrupan por (comprobante_id, linea) igual que quedo la tabla.
--
-- 'total' del DBF es BRUTO (incluye IVA); base = total - imp, como en las
-- lineas que vienen de XML.
--
-- Uso: psql -v aplicar=false|true -f completar_lineas_2026.sql

\pset pager off
\set ON_ERROR_STOP on

DROP TABLE IF EXISTS stg_lin2026;
CREATE TEMP TABLE stg_lin2026 (
    comprobante_id bigint, linea text, fecha text, codigo text, cabys text, cantidad text,
    total text, imp text, descuento text, exento text, porceimp text, cajero text, usuario text
);
\copy stg_lin2026 FROM '/tmp/dbf_reconciliacion/lineas_2026.csv' WITH (FORMAT csv, HEADER true)

DROP TABLE IF EXISTS lin_target;
CREATE TEMP TABLE lin_target AS
SELECT s.comprobante_id,
       nullif(s.linea, '')::int AS linea,
       max(nullif(s.codigo, '')) AS codigo,
       max(nullif(s.cabys, '')) AS cabys,
       sum(coalesce(nullif(s.cantidad, '')::numeric, 0)) AS cantidad,
       sum(coalesce(nullif(s.total, '')::numeric, 0)) AS total,
       sum(coalesce(nullif(s.imp, '')::numeric, 0)) AS imp,
       count(*) AS filas_origen
FROM stg_lin2026 s
WHERE s.comprobante_id IS NOT NULL
GROUP BY 1, 2;

DROP TABLE IF EXISTS lin_match;
CREATE TEMP TABLE lin_match AS
SELECT t.*, l.id AS linea_id
FROM lin_target t
JOIN comprobantesemitidos c ON c.id = t.comprobante_id
JOIN linea_detalle l ON l.detalle_servicio_id = c.detalle_servicio_id
                    AND l.numero_linea = t.linea;

SELECT 'filas_csv' AS metrica, count(*)::text AS valor FROM stg_lin2026
UNION ALL SELECT 'grupos_origen', count(*)::text FROM lin_target
UNION ALL SELECT 'lineas_en_base', count(*)::text FROM lin_match
UNION ALL SELECT 'sin_match', (count(*) - (SELECT count(*) FROM lin_match))::text FROM lin_target
UNION ALL SELECT 'con_codigo', count(*)::text FROM lin_match WHERE codigo IS NOT NULL
UNION ALL SELECT 'total_bruto', round(sum(total), 2)::text FROM lin_match
UNION ALL SELECT 'iva', round(sum(imp), 2)::text FROM lin_match
UNION ALL SELECT 'base_neta', round(sum(total - imp), 2)::text FROM lin_match;

BEGIN;

\echo '===== metadata de linea'
UPDATE linea_detalle l
SET sub_total = round(t.total - t.imp, 5),
    base_imponible = round(t.total - t.imp, 5),
    monto_total = round(t.total - t.imp, 5),
    monto_total_de_linea = round(t.total, 5),
    impuesto_neto = round(t.imp, 5),
    codigocabys = coalesce(nullif(l.codigocabys, ''), t.cabys),
    precio_unitario = CASE WHEN t.cantidad > 0 THEN round((t.total - t.imp) / t.cantidad, 5)
                           ELSE l.precio_unitario END
FROM lin_match t
WHERE l.id = t.linea_id;

\echo '===== codigos de barras'
INSERT INTO codigo_comercial (codigo, tipo, linea_detalle_id)
SELECT t.codigo, '01', t.linea_id
FROM lin_match t
WHERE t.codigo IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM codigo_comercial cc
                  WHERE cc.linea_detalle_id = t.linea_id AND cc.tipo = '01');

\echo '===== verificacion'
SELECT count(*) AS lineas_con_codigo
FROM linea_detalle l
JOIN comprobantesemitidos c ON c.detalle_servicio_id = l.detalle_servicio_id
JOIN encabezado h ON h.id = c.encabezado_id
WHERE h.fecha_emision >= '2026-01-01' AND h.fecha_emision < '2026-08-01'
  AND EXISTS (SELECT 1 FROM codigo_comercial cc WHERE cc.linea_detalle_id = l.id AND cc.tipo = '01');

SELECT count(*) AS lineas_resuelven_articulo
FROM linea_detalle l
JOIN comprobantesemitidos c ON c.detalle_servicio_id = l.detalle_servicio_id
JOIN encabezado h ON h.id = c.encabezado_id
WHERE h.fecha_emision >= '2026-01-01' AND h.fecha_emision < '2026-08-01'
  AND EXISTS (SELECT 1 FROM codigo_comercial cc JOIN articulos a ON a.codigobarra = cc.codigo
              WHERE cc.linea_detalle_id = l.id);

\if :aplicar
COMMIT;
\echo 'APLICADO';
\else
ROLLBACK;
\echo 'DRY-RUN (rollback)';
\endif
