-- Crea el articulo que falta en el catalogo y que si se vendio en el sistema
-- viejo: GUARO ANEJAZO 335ML (codigo de barras 7443004340162).
--
-- Aparece en 322 lineas de enero a julio 2026 (402.600 CRC) y es el unico
-- codigo de venta 2026 sin articulo: el resto del catalogo esta completo.
--
-- Idempotente: no hace nada si el codigo de barras ya existe.
--
-- Uso: psql -v aplicar=false|true -f crear_articulo_guaro_2026.sql

\pset pager off
\set ON_ERROR_STOP on

SELECT count(*) AS ya_existe FROM articulos WHERE codigobarra = '7443004340162';

BEGIN;

INSERT INTO articulos (codigobarra, nombre, descripcion, exento, status, unidadmedida,
                       cabys_id, familia_id, departamento_id, tipo_refrigeracion,
                       stockoptimo, diasstockseguridad, fecha, processed, estadoalertas, version)
SELECT '7443004340162', 'GUARO ANEJAZO 335ML', 'GUARO ANEJAZO 335ML', true, true, 'Unid',
       '2413100000500', 93, 41, 'NINGUNA',
       0, 0, now(), true, false, 0
WHERE NOT EXISTS (SELECT 1 FROM articulos WHERE codigobarra = '7443004340162');

INSERT INTO articulostock (codigobarra, codigo_barra, stock, lastupdated, version)
SELECT '7443004340162', '7443004340162', 0, now(), 0
WHERE NOT EXISTS (SELECT 1 FROM articulostock WHERE codigo_barra = '7443004340162');

INSERT INTO articuloprecio (fechacompra, porcentaje_utilidad, precio_con_utilidad,
                            precio_costo_sin_iva, precio_final, articulo_id, usuario_id)
SELECT now(), NULL, 1100, NULL, 1100, a.codigo, 1
FROM articulos a
WHERE a.codigobarra = '7443004340162'
  AND NOT EXISTS (SELECT 1 FROM articuloprecio p WHERE p.articulo_id = a.codigo);

SELECT a.codigo, a.codigobarra, a.nombre, a.exento, a.cabys_id,
       s.stock, p.precio_final
FROM articulos a
LEFT JOIN articulostock s ON s.codigo_barra = a.codigobarra
LEFT JOIN articuloprecio p ON p.articulo_id = a.codigo
WHERE a.codigobarra = '7443004340162';

\if :aplicar
COMMIT;
\echo 'APLICADO';
\else
ROLLBACK;
\echo 'DRY-RUN (rollback)';
\endif
