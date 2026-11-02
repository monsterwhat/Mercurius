-- Inserta las 43 lineas de 2018 que quedaron en E:\VPALMERAS\DETAFACT.DBF.
-- Solo aplica a facturas que existen y no tienen detalle.
-- Uso: psql -v aplicar=false|true
\pset pager off
\set ON_ERROR_STOP on

DROP TABLE IF EXISTS stg_l18;
CREATE TEMP TABLE stg_l18 (factura text, fecha text, codigo text, codigocaby text, descrip text,
    cantidad text, valorunita text, total text, exento text, imp text, nlinea text, unidad text,
    usuario text);
\copy stg_l18 FROM '/tmp/dbf_reconciliacion/lineas_2018_e.csv' WITH (FORMAT csv, HEADER true)

DROP TABLE IF EXISTS objetivo;
CREATE TEMP TABLE objetivo AS
SELECT DISTINCT ON (e.id) e.id, c.detalle_servicio_id, l.factura
FROM encabezado e
JOIN comprobantesemitidos c ON c.encabezado_id = e.id
JOIN stg_l18 l ON l.factura = trim(e.numero_consecutivo)
WHERE NOT EXISTS (SELECT 1 FROM linea_detalle x WHERE x.detalle_servicio_id = c.detalle_servicio_id);

SELECT 'facturas_objetivo' AS metrica, count(*) AS filas FROM objetivo;
SELECT 'lineas' AS metrica, count(*) AS filas FROM stg_l18 l JOIN objetivo o ON o.factura = l.factura;

CREATE TEMP SEQUENCE s_lin;
SELECT setval('s_lin', (SELECT coalesce(max(id), 0) FROM linea_detalle));

BEGIN;

INSERT INTO linea_detalle (id, numero_linea, detalle, cantidad, precio_unitario,
    monto_total, monto_total_de_linea, sub_total, base_imponible, impuesto_neto,
    codigocabys, unidad_medida, unidad_medida_comercial, tipo_transaccion,
    schemaversion, detalle_servicio_id)
SELECT nextval('s_lin'), coalesce(nullif(trim(l.nlinea), '')::int, 1),
       left(coalesce(nullif(l.descrip, ''), a.nombre, l.codigo), 200),
       abs(coalesce(nullif(trim(l.cantidad), '')::numeric, 0)),
       (coalesce(nullif(trim(l.total), '')::numeric, 0) - coalesce(nullif(trim(l.imp), '')::numeric, 0))
           / NULLIF(abs(coalesce(nullif(trim(l.cantidad), '')::numeric, 0)), 0),
       coalesce(nullif(trim(l.total), '')::numeric, 0) - coalesce(nullif(trim(l.imp), '')::numeric, 0),
       coalesce(nullif(trim(l.total), '')::numeric, 0),
       coalesce(nullif(trim(l.total), '')::numeric, 0) - coalesce(nullif(trim(l.imp), '')::numeric, 0),
       coalesce(nullif(trim(l.total), '')::numeric, 0) - coalesce(nullif(trim(l.imp), '')::numeric, 0),
       coalesce(nullif(trim(l.imp), '')::numeric, 0),
       nullif(l.codigocaby, ''),
       coalesce(nullif(l.unidad, ''), nullif(a.unidadmedida, ''), 'Unid'),
       'Unidad', '01', '4.4', o.detalle_servicio_id
FROM stg_l18 l
JOIN objetivo o ON o.factura = l.factura
LEFT JOIN articulos a ON a.codigobarra = l.codigo;

SELECT 'linea_detalle' AS tabla, count(*) AS filas FROM linea_detalle;

\if :aplicar
COMMIT;
\echo 'APLICADO';
\else
ROLLBACK;
\echo 'DRY-RUN (rollback)';
\endif
