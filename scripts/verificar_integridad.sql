-- Verificación de integridad de los datos de facturación.
--
-- Corre después de cada importación (tarea diaria) y también a mano.
-- Sale con código ≠ 0 si algo está mal: psql con ON_ERROR_STOP aborta en el
-- primer RAISE EXCEPTION.
--
-- Ventana: desde/hasta (por defecto, los últimos 2 días).
--   psql -d mercurius -v desde=2026-11-15 -v hasta=2026-11-18 -f verificar_integridad.sql
--
-- Falla (hard):
--   1. neta + iva <> total
--   2. comprobante electrónico sin resumen
--   3. comprobante con total > 0 sin líneas
--   4. resumen con IVA en cero pero líneas con IVA
--
-- Avisa (soft, no aborta):
--   5. IVA del resumen distinto de la suma de líneas
--   6. comprobantes sin clave de Hacienda
--   7. líneas sin artículo
--   8. movimientos de venta repetidos del mismo documento

\pset pager off
\set ON_ERROR_STOP on

\if :{?desde}
\else
\set desde `date -d '2 days ago' +%Y-%m-%d`
\endif
\if :{?hasta}
\else
\set hasta `date -d 'tomorrow' +%Y-%m-%d`
\endif

\echo '===== ventana'
SELECT :'desde'::date AS desde, :'hasta'::date AS hasta;

-- Se materializa la ventana con literales para que el planner use el índice de
-- fecha; si no, escanea los 1,15 M de comprobantes.
DROP TABLE IF EXISTS pg_temp.v_ids;
CREATE TEMP TABLE v_ids AS
SELECT c.id, c.detalle_servicio_id, c.resumen_id, c.haciendaclave,
       h.numero_consecutivo, h.clave, h.fecha_emision
FROM comprobantesemitidos c
JOIN encabezado h ON h.id = c.encabezado_id
WHERE h.fecha_emision >= :'desde'::date AND h.fecha_emision < :'hasta'::date;

DROP TABLE IF EXISTS pg_temp.v_lineas;
CREATE TEMP TABLE v_lineas AS
SELECT l.id, l.detalle_servicio_id, coalesce(l.impuesto_neto, 0) AS impuesto_neto,
       EXISTS (SELECT 1 FROM codigo_comercial cc
               JOIN articulos a ON a.codigobarra = cc.codigo
               WHERE cc.linea_detalle_id = l.id) AS tiene_articulo
FROM linea_detalle l
WHERE l.detalle_servicio_id IN (SELECT detalle_servicio_id FROM v_ids);

DROP TABLE IF EXISTS pg_temp.v_movs;
CREATE TEMP TABLE v_movs AS
SELECT i.codigo, i.notas, i.articulo_codigo, i.cantidad
FROM inventario i
WHERE i.tipomovimiento = 'Venta'
  AND i.fechamovimiento >= :'desde'::date AND i.fechamovimiento < :'hasta'::date;

\echo '===== avisos (no abortan)'
SELECT 'comprobantes' AS metrica, count(*)::text AS valor FROM v_ids
UNION ALL SELECT 'lineas', count(*)::text FROM v_lineas
UNION ALL SELECT 'iva_resumen_vs_lineas', count(*)::text
    FROM v_ids i
    JOIN resumen_factura r ON r.id = i.resumen_id
    WHERE abs(coalesce(r.total_impuesto,0)
              - coalesce((SELECT sum(impuesto_neto) FROM v_lineas l
                          WHERE l.detalle_servicio_id = i.detalle_servicio_id), 0)) > 1
UNION ALL SELECT 'sin_clave', count(*)::text FROM v_ids WHERE clave IS NULL
UNION ALL SELECT 'lineas_sin_articulo', count(*)::text FROM v_lineas WHERE NOT tiene_articulo
UNION ALL SELECT 'movimientos_repetidos', coalesce(sum(n - 1), 0)::text
    FROM (SELECT count(*) AS n FROM v_movs
          WHERE notas IS DISTINCT FROM 'Articulo Vendido'
          GROUP BY notas, articulo_codigo, cantidad HAVING count(*) > 1) t;

-- ---------- fallas ----------
DO $$
DECLARE n int; detalle text;
BEGIN
    SELECT count(*), string_agg(numero_consecutivo, ', ' ORDER BY numero_consecutivo)
    INTO n, detalle
    FROM (
        SELECT i.numero_consecutivo
        FROM v_ids i
        JOIN resumen_factura r ON r.id = i.resumen_id
        WHERE abs(coalesce(r.total_venta_neta,0) + coalesce(r.total_impuesto,0)
                  - coalesce(r.total_comprobante,0)) > 0.01
        LIMIT 20
    ) t;
    IF n > 0 THEN
        RAISE EXCEPTION 'INTEGRIDAD 1: % comprobantes con neta + IVA <> total (%)', n, detalle;
    END IF;
END $$;

DO $$
DECLARE n int; detalle text;
BEGIN
    SELECT count(*), string_agg(numero_consecutivo, ', ' ORDER BY numero_consecutivo)
    INTO n, detalle
    FROM (
        SELECT numero_consecutivo FROM v_ids
        WHERE resumen_id IS NULL AND haciendaclave IS NOT NULL
        LIMIT 20
    ) t;
    IF n > 0 THEN
        RAISE EXCEPTION 'INTEGRIDAD 2: % comprobantes electronicos sin resumen (%)', n, detalle;
    END IF;
END $$;

DO $$
DECLARE n int; detalle text;
BEGIN
    SELECT count(*), string_agg(numero_consecutivo, ', ' ORDER BY numero_consecutivo)
    INTO n, detalle
    FROM (
        SELECT i.numero_consecutivo
        FROM v_ids i
        LEFT JOIN resumen_factura r ON r.id = i.resumen_id
        WHERE coalesce(r.total_comprobante, 0) > 0
          AND NOT EXISTS (SELECT 1 FROM v_lineas l
                          WHERE l.detalle_servicio_id = i.detalle_servicio_id)
        LIMIT 20
    ) t;
    IF n > 0 THEN
        RAISE EXCEPTION 'INTEGRIDAD 3: % comprobantes con total y sin lineas (%)', n, detalle;
    END IF;
END $$;

DO $$
DECLARE n int; detalle text;
BEGIN
    SELECT count(*), string_agg(numero_consecutivo, ', ' ORDER BY numero_consecutivo)
    INTO n, detalle
    FROM (
        SELECT i.numero_consecutivo
        FROM v_ids i
        JOIN resumen_factura r ON r.id = i.resumen_id
        WHERE coalesce(r.total_impuesto, 0) = 0
          AND coalesce((SELECT sum(impuesto_neto) FROM v_lineas l
                        WHERE l.detalle_servicio_id = i.detalle_servicio_id), 0) > 0
        LIMIT 20
    ) t;
    IF n > 0 THEN
        RAISE EXCEPTION 'INTEGRIDAD 4: % resumenes con IVA en cero y lineas con IVA (%)', n, detalle;
    END IF;
END $$;

\echo 'INTEGRIDAD OK';
