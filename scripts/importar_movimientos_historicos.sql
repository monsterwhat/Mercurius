-- Importa los movimientos de inventario que faltan: 2015-2016 (kardex E:) y 2022
-- (detafact_respaldo_respaldo).
--
-- Deduplicacion por conteo: para cada (articulo, fecha, cantidad, tipo) se compara
-- cuantas filas hay en la base y se insertan solo las que sobran. Asi no se pierden
-- ventas repetidas del mismo articulo el mismo dia y tampoco se duplica lo ya cargado.
--
-- Solo se importan movimientos de articulos que existen en el catalogo (FK).
-- Uso: psql -v aplicar=false (dry-run) | -v aplicar=true (commit)
\pset pager off
\set ON_ERROR_STOP on

\echo '===== staging'
DROP TABLE IF EXISTS stg_kardex;
CREATE TEMP TABLE stg_kardex (movimiento text, documento text, fecha text, codigo text,
    cantidad text, venta text, costo text, usuario text, saldo text, exento text);
\copy stg_kardex FROM '/tmp/dbf_reconciliacion/kardex_e.csv' WITH (FORMAT csv, HEADER true)

DROP TABLE IF EXISTS stg_2022;
CREATE TEMP TABLE stg_2022 (factura text, fecha text, codigo text, codigocaby text,
    descrip text, cantidad text, descuento text, valorunita text, total text, familia text,
    exento text, porceimp text, imp text, usuario text, nlinea text, cservicios text,
    unidad text);
\copy stg_2022 FROM '/tmp/dbf_reconciliacion/detafact_2022.csv' WITH (FORMAT csv, HEADER true)

DROP TABLE IF EXISTS stg_entradas;
CREATE TEMP TABLE stg_entradas (documento text, fecha text, codigo text, cantidad text,
    costo text, prov text, codprov text, usuario text, detalle text);
\copy stg_entradas FROM '/tmp/dbf_reconciliacion/entradas_mov.csv' WITH (FORMAT csv, HEADER true)

\echo '===== claves de la base (2015-2022)'
DROP TABLE IF EXISTS base_mov;
CREATE TEMP TABLE base_mov AS
SELECT articulo_codigo AS art, fechamovimiento::date AS f, cantidad AS cant,
       tipomovimiento AS tipo, count(*) AS c
FROM inventario
WHERE fechamovimiento >= '2015-01-01' AND fechamovimiento < '2023-01-01'
GROUP BY 1, 2, 3, 4;
CREATE INDEX ON base_mov (art, f, cant, tipo);
ANALYZE base_mov;

\echo '===== candidatos'
DROP TABLE IF EXISTS cand;
CREATE TEMP TABLE cand AS
WITH x AS (
    -- kardex 2015-2016: FE = venta, FA = entrada, AJ/CI/IM = movimiento
    SELECT a.codigo AS art, k.fecha::date AS f, k.cantidad::numeric AS cant,
           CASE k.movimiento WHEN 'FE' THEN 'Venta'
                             WHEN 'FA' THEN 'ENTRADA'
                             ELSE 'MOVIMIENTO' END AS tipo,
           CASE WHEN k.movimiento = 'FE' THEN trim(k.documento)
                ELSE k.movimiento || ' ' || trim(k.documento) END AS doc
    FROM stg_kardex k
    JOIN articulos a ON a.codigobarra = k.codigo
    WHERE k.fecha >= '2015-01-01' AND k.fecha < '2017-01-01'
      AND coalesce(trim(k.cantidad), '') <> '' AND coalesce(trim(k.fecha), '') <> ''

    UNION ALL

    -- entradas manuales 2010-2016
    SELECT a.codigo, e.fecha::date, e.cantidad::numeric, 'ENTRADA',
           'ENTRADA ' || trim(e.documento)
    FROM stg_entradas e
    JOIN articulos a ON a.codigobarra = e.codigo
    WHERE e.fecha >= '2015-01-01' AND e.fecha < '2017-01-01'
      AND coalesce(trim(e.cantidad), '') <> '' AND coalesce(trim(e.fecha), '') <> ''

    UNION ALL

    -- 2022: cada linea del detalle es una salida de inventario
    SELECT a.codigo, d.fecha::date, -abs(coalesce(nullif(trim(d.cantidad), '')::numeric, 0)),
           'Venta', trim(d.factura)
    FROM stg_2022 d
    JOIN articulos a ON a.codigobarra = d.codigo
    WHERE coalesce(trim(d.cantidad), '') <> '' AND coalesce(trim(d.fecha), '') <> ''
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
FROM inventario WHERE fechamovimiento >= '2015-01-01' AND fechamovimiento < '2023-01-01'
GROUP BY 1 ORDER BY 1;

\if :aplicar
COMMIT;
\echo 'APLICADO';
\else
ROLLBACK;
\echo 'DRY-RUN (rollback)';
\endif
