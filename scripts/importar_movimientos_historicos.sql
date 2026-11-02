-- Completa los movimientos de inventario historicos con TODAS las fuentes viejas:
--   * kardex.DBF de E:           2015-10 .. 2019-04 (con numero de factura)
--   * salidas.dbf de E:          2015-08 .. 2019-04 (sin numero; solo fuera del kardex)
--   * entradas.dbf de E:         2010-04 .. 2016-09 (compras)
--
-- Deduplicacion por conteo de (articulo, fecha, cantidad, tipo): se compara con lo
-- que ya hay en `inventario` y solo se inserta el excedente, asi no se duplican
-- los movimientos que ya estaban ni se pierden ventas repetidas del mismo dia.
--
-- Uso: psql -v aplicar=false (dry-run) | -v aplicar=true (commit)
\pset pager off
\set ON_ERROR_STOP on

\echo '===== staging'
DROP TABLE IF EXISTS stg_kardex;
CREATE TEMP TABLE stg_kardex (movimiento text, documento text, fecha text, codigo text,
    cantidad text, venta text, costo text, usuario text, saldo text, exento text);
\copy stg_kardex FROM '/tmp/dbf_reconciliacion/kardex_e.csv' WITH (FORMAT csv, HEADER true)

DROP TABLE IF EXISTS stg_entradas;
CREATE TEMP TABLE stg_entradas (documento text, fecha text, codigo text, cantidad text,
    costo text, prov text, codprov text, usuario text, detalle text);
\copy stg_entradas FROM '/tmp/dbf_reconciliacion/entradas_mov.csv' WITH (FORMAT csv, HEADER true)

DROP TABLE IF EXISTS stg_salidas;
CREATE TEMP TABLE stg_salidas (fecha text, codigo text, cantidad text, familia text,
    usuario text, exento text);
\copy stg_salidas FROM '/tmp/dbf_reconciliacion/salidas_e_mov.csv' WITH (FORMAT csv, HEADER true)

\echo '===== claves de la base (hasta 2022)'
DROP TABLE IF EXISTS base_mov;
CREATE TEMP TABLE base_mov AS
SELECT articulo_codigo AS art, fechamovimiento::date AS f, cantidad AS cant,
       tipomovimiento AS tipo, count(*) AS c
FROM inventario
WHERE fechamovimiento < '2023-01-01'
GROUP BY 1, 2, 3, 4;
CREATE INDEX ON base_mov (art, f, cant, tipo);
ANALYZE base_mov;

\echo '===== candidatos'
DROP TABLE IF EXISTS cand;
CREATE TEMP TABLE cand AS
WITH x AS (
    -- kardex: FE = venta, FA = entrada, AJ/CI/IM = movimiento
    SELECT a.codigo AS art, k.fecha::date AS f, k.cantidad::numeric AS cant,
           CASE k.movimiento WHEN 'FE' THEN 'Venta'
                             WHEN 'FA' THEN 'ENTRADA'
                             ELSE 'MOVIMIENTO' END AS tipo,
           CASE WHEN k.movimiento = 'FE' THEN trim(k.documento)
                ELSE k.movimiento || ' ' || trim(k.documento) END AS doc
    FROM stg_kardex k
    JOIN articulos a ON a.codigobarra = k.codigo
    WHERE k.fecha >= '2015-01-01' AND k.fecha < '2023-01-01'
      AND coalesce(trim(k.cantidad), '') <> '' AND coalesce(trim(k.fecha), '') <> ''

    UNION ALL

    -- entradas manuales de compra (2010-2016)
    SELECT a.codigo, e.fecha::date, e.cantidad::numeric, 'ENTRADA',
           'ENTRADA ' || trim(e.documento)
    FROM stg_entradas e
    JOIN articulos a ON a.codigobarra = e.codigo
    WHERE e.fecha >= '2010-01-01' AND e.fecha < '2017-01-01'
      AND coalesce(trim(e.cantidad), '') <> '' AND coalesce(trim(e.fecha), '') <> ''

    UNION ALL

    -- salidas de inventario: solo donde el kardex no llega (2015-08/09, 2018-2019)
    SELECT a.codigo, s.fecha::date, -abs(s.cantidad::numeric), 'Venta', 'SALIDA'
    FROM stg_salidas s
    JOIN articulos a ON a.codigobarra = s.codigo
    WHERE (s.fecha < '2015-10-01' OR s.fecha >= '2017-01-01')
      AND s.fecha < '2023-01-01'
      AND coalesce(trim(s.cantidad), '') <> '' AND coalesce(trim(s.fecha), '') <> ''
)
SELECT art, f, cant, tipo, doc,
       row_number() OVER (PARTITION BY art, f, cant, tipo ORDER BY doc) AS rn
FROM x;

SELECT 'candidatos' AS metrica, count(*) AS filas FROM cand
UNION ALL SELECT 'articulos_distintos', count(DISTINCT art) FROM cand;

\echo '===== movimientos que faltan'
DROP TABLE IF EXISTS mov_nuevos;
CREATE TEMP TABLE mov_nuevos AS
SELECT c.art, c.f, c.cant, c.tipo, c.doc
FROM cand c
LEFT JOIN base_mov b ON b.art = c.art AND b.f = c.f AND b.cant = c.cant AND b.tipo = c.tipo
WHERE c.rn > coalesce(b.c, 0);

SELECT to_char(f, 'YYYY') AS anio, tipo, count(*) AS filas
FROM mov_nuevos GROUP BY 1, 2 ORDER BY 1, 2;

BEGIN;

INSERT INTO inventario (codigo, cantidad, fechamovimiento, tipomovimiento, articulo_codigo,
                        notas, status, processed, usuario_id)
SELECT (SELECT coalesce(max(codigo), 0) FROM inventario)
           + row_number() OVER (ORDER BY f, art, tipo, doc),
       cant, f::timestamp, tipo, art, doc, true, true, NULL
FROM mov_nuevos;

\echo '===== totales'
SELECT 'inventario' AS tabla, count(*) AS filas FROM inventario;
SELECT to_char(fechamovimiento, 'YYYY') AS anio, count(*) AS filas
FROM inventario WHERE fechamovimiento < '2023-01-01' GROUP BY 1 ORDER BY 1;

\if :aplicar
COMMIT;
\echo 'APLICADO';
\else
ROLLBACK;
\echo 'DRY-RUN (rollback)';
\endif
