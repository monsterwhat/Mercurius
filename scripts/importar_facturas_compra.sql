-- Importa a la base viva las FACTURAS DE COMPRA que faltan.
--   A) facturas electrónicas (fe_encabezado/fe_detalle, clave de Hacienda)
--   B) facturas legadas (facturascompras.dbf: cuentas por pagar, sin clave)
-- Idempotente por clave. Los ids se asignan ANTES de insertar (mapa), así el
-- enlace encabezado/detalle/resumen es determinista.
-- Uso: psql -v aplicar=false  (dry-run, ROLLBACK)  |  -v aplicar=true (COMMIT)
\pset pager off
\set ON_ERROR_STOP on

\echo '===== staging'
DROP TABLE IF EXISTS stg_fe_enc;
CREATE TEMP TABLE stg_fe_enc (numerofact text, nombreemis text, cedulaemis text, subtotal text,
    totaldesc text, totalexent text, totaliva13 text, totaliva8 text, totaliva2 text,
    totaliva4 text, totaliva1 text, totaldoc text);
\copy stg_fe_enc FROM '/tmp/dbf_reconciliacion/fe_encabezado.csv' WITH (FORMAT csv, HEADER true)

DROP TABLE IF EXISTS stg_fe_det;
CREATE TEMP TABLE stg_fe_det (numerofact text, emisor text, codigo text, codigocaby text,
    descripcio text, cantidad text, precio_uni text, montototal text, subtotal text,
    codigoimpu text, codigotari text, tarifa text, montoimpue text);
\copy stg_fe_det FROM '/tmp/dbf_reconciliacion/fe_detalle.csv' WITH (FORMAT csv, HEADER true)

DROP TABLE IF EXISTS stg_compras;
CREATE TEMP TABLE stg_compras (proveedor text, nombre text, fecha text, factura text,
    subtotal text, descuento text, total text, saldoactua text, estado text, usuario text);
\copy stg_compras FROM '/tmp/dbf_reconciliacion/facturascompras.csv' WITH (FORMAT csv, HEADER true)

\echo '===== secuencias (el esquema no tiene defaults en estos ids)'
CREATE TEMP SEQUENCE s_iem;  SELECT setval('s_iem',  coalesce((SELECT max(id) FROM identificacion_emisor), 0));
CREATE TEMP SEQUENCE s_emi;  SELECT setval('s_emi',  coalesce((SELECT max(id) FROM emisor), 0));
CREATE TEMP SEQUENCE s_enc;  SELECT setval('s_enc',  coalesce((SELECT max(id) FROM encabezado), 0));
CREATE TEMP SEQUENCE s_det;  SELECT setval('s_det',  coalesce((SELECT max(id) FROM detalle_servicio), 0));
CREATE TEMP SEQUENCE s_lin;  SELECT setval('s_lin',  coalesce((SELECT max(id) FROM linea_detalle), 0));
CREATE TEMP SEQUENCE s_res;  SELECT setval('s_res',  coalesce((SELECT max(id) FROM resumen_factura), 0));
CREATE TEMP SEQUENCE s_rec;  SELECT setval('s_rec',  coalesce((SELECT max(id) FROM comprobantesrecibidos), 0));

\echo '===== A) facturas electronicas faltantes'
DROP TABLE IF EXISTS enc_nuevas;
CREATE TEMP TABLE enc_nuevas AS
SELECT e.numerofact AS clave,
       to_date(substr(e.numerofact, 4, 2) || substr(e.numerofact, 6, 2) || substr(e.numerofact, 8, 2), 'DDMMYY') AS fecha,
       substr(e.numerofact, 22, 20) AS consecutivo,
       e.nombreemis, e.cedulaemis,
       coalesce(NULLIF(e.subtotal,'')::numeric, 0) AS subtotal,
       coalesce(NULLIF(e.totaldesc,'')::numeric, 0) AS descuento,
       coalesce(NULLIF(e.totalexent,'')::numeric, 0) AS exento,
       coalesce(NULLIF(e.totaliva13,'')::numeric, 0)
         + coalesce(NULLIF(e.totaliva8,'')::numeric, 0)
         + coalesce(NULLIF(e.totaliva2,'')::numeric, 0)
         + coalesce(NULLIF(e.totaliva4,'')::numeric, 0)
         + coalesce(NULLIF(e.totaliva1,'')::numeric, 0) AS impuesto,
       coalesce(NULLIF(e.totaldoc,'')::numeric, 0) AS total
FROM stg_fe_enc e
WHERE NOT EXISTS (SELECT 1 FROM encabezado x WHERE x.clave = e.numerofact);

DROP TABLE IF EXISTS leg_nuevas;
CREATE TEMP TABLE leg_nuevas AS
SELECT 'LEG-' || coalesce(c.proveedor, '0') || '-' || coalesce(c.factura, '') AS clave,
       c.proveedor, c.nombre, c.factura,
       NULLIF(c.fecha, '')::date AS fecha,
       coalesce(NULLIF(c.subtotal,'')::numeric, 0) AS subtotal,
       coalesce(NULLIF(c.descuento,'')::numeric, 0) AS descuento,
       coalesce(NULLIF(c.total,'')::numeric, 0) AS total,
       upper(coalesce(c.estado,'')) = 'C' AS pagada
FROM stg_compras c
WHERE NULLIF(c.fecha,'') IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM encabezado x
                  WHERE x.clave = 'LEG-' || coalesce(c.proveedor,'0') || '-' || coalesce(c.factura,''));

\echo '===== a insertar'
SELECT 'facturas_electronicas_nuevas' AS metrica, count(*) AS filas FROM enc_nuevas
UNION ALL SELECT 'proveedores_electronicos', count(DISTINCT cedulaemis) FROM enc_nuevas
UNION ALL SELECT 'facturas_legadas_nuevas', count(*) FROM leg_nuevas
UNION ALL SELECT 'proveedores_legados', count(DISTINCT proveedor) FROM leg_nuevas
UNION ALL SELECT 'lineas_a_insertar', count(*) FROM stg_fe_det d
    JOIN enc_nuevas n ON n.clave = d.numerofact;

BEGIN;

-- ===== A ================================================================
INSERT INTO identificacion_emisor (id, numero, tipo)
SELECT nextval('s_iem'), p.cedulaemis,
       CASE WHEN length(p.cedulaemis) = 9 THEN '01'
            WHEN length(p.cedulaemis) = 10 THEN '02'
            WHEN length(p.cedulaemis) IN (11, 12) THEN '03'
            ELSE '01' END
FROM (SELECT DISTINCT cedulaemis, nombreemis FROM enc_nuevas
      WHERE coalesce(cedulaemis,'') <> '') p
WHERE NOT EXISTS (SELECT 1 FROM identificacion_emisor i WHERE i.numero = p.cedulaemis);

INSERT INTO emisor (id, nombre, nombre_comercial, identificacion_emisor_id)
SELECT nextval('s_emi'), p.nombreemis, p.nombreemis, i.id
FROM (SELECT DISTINCT ON (cedulaemis) cedulaemis, nombreemis FROM enc_nuevas
      WHERE coalesce(cedulaemis,'') <> ''
      ORDER BY cedulaemis, nombreemis) p
JOIN identificacion_emisor i ON i.numero = p.cedulaemis
WHERE NOT EXISTS (SELECT 1 FROM emisor m WHERE m.identificacion_emisor_id = i.id);

DROP TABLE IF EXISTS mapa_a;
CREATE TEMP TABLE mapa_a AS
SELECT n.clave, nextval('s_enc') AS enc_id, nextval('s_det') AS det_id,
       nextval('s_res') AS res_id
FROM enc_nuevas n;

INSERT INTO encabezado (id, clave, codigo_documento, numero_consecutivo, fecha_emision,
                        condicion_venta, estado, schemaversion, emisor_id, receptor_id)
SELECT m.enc_id, n.clave, '01', n.consecutivo, n.fecha::timestamp,
       '01', 'ACEPTADO', '4.4', e.id,
       (SELECT e2.receptor_id FROM encabezado e2
          JOIN comprobantesrecibidos c2 ON c2.encabezado_id = e2.id
         WHERE e2.receptor_id IS NOT NULL LIMIT 1)
FROM mapa_a m
JOIN enc_nuevas n ON n.clave = m.clave
LEFT JOIN identificacion_emisor i ON i.numero = n.cedulaemis
LEFT JOIN emisor e ON e.identificacion_emisor_id = i.id;

INSERT INTO detalle_servicio (id, status) SELECT det_id, true FROM mapa_a;

INSERT INTO resumen_factura (id, codigo_moneda, tipo_cambio_moneda, schemaversion,
    total_comprobante, total_descuentos, total_exento, total_gravado, total_impuesto,
    total_venta, total_venta_neta, total_mercancias_gravadas, total_mercancias_exentas)
SELECT m.res_id, 'CRC', 1, '4.4', n.total, n.descuento, n.exento,
       greatest(n.subtotal - n.exento - n.descuento, 0), n.impuesto,
       n.subtotal - n.descuento, n.subtotal - n.descuento,
       greatest(n.subtotal - n.exento - n.descuento, 0), n.exento
FROM mapa_a m JOIN enc_nuevas n ON n.clave = m.clave;

INSERT INTO linea_detalle (id, numero_linea, detalle, cantidad, precio_unitario,
    monto_total, monto_total_de_linea, sub_total, base_imponible, impuesto_neto,
    codigocabys, unidad_medida, unidad_medida_comercial, tipo_transaccion,
    schemaversion, detalle_servicio_id)
SELECT nextval('s_lin'),
       row_number() OVER (PARTITION BY d.numerofact ORDER BY d.codigo),
       left(d.descripcio, 200),
       coalesce(NULLIF(d.cantidad,'')::numeric, 0),
       coalesce(NULLIF(d.precio_uni,'')::numeric, 0),
       coalesce(NULLIF(d.montototal,'')::numeric, 0),
       coalesce(NULLIF(d.montototal,'')::numeric, 0),
       coalesce(NULLIF(d.subtotal,'')::numeric, 0),
       coalesce(NULLIF(d.subtotal,'')::numeric, 0),
       coalesce(NULLIF(d.montoimpue,'')::numeric, 0),
       NULLIF(d.codigocaby, ''), 'Unid', 'Unidad', '01', '4.4', m.det_id
FROM stg_fe_det d JOIN mapa_a m ON m.clave = d.numerofact;

INSERT INTO comprobantesrecibidos (id, status, processed, paid, usuario, schemaversion,
                                   encabezado_id, detalle_servicio_id, resumen_id)
SELECT nextval('s_rec'), true, true, false, 'MIGRACION', '4.4', enc_id, det_id, res_id
FROM mapa_a;

-- ===== B ================================================================
INSERT INTO identificacion_emisor (id, numero, tipo)
SELECT nextval('s_iem'), 'LEG' || p.proveedor, '02'
FROM (SELECT DISTINCT proveedor FROM leg_nuevas WHERE coalesce(proveedor,'') <> '') p
WHERE NOT EXISTS (SELECT 1 FROM identificacion_emisor i WHERE i.numero = 'LEG' || p.proveedor);

INSERT INTO emisor (id, nombre, nombre_comercial, identificacion_emisor_id)
SELECT nextval('s_emi'), p.nombre, p.nombre, i.id
FROM (SELECT DISTINCT ON (proveedor) proveedor, nombre FROM leg_nuevas
      WHERE coalesce(proveedor,'') <> ''
      ORDER BY proveedor, nombre) p
JOIN identificacion_emisor i ON i.numero = 'LEG' || p.proveedor
WHERE NOT EXISTS (SELECT 1 FROM emisor m WHERE m.identificacion_emisor_id = i.id);

DROP TABLE IF EXISTS mapa_b;
CREATE TEMP TABLE mapa_b AS
SELECT l.clave, nextval('s_enc') AS enc_id, nextval('s_det') AS det_id,
       nextval('s_res') AS res_id
FROM leg_nuevas l;

INSERT INTO encabezado (id, clave, codigo_documento, numero_consecutivo, fecha_emision,
                        condicion_venta, estado, schemaversion, emisor_id, receptor_id)
SELECT m.enc_id, l.clave, '01', lpad(l.factura, 20, '0'), l.fecha::timestamp,
       '01', 'ACEPTADO', '4.4', e.id,
       (SELECT e2.receptor_id FROM encabezado e2
          JOIN comprobantesrecibidos c2 ON c2.encabezado_id = e2.id
         WHERE e2.receptor_id IS NOT NULL LIMIT 1)
FROM mapa_b m
JOIN leg_nuevas l ON l.clave = m.clave
LEFT JOIN identificacion_emisor i ON i.numero = 'LEG' || l.proveedor
LEFT JOIN emisor e ON e.identificacion_emisor_id = i.id;

INSERT INTO detalle_servicio (id, status) SELECT det_id, true FROM mapa_b;

INSERT INTO resumen_factura (id, codigo_moneda, tipo_cambio_moneda, schemaversion,
    total_comprobante, total_descuentos, total_exento, total_gravado, total_impuesto,
    total_venta, total_venta_neta, total_mercancias_gravadas, total_mercancias_exentas)
SELECT m.res_id, 'CRC', 1, '4.4', l.total, l.descuento, 0,
       greatest(l.subtotal - l.descuento, 0), greatest(l.total - l.subtotal, 0),
       l.subtotal - l.descuento, l.subtotal - l.descuento,
       greatest(l.subtotal - l.descuento, 0), 0
FROM mapa_b m JOIN leg_nuevas l ON l.clave = m.clave;

INSERT INTO comprobantesrecibidos (id, status, processed, paid, usuario, schemaversion,
                                   encabezado_id, detalle_servicio_id, resumen_id)
SELECT nextval('s_rec'), true, true, l.pagada, 'MIGRACION', '4.4', m.enc_id, m.det_id, m.res_id
FROM mapa_b m JOIN leg_nuevas l ON l.clave = m.clave;

\echo '===== totales'
SELECT 'encabezado' AS tabla, count(*) AS filas FROM encabezado
UNION ALL SELECT 'comprobantesrecibidos', count(*) FROM comprobantesrecibidos
UNION ALL SELECT 'linea_detalle', count(*) FROM linea_detalle;

\if :aplicar
COMMIT;
\echo 'APLICADO';
\else
ROLLBACK;
\echo 'DRY-RUN (rollback)';
\endif
