-- 2026-11-13
-- Limpieza única para instalaciones que importaron el outbox del sistema live:
-- los comprobantes ya fueron ACEPTADOS por Hacienda, pero sus filas de
-- envio_fuera_linea quedaron como PENDIENTE/VENCIDO/RECHAZADO y aparecían en
-- la cola como si hubiera que reenviarlas.
--
-- Se marcan como ENVIADO (terminal). No se borra nada y no se toca fecha_envio,
-- para no inflar el KPI "Enviados hoy" con históricos.
UPDATE envio_fuera_linea e
SET estado = 'ENVIADO',
    proximo_intento = NULL,
    ultimo_error = NULL
WHERE e.estado IN ('PENDIENTE', 'VENCIDO', 'RECHAZADO')
  AND e.comprobante_id IN (
      SELECT f.id
      FROM comprobantesemitidos f
      WHERE f.haciendaestado = 'ACEPTADO'
  );
