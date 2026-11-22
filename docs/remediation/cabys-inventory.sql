-- Inventario de CABYS afectados por la ambiguedad 01 / 10 / 11.
--
-- Ejecutar contra la base de produccion (o una copia restaurada), nunca contra
-- mercurius_test. Solo lectura.
--
-- Devuelve una fila por CABYS con impuesto 0/nulo/vacio que tenga al menos un
-- articulo activo. El responsable fiscal debe completar legal_basis y
-- selected_code en la hoja de calculo antes de tocar ComprobanteService.
--
-- Los codigos:
--   01 = Tarifa 0% (Art. 32.1 RLIVA), con derecho pleno a credito
--   10 = Tarifa Exenta (Ley 9635, Art. 8)
--   11 = Tarifa 0% sin derecho a credito (no sujeto)
-- No inferir el codigo solo porque impuesto = 0.

WITH cabys_afectados AS (
    SELECT
        cb.codigo AS cabys_codigo,
        cb.descripcion AS cabys_descripcion,
        cb.impuesto AS impuesto_cabys,
        COUNT(a.codigo) FILTER (WHERE a.status = true) AS articulos_activos,
        COUNT(a.codigo) AS articulos_totales,
        STRING_AGG(a.nombre, ' | ' ORDER BY a.nombre)
            FILTER (WHERE a.status = true) AS articulos_ejemplo
    FROM cabys cb
    JOIN articulos a ON a.cabys_id = cb.codigo
    WHERE cb.impuesto IS NULL
       OR BTRIM(cb.impuesto) IN ('', '0', '0.0', '0.00')
    GROUP BY cb.codigo, cb.descripcion, cb.impuesto
)
SELECT
    cabys_codigo,
    cabys_descripcion,
    impuesto_cabys,
    articulos_activos,
    articulos_totales,
    '' AS legal_basis,          -- completar: Ley 9635 Art. 8 / no sujeto / Art. 32.1
    '' AS selected_code,        -- completar: 01 / 10 / 11
    '' AS confirmed_by,         -- responsable fiscal/contable
    '' AS confirmed_at,         -- fecha ISO
    '' AS notes,
    articulos_ejemplo
FROM cabys_afectados
ORDER BY articulos_activos DESC, cabys_codigo;

-- Resumen de volumen por impuesto declarado en CABYS (para contexto).
SELECT
    COALESCE(NULLIF(BTRIM(impuesto), ''), '(nulo/vacio)') AS impuesto,
    COUNT(*) AS cabys_total
FROM cabys
GROUP BY 1
ORDER BY 2 DESC;

-- Artículos activos sin CABYS: no pueden construir una linea fiscal valida.
SELECT
    a.codigo AS articulo_codigo,
    a.nombre AS articulo_nombre,
    a.codigo_barra,
    a.status
FROM articulos a
WHERE a.status = true
  AND (a.cabys_id IS NULL OR BTRIM(a.cabys_id) = '')
ORDER BY a.nombre;
