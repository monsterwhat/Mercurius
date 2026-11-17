-- Genera los movimientos 'Venta' que faltan a partir de las lineas de cada
-- factura (2015-2026).
--
-- Por que: el import historico creo los movimientos desde el kardex, no desde
-- las lineas. En varios anios quedaron facturas con detalle y sin movimiento.
--
-- Logica: por articulo y fecha se compara el total vendido segun las lineas con
-- el total ya registrado como 'Venta' y se inserta solo la diferencia. Asi el
-- script es idempotente y se corrige solo si se corre dos veces.
--
-- Uso: psql -v aplicar=false|true -f completar_movimientos_desde_lineas.sql

\pset pager off
\set ON_ERROR_STOP on

DROP TABLE IF EXISTS lin_total;
CREATE TEMP TABLE lin_total AS
SELECT a.codigo AS articulo,
       h.fecha_emision::date AS fecha,
       sum(l.cantidad) AS cantidad,
       max(c.usuario) AS usuario,
       min(h.numero_consecutivo) AS numero
FROM linea_detalle l
JOIN comprobantesemitidos c ON c.detalle_servicio_id = l.detalle_servicio_id
JOIN encabezado h ON h.id = c.encabezado_id
JOIN codigo_comercial cc ON cc.linea_detalle_id = l.id
JOIN articulos a ON a.codigobarra = cc.codigo
WHERE h.fecha_emision >= '2015-01-01' AND h.fecha_emision < '2027-01-01'
  AND l.cantidad > 0
GROUP BY a.codigo, h.fecha_emision::date;

DROP TABLE IF EXISTS lin_falta;
CREATE TEMP TABLE lin_falta AS
SELECT t.articulo, t.fecha, t.usuario, t.numero,
       t.cantidad - coalesce(m.cantidad, 0) AS cantidad
FROM lin_total t
LEFT JOIN (
    SELECT articulo_codigo AS articulo, fechamovimiento::date AS fecha, sum(-cantidad) AS cantidad
    FROM inventario
    WHERE tipomovimiento = 'Venta'
    GROUP BY 1, 2
) m ON m.articulo = t.articulo AND m.fecha = t.fecha
WHERE t.cantidad - coalesce(m.cantidad, 0) > 0;

SELECT to_char(fecha, 'YYYY') AS anio, count(*) AS movimientos,
       round(sum(cantidad)::numeric, 2) AS cantidad
FROM lin_falta GROUP BY 1 ORDER BY 1;

BEGIN;

CREATE TEMP SEQUENCE s_lin;
SELECT setval('s_lin', coalesce((SELECT max(codigo) FROM inventario), 0));

INSERT INTO inventario (codigo, cantidad, fechamovimiento, notas, processed, status,
                        tipomovimiento, articulo_codigo, usuario_id)
SELECT nextval('s_lin'),
       -f.cantidad,
       f.fecha,
       'Venta ' || f.numero || ' (desde lineas)',
       true, true, 'Venta', f.articulo,
       (SELECT u.id FROM users u WHERE u.username = f.usuario LIMIT 1)
FROM lin_falta f;

SELECT setval('inventario_codigo_seq', (SELECT coalesce(max(codigo), 1) FROM inventario));

\if :aplicar
COMMIT;
\echo 'APLICADO';
\else
ROLLBACK;
\echo 'DRY-RUN (rollback)';
\endif
