-- 2026-11-13
-- El código de barras de un artículo debe ser único. El alta/edición ya lo
-- valida en servidor (ArticulosService.existeCodigoBarra) y findByBarCode ya
-- es determinista (ORDER BY codigo ASC), pero la base seguía aceptando
-- duplicados por concurrencia o por datos legados.
--
-- PASO 1: reportar duplicados. Si devuelve filas, resolverlos ANTES del paso 2
-- (reasignar el código a uno de los artículos o dejar el duplicado en NULL).
SELECT codigobarra,
       COUNT(*)      AS cantidad,
       MIN(codigo)   AS codigo_a_conservar
FROM articulos
WHERE codigobarra IS NOT NULL
  AND btrim(codigobarra) <> ''
GROUP BY codigobarra
HAVING COUNT(*) > 1
ORDER BY cantidad DESC;

-- PASO 2: índice único parcial. No bloquea los artículos sin código de barras
-- (NULL o cadena vacía), que son legítimos. Si el paso 1 devolvió filas, este
-- CREATE falla a propósito: la base no debe quedar con datos ambiguos.
CREATE UNIQUE INDEX IF NOT EXISTS ux_articulos_codigobarra
    ON articulos (codigobarra)
    WHERE codigobarra IS NOT NULL
      AND btrim(codigobarra) <> '';
