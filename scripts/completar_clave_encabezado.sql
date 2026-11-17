-- Completa encabezado.clave desde comprobantesemitidos.haciendaclave.
--
-- El import historico lleno haciendaclave (que viene del CIDDOCUMEN del sistema
-- viejo) pero no encabezado.clave. La pagina de declaraciones (D-104) filtra
-- encabezado.clave IS NOT NULL, por eso los meses aparecian vacios.
--
-- Solo completa los que estan en NULL; no toca los que ya tienen valor.
-- Respaldo en encabezado_bkp_clave_2026 (id, clave previa).
--
-- Uso: psql -v aplicar=false|true -f completar_clave_encabezado.sql

\pset pager off
\set ON_ERROR_STOP on

SELECT to_char(h.fecha_emision, 'YYYY') AS anio,
       count(*) FILTER (WHERE h.clave IS NULL AND c.haciendaclave IS NOT NULL) AS a_completar
FROM comprobantesemitidos c
JOIN encabezado h ON h.id = c.encabezado_id
GROUP BY 1 ORDER BY 1;

BEGIN;

CREATE TABLE IF NOT EXISTS encabezado_bkp_clave_2026 (id bigint PRIMARY KEY, clave text);

INSERT INTO encabezado_bkp_clave_2026 (id, clave)
SELECT h.id, h.clave
FROM encabezado h
JOIN comprobantesemitidos c ON c.encabezado_id = h.id
WHERE h.clave IS NULL AND c.haciendaclave IS NOT NULL
ON CONFLICT (id) DO NOTHING;

UPDATE encabezado h
SET clave = c.haciendaclave
FROM comprobantesemitidos c
WHERE c.encabezado_id = h.id
  AND h.clave IS NULL
  AND c.haciendaclave IS NOT NULL;

\echo '===== despues: D-104 por mes (solo con clave)'
SELECT to_char(h.fecha_emision,'YYYY-MM') AS mes,
       count(*) FILTER (WHERE h.clave IS NOT NULL) AS comprobantes,
       round(sum(r.total_venta_neta) FILTER (WHERE h.clave IS NOT NULL)::numeric, 2) AS neta,
       round(sum(r.total_impuesto) FILTER (WHERE h.clave IS NOT NULL)::numeric, 2) AS iva
FROM comprobantesemitidos c
JOIN encabezado h ON h.id = c.encabezado_id
LEFT JOIN resumen_factura r ON r.id = c.resumen_id
WHERE h.fecha_emision >= '2026-01-01' AND h.fecha_emision < '2027-01-01'
GROUP BY 1 ORDER BY 1;

\if :aplicar
COMMIT;
\echo 'APLICADO';
\else
ROLLBACK;
\echo 'DRY-RUN (rollback)';
\endif
