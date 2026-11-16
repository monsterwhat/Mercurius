-- Genera los movimientos de inventario que faltan para las VENTAS importadas.
--
-- Las facturas importadas traen sus lineas, pero el import de XML no creaba
-- ni el CodigoComercial ni el movimiento de salida. Este script:
--   1. completa el CodigoComercial que falte en lineas ya importadas,
--   2. inserta un movimiento 'Venta' (cantidad negativa) por linea, usando la
--      fecha de emision y el articulo resuelto por codigo de barras,
--   3. es idempotente: solo inserta si no existe un movimiento 'Venta' con la
--      misma (articulo, fecha, cantidad).
--
-- Uso: psql -v aplicar=false (dry-run) | -v aplicar=true (commit)
\pset pager off
\set ON_ERROR_STOP on

\echo '===== staging de lineas'
DROP TABLE IF EXISTS stg_mov_lin;
CREATE TEMP TABLE stg_mov_lin (clave text, numero text, detalle text, cantidad text, unidad text,
    precio_unitario text, monto_total text, sub_total text, base_imponible text,
    impuesto_neto text, monto_total_linea text, codigocabys text, codigocomercial text);
\copy stg_mov_lin FROM '/tmp/dbf_reconciliacion/xml_lin.csv' WITH (FORMAT csv, HEADER true)

\echo '===== codigos comerciales faltantes'
DROP TABLE IF EXISTS cod_faltantes;
CREATE TEMP TABLE cod_faltantes AS
SELECT ld.id AS linea_id, NULLIF(s.codigocomercial,'') AS codigo
FROM stg_mov_lin s
JOIN comprobantesemitidos c ON c.haciendaclave = s.clave
JOIN linea_detalle ld ON ld.detalle_servicio_id = c.detalle_servicio_id
     AND ld.numero_linea = coalesce(NULLIF(s.numero,'')::int, 1)
WHERE NULLIF(s.codigocomercial,'') IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM codigo_comercial cc
                  WHERE cc.linea_detalle_id = ld.id AND cc.codigo = s.codigocomercial);

\echo '===== movimientos de venta faltantes'
DROP TABLE IF EXISTS mov_venta;
CREATE TEMP TABLE mov_venta AS
SELECT DISTINCT ON (a.codigo, h.fecha_emision, l.cantidad)
       a.codigo AS art, h.fecha_emision AS f, l.cantidad AS cant
FROM stg_mov_lin s
JOIN comprobantesemitidos c ON c.haciendaclave = s.clave
JOIN encabezado h ON h.id = c.encabezado_id
JOIN linea_detalle l ON l.detalle_servicio_id = c.detalle_servicio_id
     AND l.numero_linea = coalesce(NULLIF(s.numero,'')::int, 1)
JOIN articulos a ON a.codigobarra = NULLIF(s.codigocomercial,'')
WHERE c.usuario = 'MIGRACION'
  AND l.cantidad > 0
  -- Solo el hueco posterior a la ultima cobertura del KARDEX (MOVIMIENTO):
  -- antes de esa fecha el stock ya lo movio el import historico y generarlo
  -- otra vez lo contaria doble.
  AND h.fecha_emision >= (
      SELECT coalesce(max(i2.fechamovimiento)::date + 1, DATE '1900-01-01')
      FROM inventario i2 WHERE i2.tipomovimiento = 'MOVIMIENTO'
  )
  AND NOT EXISTS (
      SELECT 1 FROM inventario i
      WHERE i.tipomovimiento = 'Venta'
        AND i.articulo_codigo = a.codigo
        AND i.fechamovimiento = h.fecha_emision
        AND i.cantidad = -l.cantidad
  );

SELECT 'lineas_staging' AS metrica, count(*) AS filas FROM stg_mov_lin
UNION ALL SELECT 'codigos_faltantes', count(*) FROM cod_faltantes
UNION ALL SELECT 'movimientos_nuevos', count(*) FROM mov_venta;

CREATE TEMP SEQUENCE s_mov;
SELECT setval('s_mov', coalesce((SELECT max(codigo) FROM inventario), 0));

BEGIN;

\echo '===== backfill codigos comerciales'
INSERT INTO codigo_comercial (linea_detalle_id, tipo, codigo)
SELECT linea_id, '01', codigo FROM cod_faltantes;

\echo '===== movimientos'
INSERT INTO inventario (codigo, cantidad, fechamovimiento, tipomovimiento, articulo_codigo,
                        notas, status, processed, usuario_id)
SELECT nextval('s_mov'), -m.cant, m.f, 'Venta', m.art, 'Articulo Vendido', true, true,
       (SELECT u.id FROM users u WHERE u.username = 'MIGRACION' LIMIT 1)
FROM mov_venta m;

\echo '===== totales despues'
SELECT 'inventario' AS tabla, count(*) AS filas FROM inventario
UNION ALL SELECT 'codigo_comercial', count(*) FROM codigo_comercial;

\if :aplicar
-- La secuencia real no se toca en dry-run; al aplicar queda por delante del max.
SELECT setval('inventario_codigo_seq', (SELECT coalesce(max(codigo), 1) FROM inventario));
COMMIT;
\echo 'APLICADO';
\else
ROLLBACK;
\echo 'DRY-RUN (rollback)';
\endif
