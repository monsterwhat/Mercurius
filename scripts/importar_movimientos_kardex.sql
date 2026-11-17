-- Importa las entradas (IM) y ajustes (AJ) del kardex viejo que nunca se
-- cargaron: 2015-2019 (E:\VPALMERAS) y 2022-2026 (respaldo de enero 2026).
--
-- Las ventas del kardex (FE) no se tocan: ya estan representadas por los
-- movimientos 'Venta' generados desde las facturas.
--
-- El CSV lo genera scripts/extraer_movimientos_kardex.py:
--   codigo,fecha,cantidad,tipo,documento,usuario
--
-- Idempotente: solo inserta si no existe un movimiento con el mismo articulo,
-- fecha, cantidad y tipo.
--
-- Uso: copiar el CSV como /tmp/dbf_reconciliacion/mov_kardex.csv y correr
--      psql -v aplicar=false|true -f importar_movimientos_kardex.sql

\pset pager off
\set ON_ERROR_STOP on

DROP TABLE IF EXISTS stg_kardex;
CREATE TEMP TABLE stg_kardex (
    codigo text, fecha text, cantidad text, tipo text, documento text, usuario text
);
\copy stg_kardex FROM '/tmp/dbf_reconciliacion/mov_kardex.csv' WITH (FORMAT csv, HEADER true)

DROP TABLE IF EXISTS kdx;
CREATE TEMP TABLE kdx AS
SELECT a.codigo AS articulo,
       s.fecha::date AS fecha,
       s.cantidad::numeric AS cantidad,
       s.tipo,
       nullif(s.documento, '') AS documento,
       (SELECT u.id FROM users u WHERE u.username = s.usuario LIMIT 1) AS usuario_id
FROM stg_kardex s
JOIN articulos a ON a.codigobarra = s.codigo;

DROP TABLE IF EXISTS kdx_new;
CREATE TEMP TABLE kdx_new AS
SELECT k.*
FROM kdx k
WHERE NOT EXISTS (
    SELECT 1 FROM inventario i
    WHERE i.tipomovimiento = k.tipo
      AND i.articulo_codigo = k.articulo
      AND i.fechamovimiento = k.fecha
      AND i.cantidad = k.cantidad
);

SELECT 'filas_csv' AS metrica, count(*)::text AS valor FROM stg_kardex
UNION ALL SELECT 'con_articulo', count(*)::text FROM kdx
UNION ALL SELECT 'sin_articulo', (count(*) - (SELECT count(*) FROM kdx))::text FROM stg_kardex
UNION ALL SELECT 'ya_existen', (count(*) - (SELECT count(*) FROM kdx_new))::text FROM kdx
UNION ALL SELECT 'a_insertar', count(*)::text FROM kdx_new
UNION ALL SELECT 'cantidad_entrada', round(coalesce(sum(cantidad) FILTER (WHERE tipo = 'ENTRADA'), 0), 2)::text FROM kdx_new
UNION ALL SELECT 'cantidad_ajuste', round(coalesce(sum(cantidad) FILTER (WHERE tipo = 'Ajuste'), 0), 2)::text FROM kdx_new;

SELECT to_char(fecha, 'YYYY') AS anio, tipo, count(*) AS filas,
       round(sum(cantidad)::numeric, 2) AS cantidad
FROM kdx_new GROUP BY 1, 2 ORDER BY 1, 2;

BEGIN;

CREATE TEMP SEQUENCE s_kdx;
SELECT setval('s_kdx', coalesce((SELECT max(codigo) FROM inventario), 0));

INSERT INTO inventario (codigo, cantidad, fechamovimiento, notas, processed, status,
                        tipomovimiento, articulo_codigo, usuario_id)
SELECT nextval('s_kdx'),
       k.cantidad,
       k.fecha,
       'Kardex ' || k.tipo || coalesce(' doc ' || k.documento, '') || ' (migracion vsuper)',
       true, true,
       k.tipo, k.articulo, k.usuario_id
FROM kdx_new k;

SELECT setval('inventario_codigo_seq', (SELECT coalesce(max(codigo), 1) FROM inventario));

SELECT 'inventario_total' AS metrica, count(*)::text AS valor FROM inventario;

\if :aplicar
COMMIT;
\echo 'APLICADO';
\else
ROLLBACK;
\echo 'DRY-RUN (rollback)';
\endif
