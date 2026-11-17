-- Corrige las filas de articuloprecio de los articulos creados desde el
-- historico (los 373 de crear_articulos_faltantes + el guaro).
--
-- Quedaron con precio_final (bruto) copiado tambien en precio_con_utilidad y
-- sin costo. Lo correcto es:
--   precio_con_utilidad = precio_final / 1.13 (o igual si el articulo es exento)
--   porcentaje_utilidad = (precio_con_utilidad / costo - 1) * 100
--
-- El costo solo existe para 11 de los 374 en los catalogos viejos
-- (MAESTRO.DBF, Articulos.dbf, maestro.DBF); el resto queda sin costo, que es
-- un dato que no existe en ningun respaldo.
--
-- Uso: psql -v aplicar=false|true -f corregir_precios_articulos_creados.sql

\pset pager off
\set ON_ERROR_STOP on

DROP TABLE IF EXISTS stg_creados;
CREATE TEMP TABLE stg_creados (codigo text, nombre text, cabys text, exento text, precio text, activo text);
\copy stg_creados FROM '/tmp/dbf_reconciliacion/articulos_faltantes.csv' WITH (FORMAT csv, HEADER true)

INSERT INTO stg_creados VALUES ('7443004340162', 'GUARO ANEJAZO 335ML', '2413100000500', '1', '1100.00', '0');

DROP TABLE IF EXISTS stg_costos;
CREATE TEMP TABLE stg_costos (codigo text, costo text, margen text, venta text);
\copy stg_costos FROM '/tmp/dbf_reconciliacion/costos_faltantes.csv' WITH (FORMAT csv, HEADER true)

\echo '===== antes'
SELECT count(*) AS filas,
       count(*) FILTER (WHERE p.precio_costo_sin_iva IS NULL) AS sin_costo,
       count(*) FILTER (WHERE p.precio_con_utilidad = p.precio_final) AS neto_igual_bruto
FROM articuloprecio p
JOIN articulos a ON a.codigo = p.articulo_id
JOIN stg_creados c ON c.codigo = a.codigobarra;

BEGIN;

\echo '===== precio neto'
UPDATE articuloprecio p
SET precio_con_utilidad = round(p.precio_final / CASE WHEN a.exento THEN 1 ELSE 1.13 END, 2)
FROM articulos a
JOIN stg_creados c ON c.codigo = a.codigobarra
WHERE p.articulo_id = a.codigo
  AND p.precio_final IS NOT NULL;

\echo '===== costo y utilidad (solo donde hay costo)'
UPDATE articuloprecio p
SET precio_costo_sin_iva = s.costo::numeric,
    porcentaje_utilidad = round(
        ((p.precio_final / CASE WHEN a.exento THEN 1 ELSE 1.13 END) / s.costo::numeric - 1) * 100, 2)
FROM articulos a
JOIN stg_costos s ON s.codigo = a.codigobarra
WHERE p.articulo_id = a.codigo
  AND s.costo::numeric > 0;

\echo '===== despues'
SELECT count(*) AS filas,
       count(*) FILTER (WHERE p.precio_costo_sin_iva IS NOT NULL) AS con_costo,
       count(*) FILTER (WHERE p.precio_con_utilidad = p.precio_final) AS neto_igual_bruto
FROM articuloprecio p
JOIN articulos a ON a.codigo = p.articulo_id
JOIN stg_creados c ON c.codigo = a.codigobarra;

SELECT a.codigobarra, a.nombre, p.precio_costo_sin_iva, p.porcentaje_utilidad,
       p.precio_con_utilidad, p.precio_final
FROM articuloprecio p
JOIN articulos a ON a.codigo = p.articulo_id
JOIN stg_costos s ON s.codigo = a.codigobarra
ORDER BY a.nombre LIMIT 12;

\if :aplicar
COMMIT;
\echo 'APLICADO';
\else
ROLLBACK;
\echo 'DRY-RUN (rollback)';
\endif
