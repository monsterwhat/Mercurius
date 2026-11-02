-- Genera los movimientos de inventario (Venta) que faltan para 2023-2026 a partir
-- de las lineas de detalle del snapshot.
--
-- Deduplicacion por conteo: para cada (articulo, fecha, cantidad) se compara con
-- lo que ya hay en `inventario` y solo se inserta el excedente.
-- Uso: psql -v aplicar=false (dry-run) | -v aplicar=true (commit)
\pset pager off
\set ON_ERROR_STOP on

\echo '===== staging'
DROP TABLE IF EXISTS stg_lm;
CREATE TEMP TABLE stg_lm (fila text, factura text, fecha text, codigo text, codigocaby text,
    descrip text, cantidad text, descuento text, valorunita text, total text, familia text,
    exento text, porceimp text, imp text, usuario text, nlinea text, cservicios text, unidad text,
    origen text);
\copy stg_lm FROM '/tmp/dbf_reconciliacion/lineas_mod.csv' WITH (FORMAT csv, HEADER true)

\echo '===== claves de la base (2023-2026)'
DROP TABLE IF EXISTS base_mov;
CREATE TEMP TABLE base_mov AS
SELECT articulo_codigo AS art, fechamovimiento::date AS f, cantidad AS cant,
       tipomovimiento AS tipo, count(*) AS c
FROM inventario
WHERE fechamovimiento >= '2023-01-01'
GROUP BY 1, 2, 3, 4;
CREATE INDEX ON base_mov (art, f, cant, tipo);
ANALYZE base_mov;

\echo '===== candidatos'
DROP TABLE IF EXISTS cand;
CREATE TEMP TABLE cand AS
SELECT art, f, cant, 'Venta'::text AS tipo, doc,
       row_number() OVER (PARTITION BY art, f, cant ORDER BY doc) AS rn
FROM (
    SELECT a.codigo AS art, l.fecha::date AS f,
           -abs(coalesce(nullif(trim(l.cantidad), '')::numeric, 0)) AS cant,
           replace(split_part(trim(l.factura), '@', 1), ' ', '') AS doc
    FROM stg_lm l
    JOIN articulos a ON a.codigobarra = l.codigo
    WHERE l.fecha >= '2023-01-01'
      AND coalesce(trim(l.cantidad), '') <> ''
      AND trim(l.fecha) ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'
) x;

SELECT 'candidatos' AS metrica, count(*) AS filas FROM cand;

\echo '===== movimientos que faltan'
DROP TABLE IF EXISTS mov_nuevos;
CREATE TEMP TABLE mov_nuevos AS
SELECT c.art, c.f, c.cant, c.tipo, c.doc
FROM cand c
LEFT JOIN base_mov b ON b.art = c.art AND b.f = c.f AND b.cant = c.cant AND b.tipo = c.tipo
WHERE c.rn > coalesce(b.c, 0);

SELECT to_char(f, 'YYYY') AS anio, count(*) AS filas FROM mov_nuevos GROUP BY 1 ORDER BY 1;

BEGIN;

INSERT INTO inventario (codigo, cantidad, fechamovimiento, tipomovimiento, articulo_codigo,
                        notas, status, processed, usuario_id)
SELECT (SELECT coalesce(max(codigo), 0) FROM inventario)
           + row_number() OVER (ORDER BY f, art, doc),
       cant, f::timestamp, tipo, art, doc, true, true, NULL
FROM mov_nuevos;

\echo '===== totales'
SELECT 'inventario' AS tabla, count(*) AS filas FROM inventario;

\if :aplicar
COMMIT;
\echo 'APLICADO';
\else
ROLLBACK;
\echo 'DRY-RUN (rollback)';
\endif
