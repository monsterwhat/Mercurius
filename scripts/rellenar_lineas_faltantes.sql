-- Rellena el detalle de facturas que YA existen en la base pero quedaron sin lineas
-- (la migracion vieja cargo la cabecera y no el detalle).
--
-- Fuente: lineas_mod.csv (detafact 2023-2026 del snapshot).
-- Solo toca facturas con CERO lineas, asi que es idempotente.
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

DROP TABLE IF EXISTS l_mod;
CREATE TEMP TABLE l_mod AS
SELECT DISTINCT ON (anio, n, nlinea)
       anio, n, codigo, codigocaby, descrip, cantidad, valorunita, total, imp, nlinea, unidad
FROM (
    SELECT to_char(fecha::date, 'YYYY') AS anio,
           (split_part(trim(factura), '@', 1))::numeric AS n,
           codigo, codigocaby, descrip,
           abs(coalesce(nullif(trim(cantidad), '')::numeric, 0)) AS cantidad,
           coalesce(nullif(trim(valorunita), '')::numeric, 0) AS valorunita,
           coalesce(nullif(trim(total), '')::numeric, 0) AS total,
           coalesce(nullif(trim(imp), '')::numeric, 0) AS imp,
           coalesce(nullif(trim(nlinea), '')::int, 1) AS nlinea, unidad,
           CASE origen WHEN 'detafact.dbf' THEN 1 WHEN 'detafactbk27022026.DBF' THEN 2 ELSE 3 END AS prio,
           fila::int AS fila
    FROM stg_lm
    WHERE split_part(trim(factura), '@', 1) ~ '^[0-9]+$'
      AND trim(fecha) ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'
) y
ORDER BY anio, n, nlinea, prio, fila;

\echo '===== facturas sin lineas que si tienen fuente'
DROP TABLE IF EXISTS objetivo;
CREATE TEMP TABLE objetivo AS
SELECT DISTINCT ON (e.id) e.id, c.detalle_servicio_id, l.n, l.anio
FROM encabezado e
JOIN comprobantesemitidos c ON c.encabezado_id = e.id
JOIN l_mod l ON l.n = (split_part(trim(e.numero_consecutivo), '@', 1))::numeric
            AND l.anio = to_char(e.fecha_emision, 'YYYY')
WHERE trim(split_part(e.numero_consecutivo, '@', 1)) ~ '^[0-9]+$'
  AND NOT EXISTS (SELECT 1 FROM linea_detalle x WHERE x.detalle_servicio_id = c.detalle_servicio_id);

SELECT to_char(e.fecha_emision, 'YYYY') AS anio, count(*) AS facturas
FROM objetivo o JOIN encabezado e ON e.id = o.id
GROUP BY 1 ORDER BY 1;

SELECT 'lineas_a_insertar' AS metrica, count(*) AS filas
FROM l_mod l JOIN objetivo o ON o.n = l.n AND o.anio = l.anio;

\echo '===== secuencia'
CREATE TEMP SEQUENCE s_lin;
SELECT setval('s_lin', (SELECT coalesce(max(id), 0) FROM linea_detalle));

BEGIN;

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
       'Unidad', '01', '4.4', o.detalle_servicio_id
FROM l_mod l
JOIN objetivo o ON o.n = l.n AND o.anio = l.anio
LEFT JOIN articulos a ON a.codigobarra = l.codigo;

\echo '===== totales'
SELECT 'linea_detalle' AS tabla, count(*) AS filas FROM linea_detalle;

\if :aplicar
COMMIT;
\echo 'APLICADO';
\else
ROLLBACK;
\echo 'DRY-RUN (rollback)';
\endif
