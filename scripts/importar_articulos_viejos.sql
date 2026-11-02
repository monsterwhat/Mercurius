-- Importa el catalogo viejo completo (Articulos.dbf 15 059 + maestro.DBF 6 424).
--
-- Solo inserta los codigos que NO existen en `articulos`. Los articulos que
-- seguian activos en el maestro de 2019 quedan con status=true; los que solo
-- aparecen en Articulos.dbf (codigos retirados) quedan con status=false, para
-- que no ensucien el POS pero existan y se puedan ligar a movimientos/facturas.
--
-- Uso: psql -v aplicar=false (dry-run) | -v aplicar=true (commit)
\pset pager off
\set ON_ERROR_STOP on

\echo '===== staging'
DROP TABLE IF EXISTS a_viejo;
CREATE TEMP TABLE a_viejo (codigo text, descripcion text, tipo text, comentario text,
  precio_venta text, precio_costo text, utilidad text, descuento text, unidad_medida text,
  xistencia text, existencia text, existencia2 text, gravado text, familia text);
\copy a_viejo FROM '/tmp/dbf_reconciliacion/articulos_viejo.csv' WITH (FORMAT csv, HEADER true)

DROP TABLE IF EXISTS m_viejo;
CREATE TEMP TABLE m_viejo (codigo text, nombre text, familia text, ubicacion text, cantidad text,
  cantinicio text, cantcierre text, cantventa text, cantcompra text, costo text, margenutil text,
  descuento text, venta text, prom text, ultimaentr text, ultimasali text, stockmin text,
  stockmax text, exento text, prov text, incluyeiv text, tipoarti text, porceimp text);
\copy m_viejo FROM '/tmp/dbf_reconciliacion/maestro_viejo.csv' WITH (FORMAT csv, HEADER true)

\echo '===== catalogo unificado'
DROP TABLE IF EXISTS art_fuente;
CREATE TEMP TABLE art_fuente AS
SELECT DISTINCT ON (cod)
       cod, descripcion, venta, costo, utilidad, stock, exento, en_maestro, ultimaentr
FROM (
    -- Articulos.dbf: descripcion, precios y si estaba en el maestro 2019
    SELECT trim(v.codigo) AS cod,
           coalesce(nullif(v.descripcion, ''), m.nombre) AS descripcion,
           coalesce(nullif(v.precio_venta, '')::numeric, nullif(m.venta, '')::numeric) AS venta,
           coalesce(nullif(v.precio_costo, '')::numeric, nullif(m.costo, '')::numeric) AS costo,
           coalesce(nullif(v.utilidad, '')::numeric, nullif(m.margenutil, '')::numeric) AS utilidad,
           nullif(m.cantidad, '')::numeric AS stock,
           coalesce(v.gravado = 'F', false) AS exento,
           (m.codigo IS NOT NULL) AS en_maestro,
           m.ultimaentr,
           1 AS prio
    FROM a_viejo v
    LEFT JOIN m_viejo m ON trim(m.codigo) = trim(v.codigo)
    WHERE trim(v.codigo) <> ''

    UNION ALL

    -- codigos que solo estan en el maestro 2019
    SELECT trim(m.codigo), m.nombre, nullif(m.venta, '')::numeric, nullif(m.costo, '')::numeric,
           nullif(m.margenutil, '')::numeric, nullif(m.cantidad, '')::numeric,
           (m.exento = 'S'), true, m.ultimaentr, 2
    FROM m_viejo m
    WHERE trim(m.codigo) <> ''
      AND NOT EXISTS (SELECT 1 FROM a_viejo v WHERE trim(v.codigo) = trim(m.codigo))
) x
ORDER BY cod, prio;

\echo '===== articulos nuevos'
DROP TABLE IF EXISTS art_nuevos;
CREATE TEMP TABLE art_nuevos AS
SELECT f.* FROM art_fuente f
WHERE NOT EXISTS (SELECT 1 FROM articulos a WHERE a.codigobarra = f.cod);

SELECT 'catalogo_unificado' AS metrica, count(*) AS filas FROM art_fuente
UNION ALL SELECT 'nuevos', count(*) FROM art_nuevos
UNION ALL SELECT 'nuevos_activos', count(*) FROM art_nuevos WHERE en_maestro
UNION ALL SELECT 'nuevos_con_precio', count(*) FROM art_nuevos WHERE coalesce(venta, 0) > 0
UNION ALL SELECT 'nuevos_con_stock', count(*) FROM art_nuevos WHERE coalesce(stock, 0) <> 0;

BEGIN;

DROP TABLE IF EXISTS mapa_art;
CREATE TEMP TABLE mapa_art AS
SELECT cod, nextval(pg_get_serial_sequence('articulos', 'codigo')) AS nuevo_id FROM art_nuevos;

INSERT INTO articulos (codigo, codigobarra, nombre, descripcion, exento, unidadmedida,
                       unidadmedidacomercial, status, processed, fecha, version, usuario_id)
SELECT m.nuevo_id, a.cod, a.descripcion, a.descripcion, a.exento, 'Unid', 'Unidad',
       a.en_maestro, true, now(), 0, 1
FROM mapa_art m JOIN art_nuevos a USING (cod);

INSERT INTO articulostock (id, codigobarra, codigo_barra, stock, lastupdated, version)
SELECT (SELECT coalesce(max(id), 0) FROM articulostock)
           + row_number() OVER (ORDER BY m.nuevo_id),
       a.cod, a.cod, coalesce(a.stock, 0), now(), 0
FROM mapa_art m JOIN art_nuevos a USING (cod);

INSERT INTO articuloprecio (id, fechacompra, porcentaje_utilidad, precio_con_utilidad,
                            precio_costo_sin_iva, precio_final, articulo_id, usuario_id)
SELECT (SELECT coalesce(max(id), 0) FROM articuloprecio)
           + row_number() OVER (ORDER BY m.nuevo_id),
       coalesce(nullif(a.ultimaentr, '')::timestamp, now()),
       coalesce(a.utilidad, 0),
       round(coalesce(a.costo, 0) * (1 + coalesce(a.utilidad, 0) / 100), 2),
       coalesce(a.costo, 0),
       a.venta,
       m.nuevo_id, 1
FROM mapa_art m JOIN art_nuevos a USING (cod)
WHERE coalesce(a.venta, 0) > 0;

\echo '===== totales'
SELECT 'articulos' AS tabla, count(*) AS filas FROM articulos
UNION ALL SELECT 'articulostock', count(*) FROM articulostock
UNION ALL SELECT 'articuloprecio', count(*) FROM articuloprecio;

SELECT status, count(*) FROM articulos GROUP BY 1;

\if :aplicar
COMMIT;
\echo 'APLICADO';
\else
ROLLBACK;
\echo 'DRY-RUN (rollback)';
\endif
