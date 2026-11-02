-- Completa 2023-2026 con lo que falta respecto de TODAS las copias del snapshot.
--
--   ventas_mod.csv  cabeceras de facturasbk22042024 / facturasenero2026 /
--                   facturas.DBF / facturasdiarias.dbf (snapshot)
--   lineas_mod.csv  lineas de detafact_respaldo / detafactbk27022026 / detafact.dbf
--
-- Reglas:
--   * Un numero de factura se identifica por su parte numerica (la base mezcla
--     '410181', '410181@20250101' y variantes con ceros).
--   * Si hay cabecera real se usa esa (primera aparicion, igual que la migracion
--     original); si no hay, se reconstruye sumando las lineas.
--   * Solo se insertan numeros que no existen en la base.
-- Uso: psql -v aplicar=false (dry-run) | -v aplicar=true (commit)
\pset pager off
\set ON_ERROR_STOP on

\echo '===== staging'
DROP TABLE IF EXISTS stg_vm;
CREATE TEMP TABLE stg_vm (fila text, numero text, fecha text, nombre text, cliente text,
    subtotal text, descuento text, total text, totalexent text, totalnoexe text,
    forma_pago text, estado text, usuario text, origen text);
\copy stg_vm FROM '/tmp/dbf_reconciliacion/ventas_mod.csv' WITH (FORMAT csv, HEADER true)

DROP TABLE IF EXISTS stg_lm;
CREATE TEMP TABLE stg_lm (fila text, factura text, fecha text, codigo text, codigocaby text,
    descrip text, cantidad text, descuento text, valorunita text, total text, familia text,
    exento text, porceimp text, imp text, usuario text, nlinea text, cservicios text, unidad text,
    origen text);
\copy stg_lm FROM '/tmp/dbf_reconciliacion/lineas_mod.csv' WITH (FORMAT csv, HEADER true)

\echo '===== cabeceras reales (una por numero)'
DROP TABLE IF EXISTS h_mod;
CREATE TEMP TABLE h_mod AS
SELECT DISTINCT ON (n) n, numero, fecha, usuario, bruto, descuento, exento, gravado
FROM (
    SELECT (split_part(trim(numero), '@', 1))::numeric AS n,
           trim(numero) AS numero, fecha::date AS fecha,
           coalesce(nullif(trim(usuario), ''), '') AS usuario,
           CASE WHEN coalesce(nullif(trim(total), '')::numeric, 0) > 0
                THEN nullif(trim(total), '')::numeric
                ELSE coalesce(nullif(trim(subtotal), '')::numeric, 0) END AS bruto,
           coalesce(nullif(trim(descuento), '')::numeric, 0) AS descuento,
           coalesce(nullif(trim(totalexent), '')::numeric, 0) AS exento,
           coalesce(nullif(trim(totalnoexe), '')::numeric, 0) AS gravado,
           CASE origen WHEN 'facturas_snap' THEN 1 WHEN 'diarias_snap' THEN 2
                       WHEN 'enero2026' THEN 3 ELSE 4 END AS prio,
           fila::int AS fila
    FROM stg_vm
    WHERE split_part(trim(numero), '@', 1) ~ '^[0-9]+$'
      AND trim(fecha) ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'
) x
ORDER BY n, prio, fila;

\echo '===== lineas (una por factura+numero de linea)'
DROP TABLE IF EXISTS l_mod;
CREATE TEMP TABLE l_mod AS
SELECT DISTINCT ON (n, nlinea)
       n, numero, fecha, codigo, codigocaby, descrip, cantidad, descuento,
       valorunita, total, exento, imp, usuario, nlinea, unidad
FROM (
    SELECT (split_part(trim(factura), '@', 1))::numeric AS n, trim(factura) AS numero,
           fecha::date AS fecha, codigo, codigocaby, descrip,
           abs(coalesce(nullif(trim(cantidad), '')::numeric, 0)) AS cantidad,
           coalesce(nullif(trim(descuento), '')::numeric, 0) AS descuento,
           coalesce(nullif(trim(valorunita), '')::numeric, 0) AS valorunita,
           coalesce(nullif(trim(total), '')::numeric, 0) AS total,
           exento, coalesce(nullif(trim(imp), '')::numeric, 0) AS imp,
           usuario, coalesce(nullif(trim(nlinea), '')::int, 1) AS nlinea, unidad,
           CASE origen WHEN 'detafact.dbf' THEN 1 WHEN 'detafactbk27022026.DBF' THEN 2 ELSE 3 END AS prio,
           fila::int AS fila
    FROM stg_lm
    WHERE split_part(trim(factura), '@', 1) ~ '^[0-9]+$'
      AND trim(fecha) ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'
) y
ORDER BY n, nlinea, prio, fila;

\echo '===== cabeceras reconstruidas de las lineas'
DROP TABLE IF EXISTS h_lin;
CREATE TEMP TABLE h_lin AS
SELECT n, min(numero) AS numero, min(fecha) AS fecha,
       (array_agg(usuario ORDER BY nlinea))[1] AS usuario,
       sum(valorunita * cantidad) AS bruto,
       sum(valorunita * cantidad - total) AS descuento,
       sum(CASE WHEN exento = 'S' THEN total ELSE 0 END) AS exento,
       sum(CASE WHEN exento <> 'S' THEN total ELSE 0 END) AS gravado,
       sum(imp) AS impuesto
FROM l_mod
GROUP BY n;

\echo '===== numeros ya existentes'
DROP TABLE IF EXISTS base_num;
CREATE TEMP TABLE base_num AS
SELECT DISTINCT to_char(fecha_emision, 'YYYY') AS anio,
       split_part(numero_consecutivo, '@', 1)::numeric AS n
FROM encabezado
WHERE fecha_emision >= '2022-01-01'
  AND split_part(numero_consecutivo, '@', 1) ~ '^[0-9]+$';
CREATE INDEX ON base_num (anio, n);
ANALYZE base_num;

\echo '===== ventas nuevas'
DROP TABLE IF EXISTS ventas_nuevas;
CREATE TEMP TABLE ventas_nuevas AS
SELECT * FROM (
    SELECT h.n, split_part(h.numero, '@', 1) AS numero, h.fecha, h.usuario,
           h.bruto, h.descuento, 0::numeric AS impuesto, h.exento, h.gravado
    FROM h_mod h
    UNION ALL
    SELECT r.n, split_part(r.numero, '@', 1), r.fecha, r.usuario,
           r.bruto, r.descuento, r.impuesto, r.exento, r.gravado
    FROM h_lin r
    WHERE NOT EXISTS (SELECT 1 FROM h_mod h WHERE h.n = r.n)
) v
WHERE NOT EXISTS (SELECT 1 FROM base_num b
                  WHERE b.n = v.n AND b.anio = to_char(v.fecha, 'YYYY'));

SELECT to_char(fecha, 'YYYY') AS anio, count(*) AS facturas,
       round(sum(bruto - descuento)::numeric, 0) AS total
FROM ventas_nuevas GROUP BY 1 ORDER BY 1;

\echo '===== lineas de esas ventas'
DROP TABLE IF EXISTS lineas_nuevas;
CREATE TEMP TABLE lineas_nuevas AS
SELECT l.* FROM l_mod l
JOIN ventas_nuevas v ON v.n = l.n;

SELECT to_char(fecha, 'YYYY') AS anio, count(*) AS lineas FROM lineas_nuevas GROUP BY 1 ORDER BY 1;

\echo '===== secuencias'
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
SELECT n, nextval('s_id') AS id FROM ventas_nuevas;

BEGIN;

INSERT INTO encabezado (id, clave, codigo_documento, numero_consecutivo, fecha_emision,
                        condicion_venta, estado, schemaversion, emisor_id, receptor_id)
SELECT m.id, NULL, '04', v.numero, v.fecha, '01', 'ACEPTADO', '4.4', NULL, NULL
FROM mapa_v m JOIN ventas_nuevas v USING (n);

INSERT INTO detalle_servicio (id, status) SELECT id, true FROM mapa_v;

INSERT INTO resumen_factura (id, codigo_moneda, tipo_cambio_moneda, schemaversion,
    total_venta, total_descuentos, total_venta_neta, total_comprobante,
    total_impuesto, total_exento, total_gravado)
SELECT m.id, 'CRC', 1, '4.4', v.bruto, v.descuento,
       v.bruto - v.descuento, v.bruto - v.descuento,
       coalesce(v.impuesto, 0), v.exento, v.gravado
FROM mapa_v m JOIN ventas_nuevas v USING (n);

INSERT INTO comprobantesemitidos (id, status, usuario, schemaversion, haciendaclave,
                                  haciendaestado, encabezado_id, detalle_servicio_id, resumen_id)
SELECT m.id, true, coalesce(nullif(v.usuario, ''), 'MIGRACION'), '4.4', NULL, 'ACEPTADO',
       m.id, m.id, m.id
FROM mapa_v m JOIN ventas_nuevas v USING (n);

INSERT INTO linea_detalle (id, numero_linea, detalle, cantidad, precio_unitario,
    monto_total, monto_total_de_linea, sub_total, base_imponible, impuesto_neto,
    codigocabys, unidad_medida, unidad_medida_comercial, tipo_transaccion,
    schemaversion, detalle_servicio_id)
SELECT nextval('s_lin'), l.nlinea,
       left(coalesce(nullif(l.descrip, ''), a.nombre, l.codigo), 200),
       l.cantidad,
       (l.total - l.imp) / NULLIF(l.cantidad, 0),
       l.total - l.imp,
       l.total,
       l.total - l.imp,
       l.total - l.imp,
       l.imp,
       nullif(l.codigocaby, ''),
       coalesce(nullif(l.unidad, ''), nullif(a.unidadmedida, ''), 'Unid'),
       'Unidad', '01', '4.4', m.id
FROM lineas_nuevas l
JOIN mapa_v m ON m.n = l.n
LEFT JOIN articulos a ON a.codigobarra = l.codigo;

\echo '===== totales'
SELECT 'encabezado' AS tabla, count(*) AS filas FROM encabezado
UNION ALL SELECT 'comprobantesemitidos', count(*) FROM comprobantesemitidos
UNION ALL SELECT 'linea_detalle', count(*) FROM linea_detalle;

SELECT to_char(e.fecha_emision, 'YYYY') AS anio, count(*) AS facturas,
       round(sum(r.total_comprobante)::numeric, 0) AS total
FROM encabezado e
JOIN comprobantesemitidos c ON c.encabezado_id = e.id
JOIN resumen_factura r ON r.id = c.resumen_id
WHERE e.fecha_emision >= '2023-01-01'
GROUP BY 1 ORDER BY 1;

\if :aplicar
COMMIT;
\echo 'APLICADO';
\else
ROLLBACK;
\echo 'DRY-RUN (rollback)';
\endif
