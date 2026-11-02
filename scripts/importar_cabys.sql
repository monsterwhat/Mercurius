-- Asigna el CAByS a los articulos que no lo tienen, usando el maestro viejo
-- (maestrocaby.DBF: 53 317 filas, 43 209 codigos, 1 783 CAByS).
-- Solo asigna codigos que existen en la tabla `cabys` (hay FK).
-- Uso: psql -v aplicar=false|true
\pset pager off
\set ON_ERROR_STOP on

DROP TABLE IF EXISTS mc;
CREATE TEMP TABLE mc (codigo text, codigocaby text, nombre text);
\copy mc FROM '/tmp/dbf_reconciliacion/maestrocaby.csv' WITH (FORMAT csv, HEADER true)

DROP TABLE IF EXISTS mapa_mc;
CREATE TEMP TABLE mapa_mc AS
SELECT DISTINCT ON (codigo) codigo, codigocaby
FROM mc
WHERE coalesce(codigocaby, '') <> ''
  AND EXISTS (SELECT 1 FROM cabys c WHERE c.codigo = mc.codigocaby)
ORDER BY codigo, codigocaby;

SELECT 'mapa' AS metrica, count(*) AS filas FROM mapa_mc
UNION ALL SELECT 'sin_cabys_antes', count(*) FROM articulos WHERE coalesce(cabys_id, '') = ''
UNION ALL SELECT 'a_asignar', count(*) FROM articulos a
  JOIN mapa_mc m ON m.codigo = a.codigobarra
  WHERE coalesce(a.cabys_id, '') = '';

BEGIN;

UPDATE articulos a
SET cabys_id = m.codigocaby
FROM mapa_mc m
WHERE m.codigo = a.codigobarra
  AND coalesce(a.cabys_id, '') = '';

SELECT 'sin_cabys_despues' AS metrica, count(*) AS filas FROM articulos WHERE coalesce(cabys_id, '') = '';

\if :aplicar
COMMIT;
\echo 'APLICADO';
\else
ROLLBACK;
\echo 'DRY-RUN (rollback)';
\endif
