-- Importa facturas EMITIDAS desde los XML v4.4 ya parseados (CSV).
-- Solo inserta las claves que NO están en `encabezado` (las 626 de 28-30 oct).
-- Uso: psql -v aplicar=false (dry-run) | -v aplicar=true (commit)
\pset pager off
\set ON_ERROR_STOP on

\echo '===== staging'
DROP TABLE IF EXISTS stg_xenc;
CREATE TEMP TABLE stg_xenc (clave text, codigo_documento text, consecutivo text, fecha text,
    condicion_venta text, plazo_credito text, actividad_emisor text, emisor_numero text,
    emisor_nombre text, receptor_numero text, receptor_nombre text, receptor_tipo text);
\copy stg_xenc FROM '/tmp/dbf_reconciliacion/xml_enc.csv' WITH (FORMAT csv, HEADER true)

DROP TABLE IF EXISTS stg_xres;
CREATE TEMP TABLE stg_xres (clave text, moneda text, tipo_cambio text, serv_gravados text,
    serv_exentos text, merc_gravadas text, merc_exentas text, total_gravado text, total_exento text,
    total_nosujeto text, total_venta text, total_descuentos text, total_venta_neta text,
    total_impuesto text, total_comprobante text);
\copy stg_xres FROM '/tmp/dbf_reconciliacion/xml_res.csv' WITH (FORMAT csv, HEADER true)

DROP TABLE IF EXISTS stg_xlin;
CREATE TEMP TABLE stg_xlin (clave text, numero text, detalle text, cantidad text, unidad text,
    precio_unitario text, monto_total text, sub_total text, base_imponible text,
    impuesto_neto text, monto_total_linea text, codigocabys text);
\copy stg_xlin FROM '/tmp/dbf_reconciliacion/xml_lin.csv' WITH (FORMAT csv, HEADER true)

\echo '===== secuencias'
CREATE TEMP SEQUENCE s_iem;  SELECT setval('s_iem',  coalesce((SELECT max(id) FROM identificacion_emisor), 0));
CREATE TEMP SEQUENCE s_ire;  SELECT setval('s_ire',  coalesce((SELECT max(id) FROM identificacion_receptor), 0));
CREATE TEMP SEQUENCE s_emi;  SELECT setval('s_emi',  coalesce((SELECT max(id) FROM emisor), 0));
CREATE TEMP SEQUENCE s_rec;  SELECT setval('s_rec',  coalesce((SELECT max(id) FROM receptor), 0));
CREATE TEMP SEQUENCE s_enc;  SELECT setval('s_enc',  coalesce((SELECT max(id) FROM encabezado), 0));
CREATE TEMP SEQUENCE s_det;  SELECT setval('s_det',  coalesce((SELECT max(id) FROM detalle_servicio), 0));
CREATE TEMP SEQUENCE s_lin;  SELECT setval('s_lin',  coalesce((SELECT max(id) FROM linea_detalle), 0));
CREATE TEMP SEQUENCE s_res;  SELECT setval('s_res',  coalesce((SELECT max(id) FROM resumen_factura), 0));
CREATE TEMP SEQUENCE s_com;  SELECT setval('s_com',  coalesce((SELECT max(id) FROM comprobantesemitidos), 0));

\echo '===== claves nuevas'
DROP TABLE IF EXISTS x_enc;
CREATE TEMP TABLE x_enc AS
SELECT e.* FROM stg_xenc e
WHERE NOT EXISTS (SELECT 1 FROM encabezado x WHERE x.clave = e.clave);

SELECT 'claves_xml' AS metrica, count(*) AS filas FROM stg_xenc
UNION ALL SELECT 'nuevas', count(*) FROM x_enc
UNION ALL SELECT 'lineas_nuevas', count(*) FROM stg_xlin l JOIN x_enc e ON e.clave = l.clave;

BEGIN;

-- emisor (la empresa): reutiliza por identificación
INSERT INTO identificacion_emisor (id, numero, tipo)
SELECT nextval('s_iem'), p.emisor_numero, '02'
FROM (SELECT DISTINCT ON (emisor_numero) emisor_numero FROM x_enc WHERE coalesce(emisor_numero,'') <> '') p
WHERE NOT EXISTS (SELECT 1 FROM identificacion_emisor i WHERE i.numero = p.emisor_numero);

INSERT INTO emisor (id, nombre, nombre_comercial, identificacion_emisor_id)
SELECT nextval('s_emi'), p.emisor_nombre, p.emisor_nombre, i.id
FROM (SELECT DISTINCT ON (emisor_numero) emisor_numero, emisor_nombre FROM x_enc
      WHERE coalesce(emisor_numero,'') <> '' ORDER BY emisor_numero, emisor_nombre) p
JOIN identificacion_emisor i ON i.numero = p.emisor_numero
WHERE NOT EXISTS (SELECT 1 FROM emisor m WHERE m.identificacion_emisor_id = i.id);

-- receptor (cliente) cuando el XML lo trae identificado
INSERT INTO identificacion_receptor (id, numero, tipo)
SELECT nextval('s_ire'), p.receptor_numero, coalesce(nullif(p.receptor_tipo,''), '01')
FROM (SELECT DISTINCT ON (receptor_numero) receptor_numero, receptor_tipo FROM x_enc
      WHERE coalesce(receptor_numero,'') <> '' ORDER BY receptor_numero) p
WHERE NOT EXISTS (SELECT 1 FROM identificacion_receptor i WHERE i.numero = p.receptor_numero);

INSERT INTO receptor (id, nombre, nombre_comercial, identificacion_receptor_id)
SELECT nextval('s_rec'), p.receptor_nombre, p.receptor_nombre, i.id
FROM (SELECT DISTINCT ON (receptor_numero) receptor_numero, receptor_nombre FROM x_enc
      WHERE coalesce(receptor_numero,'') <> '' ORDER BY receptor_numero, receptor_nombre) p
JOIN identificacion_receptor i ON i.numero = p.receptor_numero
WHERE NOT EXISTS (SELECT 1 FROM receptor m WHERE m.identificacion_receptor_id = i.id);

DROP TABLE IF EXISTS mapa_x;
CREATE TEMP TABLE mapa_x AS
SELECT e.clave, nextval('s_enc') AS enc_id, nextval('s_det') AS det_id,
       nextval('s_res') AS res_id, nextval('s_com') AS com_id
FROM x_enc e;

INSERT INTO encabezado (id, clave, codigo_documento, numero_consecutivo, fecha_emision,
                        condicion_venta, plazo_credito, codigo_actividad_emisor,
                        estado, schemaversion, emisor_id, receptor_id)
SELECT m.enc_id, e.clave, e.codigo_documento, e.consecutivo,
       NULLIF(e.fecha,'')::timestamp, coalesce(nullif(e.condicion_venta,''),'01'),
       NULLIF(e.plazo_credito,''), NULLIF(e.actividad_emisor,''), 'ACEPTADO', '4.4',
       (SELECT em.id FROM identificacion_emisor i2 JOIN emisor em ON em.identificacion_emisor_id = i2.id
         WHERE i2.numero = e.emisor_numero LIMIT 1),
       (SELECT r.id FROM identificacion_receptor i3 JOIN receptor r ON r.identificacion_receptor_id = i3.id
         WHERE i3.numero = e.receptor_numero LIMIT 1)
FROM mapa_x m JOIN x_enc e ON e.clave = m.clave;

INSERT INTO detalle_servicio (id, status) SELECT det_id, true FROM mapa_x;

INSERT INTO resumen_factura (id, codigo_moneda, tipo_cambio_moneda, schemaversion,
    total_comprobante, total_descuentos, total_exento, total_gravado, total_impuesto,
    total_venta, total_venta_neta, total_no_sujeto,
    total_mercancias_gravadas, total_mercancias_exentas,
    total_servicios_gravados, total_servicios_exentos)
SELECT m.res_id, coalesce(nullif(r.moneda,''),'CRC'),
       coalesce(NULLIF(r.tipo_cambio,'')::numeric, 1), '4.4',
       coalesce(NULLIF(r.total_comprobante,'')::numeric, 0),
       coalesce(NULLIF(r.total_descuentos,'')::numeric, 0),
       coalesce(NULLIF(r.total_exento,'')::numeric, 0),
       coalesce(NULLIF(r.total_gravado,'')::numeric, 0),
       coalesce(NULLIF(r.total_impuesto,'')::numeric, 0),
       coalesce(NULLIF(r.total_venta,'')::numeric, 0),
       coalesce(NULLIF(r.total_venta_neta,'')::numeric, 0),
       coalesce(NULLIF(r.total_nosujeto,'')::numeric, 0),
       coalesce(NULLIF(r.merc_gravadas,'')::numeric, 0),
       coalesce(NULLIF(r.merc_exentas,'')::numeric, 0),
       coalesce(NULLIF(r.serv_gravados,'')::numeric, 0),
       coalesce(NULLIF(r.serv_exentos,'')::numeric, 0)
FROM mapa_x m JOIN stg_xres r ON r.clave = m.clave;

INSERT INTO linea_detalle (id, numero_linea, detalle, cantidad, precio_unitario,
    monto_total, monto_total_de_linea, sub_total, base_imponible, impuesto_neto,
    codigocabys, unidad_medida, unidad_medida_comercial, tipo_transaccion,
    schemaversion, detalle_servicio_id)
SELECT nextval('s_lin'), coalesce(NULLIF(l.numero,'')::int, 1), left(l.detalle, 200),
       coalesce(NULLIF(l.cantidad,'')::numeric, 0),
       coalesce(NULLIF(l.precio_unitario,'')::numeric, 0),
       coalesce(NULLIF(l.monto_total,'')::numeric, 0),
       coalesce(NULLIF(l.monto_total_linea,'')::numeric, 0),
       coalesce(NULLIF(l.sub_total,'')::numeric, 0),
       coalesce(NULLIF(l.base_imponible,'')::numeric, 0),
       coalesce(NULLIF(l.impuesto_neto,'')::numeric, 0),
       NULLIF(l.codigocabys,''), coalesce(nullif(l.unidad,''),'Unid'), 'Unidad',
       '01', '4.4', m.det_id
FROM stg_xlin l JOIN mapa_x m ON m.clave = l.clave;

INSERT INTO comprobantesemitidos (id, status, usuario, schemaversion, haciendaclave,
                                  haciendaestado, encabezado_id, detalle_servicio_id, resumen_id)
SELECT m.com_id, true, 'MIGRACION', '4.4', e.clave, 'ACEPTADO', m.enc_id, m.det_id, m.res_id
FROM mapa_x m JOIN x_enc e ON e.clave = m.clave;

-- el FK es circular (detalle -> comprobante -> detalle): se cierra al final
UPDATE detalle_servicio d SET comprobante_emitido_id = m.com_id
FROM mapa_x m WHERE d.id = m.det_id;

\echo '===== totales'
SELECT 'encabezado' AS tabla, count(*) AS filas FROM encabezado
UNION ALL SELECT 'comprobantesemitidos', count(*) FROM comprobantesemitidos
UNION ALL SELECT 'linea_detalle', count(*) FROM linea_detalle;

\if :aplicar
COMMIT;
\echo 'APLICADO';
\else
ROLLBACK;
\echo 'DRY-RUN (rollback)';
\endif
