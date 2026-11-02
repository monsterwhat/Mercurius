-- Importa a la base viva lo que FALTA del snapshot vSuper 2026-10-03:
--   * stock por artículo (articulostock)
--   * movimientos de inventario 2026 (KARDEX + ENTRADAS)
-- Idempotente: solo inserta lo que no existe, comparando por claves naturales.
-- Todo dentro de una transacción; imprime el antes/después.
\pset pager off
\set ON_ERROR_STOP on

\echo '===== staging'
DROP TABLE IF EXISTS stg_maestro;
CREATE TEMP TABLE stg_maestro (codigo text, nombre text, codigocaby text, familia text,
    cantidad text, costo text, margenutil text, venta text, exento text, porceimp text,
    ultimaentr text, ultimasali text, stockmin text, stockmax text);
\copy stg_maestro FROM '/tmp/dbf_reconciliacion/maestro.csv' WITH (FORMAT csv, HEADER true)

DROP TABLE IF EXISTS stg_kardex;
CREATE TEMP TABLE stg_kardex (codigo text, fecha text, movimiento text, documento text,
    cantidad text, usuario text, costo text, venta text, tipodocume text, control text,
    proveedor text, saldo text);
\copy stg_kardex FROM '/tmp/dbf_reconciliacion/kardex.csv' WITH (FORMAT csv, HEADER true)

DROP TABLE IF EXISTS stg_entradas;
CREATE TEMP TABLE stg_entradas (codigo text, fecha text, documento text, cantidad text,
    costo text, prov text, codprov text, detalle text, usuario text);
\copy stg_entradas FROM '/tmp/dbf_reconciliacion/entradas.csv' WITH (FORMAT csv, HEADER true)

\echo '===== claves de la base viva'
DROP TABLE IF EXISTS inv_keys;
CREATE TEMP TABLE inv_keys AS
SELECT articulo_codigo, fechamovimiento::date AS f, cantidad, coalesce(notas,'') AS doc, tipomovimiento
FROM inventario;
CREATE INDEX ON inv_keys (articulo_codigo, f);

\echo '===== pares a insertar'
DROP TABLE IF EXISTS mov_nuevos;
CREATE TEMP TABLE mov_nuevos AS
SELECT DISTINCT art, f, cant, doc, tipo
FROM (
    SELECT a.codigo AS art, NULLIF(k.fecha,'')::date AS f,
           NULLIF(k.cantidad,'')::numeric AS cant, coalesce(k.documento,'') AS doc,
           CASE WHEN k.movimiento='E' THEN 'ENTRADA'
                WHEN k.movimiento='S' THEN 'SALIDA' ELSE 'MOVIMIENTO' END AS tipo
    FROM stg_kardex k JOIN articulos a ON a.codigobarra = k.codigo
    WHERE NULLIF(k.fecha,'') IS NOT NULL
    UNION ALL
    SELECT a.codigo, NULLIF(e.fecha,'')::date, NULLIF(e.cantidad,'')::numeric,
           coalesce(e.documento,''), 'ENTRADA'
    FROM stg_entradas e JOIN articulos a ON a.codigobarra = e.codigo
    WHERE NULLIF(e.fecha,'') IS NOT NULL
) s
WHERE NOT EXISTS (
    SELECT 1 FROM inv_keys i
    WHERE i.articulo_codigo = s.art AND i.f = s.f AND i.cantidad = s.cant AND i.doc = s.doc
      AND i.tipomovimiento = s.tipo
);

SELECT 'movimientos_nuevos' AS metrica, count(*) AS filas FROM mov_nuevos;
SELECT 'stock_nuevo' AS metrica, count(*) AS filas
FROM stg_maestro m JOIN articulos a ON a.codigobarra = m.codigo
LEFT JOIN articulostock s ON s.codigo_barra = a.codigobarra
WHERE s.id IS NULL AND NULLIF(m.cantidad,'') IS NOT NULL;

BEGIN;

\echo '===== stock'
INSERT INTO articulostock (id, codigo_barra, codigobarra, stock, lastupdated, version)
SELECT (SELECT coalesce(max(id), 0) FROM articulostock) + row_number() OVER (ORDER BY a.codigo),
       a.codigobarra, a.codigobarra,
       round(NULLIF(m.cantidad,'')::numeric, 2), now(), 0
FROM stg_maestro m
JOIN articulos a ON a.codigobarra = m.codigo
LEFT JOIN articulostock s ON s.codigo_barra = a.codigobarra
WHERE s.id IS NULL AND NULLIF(m.cantidad,'') IS NOT NULL;

\echo '===== movimientos'
INSERT INTO inventario (codigo, cantidad, fechamovimiento, tipomovimiento, articulo_codigo,
                        notas, status, processed, usuario_id)
SELECT (SELECT coalesce(max(codigo), 0) FROM inventario) + row_number() OVER (ORDER BY f, art, tipo, doc),
       cant, f::timestamp, tipo, art, doc, true, true, NULL
FROM mov_nuevos;

\echo '===== totales despues'
SELECT 'articulostock' AS tabla, count(*) AS filas FROM articulostock
UNION ALL SELECT 'inventario', count(*) FROM inventario;

COMMIT;
