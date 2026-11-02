-- Reconstruye las ventas historicas que faltan en la base.
--
-- Fuentes:
--   ventas_e.csv     cabeceras de E:\VPALMERAS\facturasdiarias.dbf (2013-2019)
--   kardex_e.csv     movimientos de E:\VPALMERAS\kardex.DBF (lineas FE = venta)
--   detafact_2022.csv lineas de detafact_respaldo_respaldo.dbf (solo 2022)
--
-- Que hace:
--   1. Arregla los numeros corruptos de 2015 emparejando por fecha+total+cajero.
--   2. Cabeceras de 2015 que faltan (sep-dic) desde E:.
--   3. Cabeceras de 2016 (ene-feb desde E:, mar-sep reconstruidas del kardex).
--   4. Cabeceras + lineas de 2022 reconstruidas de detafact (no existen en ningun lado).
--   5. Lineas de 2015 (oct-dic) y 2016 desde el kardex FE.
--
-- Idempotente: la clave es `encabezado.numero_consecutivo` (numero con 20 posiciones).
-- Uso: psql -v aplicar=false (dry-run) | -v aplicar=true (commit)
\pset pager off
\set ON_ERROR_STOP on

\echo '===== staging'
DROP TABLE IF EXISTS stg_venta;
CREATE TEMP TABLE stg_venta (numero text, fecha text, nombre text, cliente text,
    subtotal text, descuento text, forma_pago text, estado text, usuario text,
    cajero text, vende text);
\copy stg_venta FROM '/tmp/dbf_reconciliacion/ventas_e.csv' WITH (FORMAT csv, HEADER true)

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

SELECT 'stg_venta' AS tabla, count(*) FROM stg_venta
UNION ALL SELECT 'stg_kardex', count(*) FROM stg_kardex
UNION ALL SELECT 'stg_2022', count(*) FROM stg_2022;

\echo '===== 1. numeros ya existentes (comparacion numerica por anio)'
-- La base guarda los numeros en formatos distintos ('959552', '000...959552',
-- '709162@20131022'), asi que se compara por valor numerico. Se incluye el anio
-- porque hay cabeceras huerfanas con numeros repetidos en otros anios.
DROP TABLE IF EXISTS num_existentes;
CREATE TEMP TABLE num_existentes AS
SELECT DISTINCT to_char(fecha_emision, 'YYYY') AS anio, numero_consecutivo::numeric AS n
FROM encabezado
WHERE numero_consecutivo ~ '^[0-9]+$';
CREATE INDEX ON num_existentes (anio, n);
ANALYZE num_existentes;
SELECT 'numeros_existentes' AS metrica, count(*) AS filas FROM num_existentes;
DROP TABLE IF EXISTS v_e;
CREATE TEMP TABLE v_e AS
SELECT lpad(trim(numero), 20, '0') AS numero_key, trim(numero) AS numero, fecha::date AS fecha,
       coalesce(nullif(trim(usuario), ''), '') AS usuario,
       coalesce(nullif(subtotal, '')::numeric, 0) AS total,
       coalesce(nullif(descuento, '')::numeric, 0) AS descuento,
       0::numeric AS impuesto, NULL::numeric AS exento, NULL::numeric AS gravado,
       'facturasdiarias'::text AS origen
FROM stg_venta
WHERE fecha LIKE '2015%' OR fecha LIKE '2016%';

-- 2015: documentos del kardex que no esten en E:
DROP TABLE IF EXISTS v_k15;
CREATE TEMP TABLE v_k15 AS
SELECT lpad(trim(documento), 20, '0') AS numero_key, trim(documento) AS numero,
       min(fecha)::date AS fecha,
       (array_agg(coalesce(nullif(trim(usuario), ''), '') ORDER BY fecha, codigo))[1] AS usuario,
       sum(abs(coalesce(nullif(cantidad, '')::numeric, 0)) * coalesce(nullif(venta, '')::numeric, 0)) AS total,
       0::numeric AS descuento, 0::numeric AS impuesto,
       NULL::numeric AS exento, NULL::numeric AS gravado,
       'kardex'::text AS origen
FROM stg_kardex
WHERE movimiento = 'FE' AND fecha LIKE '2015%' AND coalesce(trim(documento), '') <> ''
GROUP BY documento;

-- 2016: documentos del kardex (mar-sep) que no esten en E:
DROP TABLE IF EXISTS v_k16;
CREATE TEMP TABLE v_k16 AS
SELECT lpad(trim(documento), 20, '0') AS numero_key, trim(documento) AS numero,
       min(fecha)::date AS fecha,
       (array_agg(coalesce(nullif(trim(usuario), ''), '') ORDER BY fecha, codigo))[1] AS usuario,
       sum(abs(coalesce(nullif(cantidad, '')::numeric, 0)) * coalesce(nullif(venta, '')::numeric, 0)) AS total,
       0::numeric AS descuento, 0::numeric AS impuesto,
       NULL::numeric AS exento, NULL::numeric AS gravado,
       'kardex'::text AS origen
FROM stg_kardex
WHERE movimiento = 'FE' AND fecha LIKE '2016%' AND coalesce(trim(documento), '') <> ''
  AND NOT EXISTS (SELECT 1 FROM v_e x WHERE x.numero_key = lpad(trim(stg_kardex.documento), 20, '0'))
GROUP BY documento;

-- 2022: reconstruido de las lineas de detafact
DROP TABLE IF EXISTS v_22;
CREATE TEMP TABLE v_22 AS
SELECT lpad(trim(factura), 20, '0') AS numero_key, trim(factura) AS numero,
       min(fecha)::date AS fecha,
       (array_agg(coalesce(nullif(trim(usuario), ''), '') ORDER BY nlinea))[1] AS usuario,
       sum(abs(coalesce(nullif(cantidad, '')::numeric, 0)) * coalesce(nullif(valorunita, '')::numeric, 0)) AS total,
       sum(abs(coalesce(nullif(cantidad, '')::numeric, 0)) * coalesce(nullif(valorunita, '')::numeric, 0)
           - coalesce(nullif(total, '')::numeric, 0)) AS descuento,
       sum(coalesce(nullif(imp, '')::numeric, 0)) AS impuesto,
       sum(CASE WHEN exento = 'S' THEN coalesce(nullif(total, '')::numeric, 0) ELSE 0 END) AS exento,
       sum(CASE WHEN exento <> 'S' THEN coalesce(nullif(total, '')::numeric, 0) ELSE 0 END) AS gravado,
       'detafact_2022'::text AS origen
FROM stg_2022
WHERE coalesce(trim(factura), '') <> ''
GROUP BY factura;

SELECT 'v_e' AS origen, count(*) AS filas, count(*) FILTER (WHERE fecha::text LIKE '2015%') AS de_2015,
       count(*) FILTER (WHERE fecha::text LIKE '2016%') AS de_2016 FROM v_e
UNION ALL SELECT 'v_k15', count(*), count(*), 0 FROM v_k15
UNION ALL SELECT 'v_k16', count(*), 0, count(*) FROM v_k16
UNION ALL SELECT 'v_22', count(*), 0, 0 FROM v_22;

\echo '===== 3. ventas realmente nuevas'
DROP TABLE IF EXISTS ventas_nuevas;
CREATE TEMP TABLE ventas_nuevas AS
SELECT DISTINCT ON (numero_key) *
FROM (
    SELECT * FROM v_e
    UNION ALL SELECT * FROM v_k15
    UNION ALL SELECT * FROM v_k16
    UNION ALL SELECT * FROM v_22
) x
WHERE NOT EXISTS (SELECT 1 FROM num_existentes e
                  WHERE e.n = x.numero_key::numeric
                    AND e.anio = to_char(x.fecha, 'YYYY'))
ORDER BY numero_key, CASE origen WHEN 'facturasdiarias' THEN 1 WHEN 'detafact_2022' THEN 2 ELSE 3 END;

SELECT to_char(fecha, 'YYYY') AS anio, origen, count(*) AS filas,
       round(sum(total)::numeric, 0) AS total
FROM ventas_nuevas GROUP BY 1, 2 ORDER BY 1, 2;

\echo '===== 4. lineas candidatas (solo de ventas nuevas)'
DROP TABLE IF EXISTS lineas_nuevas;
CREATE TEMP TABLE lineas_nuevas AS
SELECT lpad(trim(k.documento), 20, '0') AS numero_key, k.documento, k.fecha::date AS fecha,
       k.codigo, NULL::text AS codigocaby, NULL::text AS descrip,
       abs(coalesce(nullif(k.cantidad, '')::numeric, 0)) AS cantidad,
       coalesce(nullif(k.venta, '')::numeric, 0) AS valorunita,
       abs(coalesce(nullif(k.cantidad, '')::numeric, 0)) * coalesce(nullif(k.venta, '')::numeric, 0) AS total,
       NULL::numeric AS imp, NULL::text AS exento, NULL::text AS unidad,
       row_number() OVER (PARTITION BY lpad(trim(k.documento), 20, '0')
                          ORDER BY k.fecha, k.codigo, k.cantidad) AS nlinea
FROM stg_kardex k
JOIN ventas_nuevas v ON v.numero_key = lpad(trim(k.documento), 20, '0')
WHERE k.movimiento = 'FE';

INSERT INTO lineas_nuevas
SELECT lpad(trim(d.factura), 20, '0'), d.factura, d.fecha::date, d.codigo, d.codigocaby,
       d.descrip, abs(coalesce(nullif(d.cantidad, '')::numeric, 0)),
       (coalesce(nullif(d.total, '')::numeric, 0) - coalesce(nullif(d.imp, '')::numeric, 0))
           / NULLIF(abs(coalesce(nullif(d.cantidad, '')::numeric, 0)), 0),
       coalesce(nullif(d.total, '')::numeric, 0), nullif(d.imp, '')::numeric, d.exento, d.unidad,
       coalesce(nullif(d.nlinea, '')::int, 1)
FROM stg_2022 d
JOIN ventas_nuevas v ON v.numero_key = lpad(trim(d.factura), 20, '0');

SELECT 'lineas_nuevas' AS metrica, count(*) AS filas,
       count(DISTINCT numero_key) AS facturas FROM lineas_nuevas;

\echo '===== 5. secuencias'
CREATE TEMP SEQUENCE s_id;
SELECT setval('s_id', GREATEST(
    (SELECT coalesce(max(id), 0) FROM encabezado),
    (SELECT coalesce(max(id), 0) FROM detalle_servicio),
    (SELECT coalesce(max(id), 0) FROM resumen_factura),
    (SELECT coalesce(max(id), 0) FROM comprobantesemitidos)));
CREATE TEMP SEQUENCE s_lin;
SELECT setval('s_lin', (SELECT coalesce(max(id), 0) FROM linea_detalle));

DROP TABLE IF EXISTS mapa_v;
CREATE TEMP TABLE mapa_v AS
SELECT numero_key, nextval('s_id') AS id FROM ventas_nuevas;

BEGIN;

\echo '===== 6. insertar'
INSERT INTO encabezado (id, clave, codigo_documento, numero_consecutivo, fecha_emision,
                        condicion_venta, estado, schemaversion, emisor_id, receptor_id)
SELECT m.id, NULL, '04', v.numero, v.fecha, '01', 'ACEPTADO', '4.4', NULL, NULL
FROM mapa_v m JOIN ventas_nuevas v USING (numero_key);

INSERT INTO detalle_servicio (id, status) SELECT id, true FROM mapa_v;

INSERT INTO resumen_factura (id, codigo_moneda, tipo_cambio_moneda, schemaversion,
    total_venta, total_descuentos, total_venta_neta, total_comprobante,
    total_impuesto, total_exento, total_gravado)
SELECT m.id, 'CRC', 1, '4.4', v.total, v.descuento,
       v.total - v.descuento, v.total - v.descuento,
       coalesce(v.impuesto, 0), v.exento, v.gravado
FROM mapa_v m JOIN ventas_nuevas v USING (numero_key);

INSERT INTO comprobantesemitidos (id, status, usuario, schemaversion, haciendaclave,
                                  haciendaestado, encabezado_id, detalle_servicio_id, resumen_id)
SELECT m.id, true,
       coalesce(nullif(v.usuario, ''), 'MIGRACION'), '4.4', NULL, 'ACEPTADO',
       m.id, m.id, m.id
FROM mapa_v m JOIN ventas_nuevas v USING (numero_key);

INSERT INTO linea_detalle (id, numero_linea, detalle, cantidad, precio_unitario,
    monto_total, monto_total_de_linea, sub_total, base_imponible, impuesto_neto,
    codigocabys, unidad_medida, unidad_medida_comercial, tipo_transaccion,
    schemaversion, detalle_servicio_id)
SELECT nextval('s_lin'), l.nlinea,
       left(coalesce(nullif(l.descrip, ''), a.nombre, l.codigo), 200),
       l.cantidad, l.valorunita,
       l.valorunita * l.cantidad,
       l.total,
       l.valorunita * l.cantidad,
       l.valorunita * l.cantidad,
       coalesce(l.imp, 0),
       nullif(l.codigocaby, ''),
       coalesce(nullif(l.unidad, ''), nullif(a.unidadmedida, ''), 'Unid'),
       'Unidad', '01', '4.4', m.id
FROM lineas_nuevas l
JOIN mapa_v m ON m.numero_key = l.numero_key
LEFT JOIN articulos a ON a.codigobarra = l.codigo;

\echo '===== totales'
SELECT 'encabezado' AS tabla, count(*) AS filas FROM encabezado
UNION ALL SELECT 'comprobantesemitidos', count(*) FROM comprobantesemitidos
UNION ALL SELECT 'linea_detalle', count(*) FROM linea_detalle
UNION ALL SELECT 'resumen_factura', count(*) FROM resumen_factura
UNION ALL SELECT 'detalle_servicio', count(*) FROM detalle_servicio;

SELECT to_char(e.fecha_emision, 'YYYY') AS anio, count(*) AS facturas,
       round(sum(r.total_comprobante)::numeric, 0) AS total
FROM encabezado e
JOIN comprobantesemitidos c ON c.encabezado_id = e.id
JOIN resumen_factura r ON r.id = c.resumen_id
WHERE e.fecha_emision >= '2015-01-01' AND e.fecha_emision < '2023-01-01'
GROUP BY 1 ORDER BY 1;

\if :aplicar
COMMIT;
\echo 'APLICADO';
\else
ROLLBACK;
\echo 'DRY-RUN (rollback)';
\endif
