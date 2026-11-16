-- Reconstruye los movimientos de inventario 'Venta' de febrero 2026.
--
-- Por que: febrero quedo con 26.410 movimientos cuando las lineas reales son
-- 13.617. Se importaron dos veces (kardex FE y salidas) y solo 824 filas
-- deduplicaron, porque cada importacion genero notas distintas.
--
-- Fuente: lineas_2026.csv (scripts/conciliar_lineas_2026.py), conciliadas
-- contra el total de cada comprobante, con codigo de barras y usuario.
--
-- Deja respaldo en inventario_bkp_venta_feb2026 antes de borrar.
-- No toca articulostock: el stock viene del maestro del sistema viejo y no es
-- la suma del kardex (hay 8.984 articulos con diferencia previa a este cambio).
--
-- Uso: psql -v aplicar=false|true -f reconstruir_ventas_febrero_2026.sql

\pset pager off
\set ON_ERROR_STOP on

DROP TABLE IF EXISTS stg_lin_feb;
CREATE TEMP TABLE stg_lin_feb (
    comprobante_id bigint, linea text, fecha text, codigo text, cabys text, cantidad text,
    total text, imp text, descuento text, exento text, porceimp text, cajero text, usuario text
);
\copy stg_lin_feb FROM '/tmp/dbf_reconciliacion/lineas_2026.csv' WITH (FORMAT csv, HEADER true)

DROP TABLE IF EXISTS mov_feb;
CREATE TEMP TABLE mov_feb AS
WITH lin AS (
    SELECT c.id AS comprobante_id,
           h.fecha_emision::date AS fecha,
           h.numero_consecutivo,
           c.usuario,
           nullif(s.linea, '')::int AS linea,
           max(nullif(s.codigo, '')) AS codigo,
           sum(coalesce(nullif(s.cantidad, '')::numeric, 0)) AS cantidad
    FROM stg_lin_feb s
    JOIN comprobantesemitidos c ON c.id = s.comprobante_id
    JOIN encabezado h ON h.id = c.encabezado_id
    WHERE h.fecha_emision >= '2026-02-01' AND h.fecha_emision < '2026-03-01'
    GROUP BY c.id, h.fecha_emision, h.numero_consecutivo, c.usuario, s.linea
)
SELECT lin.*,
       a.codigo AS articulo,
       (SELECT u.id FROM users u WHERE u.username = lin.usuario LIMIT 1) AS usuario_id
FROM lin
LEFT JOIN articulos a ON a.codigobarra = lin.codigo;

SELECT 'lineas_febrero' AS metrica, count(*)::text AS valor FROM mov_feb
UNION ALL SELECT 'sin_articulo', count(*)::text FROM mov_feb WHERE articulo IS NULL
UNION ALL SELECT 'sin_usuario', count(*)::text FROM mov_feb WHERE usuario_id IS NULL
UNION ALL SELECT 'cantidad_total', round(sum(cantidad), 2)::text FROM mov_feb
UNION ALL SELECT 'movimientos_antes', count(*)::text FROM inventario
     WHERE tipomovimiento = 'Venta'
       AND fechamovimiento >= '2026-02-01' AND fechamovimiento < '2026-03-01'
UNION ALL SELECT 'cantidad_antes', round(sum(cantidad), 2)::text FROM inventario
     WHERE tipomovimiento = 'Venta'
       AND fechamovimiento >= '2026-02-01' AND fechamovimiento < '2026-03-01';

BEGIN;

CREATE TABLE IF NOT EXISTS inventario_bkp_venta_feb2026 (LIKE inventario INCLUDING ALL);

INSERT INTO inventario_bkp_venta_feb2026
SELECT i.*
FROM inventario i
WHERE i.tipomovimiento = 'Venta'
  AND i.fechamovimiento >= '2026-02-01' AND i.fechamovimiento < '2026-03-01'
  AND NOT EXISTS (SELECT 1 FROM inventario_bkp_venta_feb2026 b WHERE b.codigo = i.codigo);

DELETE FROM inventario
WHERE tipomovimiento = 'Venta'
  AND fechamovimiento >= '2026-02-01' AND fechamovimiento < '2026-03-01'
  AND coalesce(notas, '') NOT LIKE 'Articulo Vendido%';

CREATE TEMP SEQUENCE s_mov;
SELECT setval('s_mov', coalesce((SELECT max(codigo) FROM inventario), 0));

INSERT INTO inventario (codigo, cantidad, fechamovimiento, notas, processed, status,
                        tipomovimiento, articulo_codigo, usuario_id)
SELECT nextval('s_mov'),
       -m.cantidad,
       m.fecha,
       'Venta ' || m.numero_consecutivo || ' (conciliado feb 2026)',
       true, true, 'Venta', m.articulo, m.usuario_id
FROM mov_feb m
WHERE m.articulo IS NOT NULL
  AND m.cantidad > 0;

SELECT setval('inventario_codigo_seq', (SELECT coalesce(max(codigo), 1) FROM inventario));

\echo '===== despues'
SELECT count(*) AS movimientos_despues, round(sum(cantidad)::numeric, 2) AS cantidad_despues
FROM inventario
WHERE tipomovimiento = 'Venta'
  AND fechamovimiento >= '2026-02-01' AND fechamovimiento < '2026-03-01';

\if :aplicar
COMMIT;
\echo 'APLICADO';
\else
ROLLBACK;
\echo 'DRY-RUN (rollback)';
\endif
