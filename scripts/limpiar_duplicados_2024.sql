-- Borra los duplicados de 2024 identificados en la primera pasada.
-- 2024 era el unico anio con exceso real contra las lineas (+14.370 unidades).
-- El respaldo completo esta en inventario_bkp_dup_venta_2026.
--
-- Uso: psql -v aplicar=false|true -f limpiar_duplicados_2024.sql

\pset pager off
\set ON_ERROR_STOP on

SELECT 'a_borrar' AS metrica, count(*)::text AS valor,
       round(sum(i.cantidad)::numeric, 0)::text AS cantidad
FROM inventario i
JOIN inventario_bkp_dup_venta_2026 b ON b.codigo = i.codigo
WHERE b.fechamovimiento >= '2024-01-01' AND b.fechamovimiento < '2025-01-01';

BEGIN;

DELETE FROM inventario i
USING inventario_bkp_dup_venta_2026 b
WHERE i.codigo = b.codigo
  AND b.fechamovimiento >= '2024-01-01' AND b.fechamovimiento < '2025-01-01';

\echo '===== despues (2024)'
SELECT count(*) AS ventas, round(sum(cantidad)::numeric, 0) AS cantidad
FROM inventario
WHERE tipomovimiento = 'Venta'
  AND fechamovimiento >= '2024-01-01' AND fechamovimiento < '2025-01-01';

\if :aplicar
COMMIT;
\echo 'APLICADO';
\else
ROLLBACK;
\echo 'DRY-RUN (rollback)';
\endif
