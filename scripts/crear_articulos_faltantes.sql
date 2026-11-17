-- Crea los articulos que se vendieron en el sistema viejo (2022-2025) pero que
-- no existen en el catalogo de Mercurius.
--
-- El CSV lo genera scripts/crear_articulos_faltantes.py:
--   codigo,nombre,cabys,exento,precio,activo
--
-- Los que siguen en el maestro vigente quedan status = true; el resto queda
-- inactivo (visible en el historico, fuera del POS).
--
-- Idempotente: no toca codigos que ya existan.
--
-- Uso: copiar el CSV como /tmp/dbf_reconciliacion/articulos_faltantes.csv y
--      correr psql -v aplicar=false|true -f crear_articulos_faltantes.sql

\pset pager off
\set ON_ERROR_STOP on

DROP TABLE IF EXISTS stg_art;
CREATE TEMP TABLE stg_art (
    codigo text, nombre text, cabys text, exento text, precio text, activo text
);
\copy stg_art FROM '/tmp/dbf_reconciliacion/articulos_faltantes.csv' WITH (FORMAT csv, HEADER true)

DROP TABLE IF EXISTS art_nuevos;
CREATE TEMP TABLE art_nuevos AS
SELECT s.codigo,
       left(s.nombre, 255) AS nombre,
       nullif(s.cabys, '') AS cabys,
       (s.exento = '1') AS exento,
       (s.activo = '1') AS activo,
       nullif(s.precio, '')::numeric AS precio
FROM stg_art s
WHERE nullif(s.codigo, '') IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM articulos a WHERE a.codigobarra = s.codigo);

SELECT count(*) AS filas_csv FROM stg_art
UNION ALL SELECT count(*) FROM art_nuevos;

BEGIN;

INSERT INTO articulos (codigobarra, nombre, descripcion, exento, status, unidadmedida,
                       cabys_id, tipo_refrigeracion, stockoptimo, diasstockseguridad,
                       fecha, processed, estadoalertas, version)
SELECT n.codigo, n.nombre, n.nombre, n.exento, n.activo, 'Unid',
       (SELECT c.codigo FROM cabys c WHERE c.codigo = n.cabys),
       'NINGUNA', 0, 0, now(), true, false, 0
FROM art_nuevos n;

INSERT INTO articulostock (codigobarra, codigo_barra, stock, lastupdated, version)
SELECT n.codigo, n.codigo, 0, now(), 0
FROM art_nuevos n
WHERE NOT EXISTS (SELECT 1 FROM articulostock s WHERE s.codigo_barra = n.codigo);

INSERT INTO articuloprecio (fechacompra, porcentaje_utilidad, precio_con_utilidad,
                            precio_costo_sin_iva, precio_final, articulo_id, usuario_id)
SELECT now(), NULL, n.precio, NULL, n.precio, a.codigo, 1
FROM art_nuevos n
JOIN articulos a ON a.codigobarra = n.codigo
WHERE n.precio IS NOT NULL AND n.precio > 0;

\echo '===== verificacion'
SELECT count(DISTINCT cc.codigo) AS codigos_sin_articulo
FROM codigo_comercial cc
WHERE cc.tipo = '01'
  AND NOT EXISTS (SELECT 1 FROM articulos a WHERE a.codigobarra = cc.codigo);

\if :aplicar
COMMIT;
\echo 'APLICADO';
\else
ROLLBACK;
\echo 'DRY-RUN (rollback)';
\endif
