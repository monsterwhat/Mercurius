-- Reconciliación vSuper DBF -> base viva (SOLO LECTURA).
-- Carga los CSV en tablas temporales y compara por claves naturales.
\pset pager off
\set ON_ERROR_STOP on

DROP TABLE IF EXISTS stg_maestro;
CREATE TEMP TABLE stg_maestro (codigo text, nombre text, codigocaby text, familia text,
    cantidad text, costo text, margenutil text, venta text, exento text, porceimp text,
    ultimaentr text, ultimasali text, stockmin text, stockmax text);
\copy stg_maestro FROM '/tmp/dbf_reconciliacion/maestro.csv' WITH (FORMAT csv, HEADER true)

DROP TABLE IF EXISTS stg_kardex;
CREATE TEMP TABLE stg_kardex (codigo text, fecha text, movimiento text, documento text,
    cantidad text, usuario text, costo text, venta text, tipodocume text, control text,
    proveedor text, saldo text);
\copy stg_kardex FROM '/tmp/dbf_reconciliacion/kardex.csv' WITH (FORMAT csv, HEADER true)

DROP TABLE IF EXISTS stg_entradas;
CREATE TEMP TABLE stg_entradas (codigo text, fecha text, documento text, cantidad text,
    costo text, prov text, codprov text, detalle text, usuario text);
\copy stg_entradas FROM '/tmp/dbf_reconciliacion/entradas.csv' WITH (FORMAT csv, HEADER true)

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

\echo '===== ARTICULOS'
SELECT 'fuente' AS metrica, count(*) AS filas FROM stg_maestro
UNION ALL SELECT 'sin_match_en_bd', count(*) FROM stg_maestro m
    LEFT JOIN articulos a ON a.codigobarra = m.codigo WHERE a.codigo IS NULL
UNION ALL SELECT 'con_fila_de_stock', count(*) FROM stg_maestro m
    JOIN articulos a ON a.codigobarra = m.codigo
    JOIN articulostock s ON s.codigo_barra = a.codigobarra
UNION ALL SELECT 'sin_fila_de_stock', count(*) FROM stg_maestro m
    JOIN articulos a ON a.codigobarra = m.codigo
    LEFT JOIN articulostock s ON s.codigo_barra = a.codigobarra WHERE s.id IS NULL
UNION ALL SELECT 'sin_cabys_en_origen', count(*) FROM stg_maestro WHERE coalesce(codigocaby,'') = '';

\echo '===== MOVIMIENTOS (KARDEX)'
DROP TABLE IF EXISTS inv_keys;
CREATE TEMP TABLE inv_keys AS
SELECT articulo_codigo, fechamovimiento::date AS f, cantidad, coalesce(notas,'') AS doc, tipomovimiento
FROM inventario;
CREATE INDEX ON inv_keys (articulo_codigo, f);

DROP TABLE IF EXISTS kx;
CREATE TEMP TABLE kx AS
SELECT a.codigo AS art, NULLIF(k.fecha,'')::date AS f, NULLIF(k.cantidad,'')::numeric AS cant,
       coalesce(k.documento,'') AS doc,
       CASE WHEN k.movimiento='E' THEN 'ENTRADA' WHEN k.movimiento='S' THEN 'SALIDA' ELSE 'MOVIMIENTO' END AS tipo
FROM stg_kardex k JOIN articulos a ON a.codigobarra = k.codigo
WHERE NULLIF(k.fecha,'') IS NOT NULL;

SELECT 'fuente' AS metrica, count(*) AS filas FROM stg_kardex
UNION ALL SELECT 'articulo_no_existe', count(*) FROM stg_kardex k
    LEFT JOIN articulos a ON a.codigobarra = k.codigo WHERE a.codigo IS NULL
UNION ALL SELECT 'sin_fecha_valida', count(*) FROM stg_kardex WHERE NULLIF(fecha,'') IS NULL
UNION ALL SELECT 'ya_en_bd', count(*) FROM kx s
    WHERE EXISTS (SELECT 1 FROM inv_keys i WHERE i.articulo_codigo=s.art AND i.f=s.f
                  AND i.cantidad=s.cant AND i.doc=s.doc AND i.tipomovimiento=s.tipo)
UNION ALL SELECT 'faltantes', count(*) FROM kx s
    WHERE NOT EXISTS (SELECT 1 FROM inv_keys i WHERE i.articulo_codigo=s.art AND i.f=s.f
                  AND i.cantidad=s.cant AND i.doc=s.doc AND i.tipomovimiento=s.tipo);

\echo '===== MOVIMIENTOS (ENTRADAS)'
DROP TABLE IF EXISTS ex;
CREATE TEMP TABLE ex AS
SELECT a.codigo AS art, NULLIF(e.fecha,'')::date AS f, NULLIF(e.cantidad,'')::numeric AS cant,
       coalesce(e.documento,'') AS doc, 'ENTRADA' AS tipo
FROM stg_entradas e JOIN articulos a ON a.codigobarra = e.codigo
WHERE NULLIF(e.fecha,'') IS NOT NULL;

SELECT 'fuente' AS metrica, count(*) AS filas FROM stg_entradas
UNION ALL SELECT 'articulo_no_existe', count(*) FROM stg_entradas e
    LEFT JOIN articulos a ON a.codigobarra = e.codigo WHERE a.codigo IS NULL
UNION ALL SELECT 'ya_en_bd', count(*) FROM ex s
    WHERE EXISTS (SELECT 1 FROM inv_keys i WHERE i.articulo_codigo=s.art AND i.f=s.f
                  AND i.cantidad=s.cant AND i.doc=s.doc AND i.tipomovimiento=s.tipo)
UNION ALL SELECT 'faltantes', count(*) FROM ex s
    WHERE NOT EXISTS (SELECT 1 FROM inv_keys i WHERE i.articulo_codigo=s.art AND i.f=s.f
                  AND i.cantidad=s.cant AND i.doc=s.doc AND i.tipomovimiento=s.tipo);

\echo '===== FACTURAS DE COMPRA ELECTRONICAS (clave Hacienda)'
SELECT 'encabezados_fuente' AS metrica, count(*) AS filas FROM stg_fe_enc
UNION ALL SELECT 'claves_ya_en_encabezado', count(*) FROM stg_fe_enc e
    WHERE EXISTS (SELECT 1 FROM encabezado x WHERE x.clave = e.numerofact)
UNION ALL SELECT 'claves_faltantes', count(*) FROM stg_fe_enc e
    WHERE NOT EXISTS (SELECT 1 FROM encabezado x WHERE x.clave = e.numerofact)
UNION ALL SELECT 'claves_con_comprobante_recibido', count(*) FROM stg_fe_enc e
    JOIN encabezado x ON x.clave = e.numerofact
    JOIN comprobantesrecibidos cr ON cr.encabezado_id = x.id
UNION ALL SELECT 'lineas_fuente', count(*) FROM stg_fe_det
UNION ALL SELECT 'lineas_de_claves_ya_importadas', count(*) FROM stg_fe_det d
    WHERE EXISTS (SELECT 1 FROM encabezado x WHERE x.clave = d.numerofact);

\echo '===== FACTURAS DE COMPRA LEGADAS (cuentas por pagar, sin clave)'
SELECT 'fuente' AS metrica, count(*) AS filas FROM stg_compras
UNION ALL SELECT 'con_estado_cancelado', count(*) FROM stg_compras WHERE upper(estado) = 'C';

\echo '===== MUESTRA DE FALTANTES'
SELECT m.codigo, m.nombre FROM stg_maestro m
LEFT JOIN articulos a ON a.codigobarra = m.codigo WHERE a.codigo IS NULL LIMIT 5;
SELECT e.numerofact, e.nombreemis, e.totaldoc FROM stg_fe_enc e
WHERE NOT EXISTS (SELECT 1 FROM encabezado x WHERE x.clave = e.numerofact) LIMIT 5;
