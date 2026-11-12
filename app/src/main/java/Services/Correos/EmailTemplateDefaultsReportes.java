package Services.Correos;

import Models.Correos.EmailTemplate;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * Catálogo de plantillas predeterminadas para los reportes del negocio.
 *
 * <p>Mismo estilo que las plantillas base de {@link EmailTemplateService}:
 * HTML con estilos en línea, sin hojas de estilo externas, listo para Gmail y
 * Outlook. Los cuerpos usan los marcadores {{reporte}}, {{periodo}},
 * {{fecha}}, {{enlace}}, {{negocio}}, {{resumen}}, {{total}} y {{cliente}}.
 *
 * @author Al
 */
public final class EmailTemplateDefaultsReportes {

    private EmailTemplateDefaultsReportes() {
    }

    /**
     * Construye las diez plantillas de reportes.
     *
     * @param usuario usuario al que se asocian las plantillas (puede ser null)
     * @return lista de plantillas listas para persistir
     */
    @Nonnull
    public static java.util.List<Models.Correos.EmailTemplate> templates(@Nullable Models.Usuarios usuario) {
        java.util.List<EmailTemplate> lista = new java.util.ArrayList<>();
        lista.add(plantilla("Reporte de ventas", "Reporte de ventas {{periodo}}",
                """
                <h2 style="margin:0 0 14px;color:#0b3b8c;font-size:19px">Reporte de ventas</h2>
                <p style="margin:0 0 12px;color:#1f2937;font-size:15px;line-height:1.55">
                  Hola {{cliente}}, le compartimos el reporte de ventas de {{negocio}}
                  correspondiente al período <strong>{{periodo}}</strong>.
                </p>
                <p style="margin:0 0 18px;color:#1f2937;font-size:15px;line-height:1.55">
                  {{resumen}}
                </p>
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"
                       style="margin:18px 0;background:#f8fafc;border:1px solid #e5e9f2;border-radius:10px">
                  <tr><td style="padding:14px 18px;color:#374151;font-size:14px;line-height:1.7">
                    <strong>Reporte:</strong> {{reporte}}<br>
                    <strong>Período:</strong> {{periodo}}<br>
                    <strong>Total vendido:</strong> {{total}}<br>
                    <strong>Generado:</strong> {{fecha}}
                  </td></tr>
                </table>
                <p style="margin:0 0 18px;color:#1f2937;font-size:15px;line-height:1.55">
                  El detalle completo va adjunto en PDF y Excel, con el desglose por día,
                  sucursal y método de pago.
                </p>
                <p style="margin:0">
                  <a href="{{enlace}}" style="display:inline-block;background:#0b3b8c;color:#ffffff;
                     text-decoration:none;padding:12px 22px;border-radius:8px;font-size:15px;font-weight:600">
                    Ver reporte de ventas
                  </a>
                </p>
                <p style="margin:16px 0 0;color:#6b7280;font-size:13px;line-height:1.6">
                  {{negocio}} · Enviado automáticamente por Mercurius.
                </p>
                """, usuario));
        lista.add(plantilla("Reporte de inventario y stock bajo", "Inventario y stock bajo {{periodo}}",
                """
                <h2 style="margin:0 0 14px;color:#b45309;font-size:19px">Reporte de inventario y stock bajo</h2>
                <p style="margin:0 0 12px;color:#1f2937;font-size:15px;line-height:1.55">
                  Hola {{cliente}}, le enviamos el reporte de inventario y stock bajo de
                  {{negocio}} para el período <strong>{{periodo}}</strong>.
                </p>
                <p style="margin:0 0 18px;color:#1f2937;font-size:15px;line-height:1.55">
                  {{resumen}}
                </p>
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"
                       style="margin:18px 0;background:#f8fafc;border:1px solid #e5e9f2;border-radius:10px">
                  <tr><td style="padding:14px 18px;color:#374151;font-size:14px;line-height:1.7">
                    <strong>Reporte:</strong> {{reporte}}<br>
                    <strong>Período:</strong> {{periodo}}<br>
                    <strong>Artículos por reponer:</strong> {{total}}<br>
                    <strong>Generado:</strong> {{fecha}}
                  </td></tr>
                </table>
                <p style="margin:0 0 18px;color:#1f2937;font-size:15px;line-height:1.55">
                  El archivo adjunto incluye la existencia actual, el mínimo configurado y el
                  consumo promedio de cada artículo.
                </p>
                <p style="margin:0">
                  <a href="{{enlace}}" style="display:inline-block;background:#0b3b8c;color:#ffffff;
                     text-decoration:none;padding:12px 22px;border-radius:8px;font-size:15px;font-weight:600">
                    Revisar inventario
                  </a>
                </p>
                <p style="margin:16px 0 0;color:#6b7280;font-size:13px;line-height:1.6">
                  {{negocio}} · Enviado automáticamente por Mercurius.
                </p>
                """, usuario));
        lista.add(plantilla("Reporte de cuentas por cobrar", "Cuentas por cobrar {{periodo}}",
                """
                <h2 style="margin:0 0 14px;color:#b91c1c;font-size:19px">Reporte de cuentas por cobrar</h2>
                <p style="margin:0 0 12px;color:#1f2937;font-size:15px;line-height:1.55">
                  Hola {{cliente}}, adjuntamos el reporte de cuentas por cobrar de {{negocio}}
                  con las facturas vencidas al período <strong>{{periodo}}</strong>.
                </p>
                <p style="margin:0 0 18px;color:#1f2937;font-size:15px;line-height:1.55">
                  {{resumen}}
                </p>
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"
                       style="margin:18px 0;background:#f8fafc;border:1px solid #e5e9f2;border-radius:10px">
                  <tr><td style="padding:14px 18px;color:#374151;font-size:14px;line-height:1.7">
                    <strong>Reporte:</strong> {{reporte}}<br>
                    <strong>Período:</strong> {{periodo}}<br>
                    <strong>Saldo vencido:</strong> {{total}}<br>
                    <strong>Generado:</strong> {{fecha}}
                  </td></tr>
                </table>
                <p style="margin:0 0 18px;color:#1f2937;font-size:15px;line-height:1.55">
                  El reporte adjunto detalla cada factura vencida por cliente, fecha de emisión
                  y días de atraso.
                </p>
                <p style="margin:0">
                  <a href="{{enlace}}" style="display:inline-block;background:#0b3b8c;color:#ffffff;
                     text-decoration:none;padding:12px 22px;border-radius:8px;font-size:15px;font-weight:600">
                    Ver cuentas por cobrar
                  </a>
                </p>
                <p style="margin:16px 0 0;color:#6b7280;font-size:13px;line-height:1.6">
                  {{negocio}} · Enviado automáticamente por Mercurius.
                </p>
                """, usuario));
        lista.add(plantilla("Reporte de cierre de caja", "Cierre de caja {{periodo}}",
                """
                <h2 style="margin:0 0 14px;color:#0f766e;font-size:19px">Reporte de cierre de caja</h2>
                <p style="margin:0 0 12px;color:#1f2937;font-size:15px;line-height:1.55">
                  Hola {{cliente}}, le compartimos el cierre de caja de {{negocio}} para el
                  período <strong>{{periodo}}</strong>.
                </p>
                <p style="margin:0 0 18px;color:#1f2937;font-size:15px;line-height:1.55">
                  {{resumen}}
                </p>
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"
                       style="margin:18px 0;background:#f8fafc;border:1px solid #e5e9f2;border-radius:10px">
                  <tr><td style="padding:14px 18px;color:#374151;font-size:14px;line-height:1.7">
                    <strong>Reporte:</strong> {{reporte}}<br>
                    <strong>Período:</strong> {{periodo}}<br>
                    <strong>Diferencia de caja:</strong> {{total}}<br>
                    <strong>Generado:</strong> {{fecha}}
                  </td></tr>
                </table>
                <p style="margin:0 0 18px;color:#1f2937;font-size:15px;line-height:1.55">
                  El adjunto incluye apertura, ventas en efectivo, tarjeta y SINPE, arqueo y
                  diferencia final del turno.
                </p>
                <p style="margin:0">
                  <a href="{{enlace}}" style="display:inline-block;background:#0b3b8c;color:#ffffff;
                     text-decoration:none;padding:12px 22px;border-radius:8px;font-size:15px;font-weight:600">
                    Ver cierre de caja
                  </a>
                </p>
                <p style="margin:16px 0 0;color:#6b7280;font-size:13px;line-height:1.6">
                  {{negocio}} · Enviado automáticamente por Mercurius.
                </p>
                """, usuario));
        lista.add(plantilla("Reporte de clientes y lealtad", "Clientes y lealtad {{periodo}}",
                """
                <h2 style="margin:0 0 14px;color:#7c3aed;font-size:19px">Reporte de clientes y lealtad</h2>
                <p style="margin:0 0 12px;color:#1f2937;font-size:15px;line-height:1.55">
                  Hola {{cliente}}, este es el reporte de clientes y lealtad de {{negocio}}
                  correspondiente al período <strong>{{periodo}}</strong>.
                </p>
                <p style="margin:0 0 18px;color:#1f2937;font-size:15px;line-height:1.55">
                  {{resumen}}
                </p>
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"
                       style="margin:18px 0;background:#f8fafc;border:1px solid #e5e9f2;border-radius:10px">
                  <tr><td style="padding:14px 18px;color:#374151;font-size:14px;line-height:1.7">
                    <strong>Reporte:</strong> {{reporte}}<br>
                    <strong>Período:</strong> {{periodo}}<br>
                    <strong>Puntos otorgados:</strong> {{total}}<br>
                    <strong>Generado:</strong> {{fecha}}
                  </td></tr>
                </table>
                <p style="margin:0 0 18px;color:#1f2937;font-size:15px;line-height:1.55">
                  El adjunto resume clientes nuevos y recurrentes, canjes y puntos otorgados
                  en el programa de lealtad.
                </p>
                <p style="margin:0">
                  <a href="{{enlace}}" style="display:inline-block;background:#0b3b8c;color:#ffffff;
                     text-decoration:none;padding:12px 22px;border-radius:8px;font-size:15px;font-weight:600">
                    Ver reporte de clientes
                  </a>
                </p>
                <p style="margin:16px 0 0;color:#6b7280;font-size:13px;line-height:1.6">
                  {{negocio}} · Enviado automáticamente por Mercurius.
                </p>
                """, usuario));
        lista.add(plantilla("Reporte de margen y rentabilidad", "Margen y rentabilidad {{periodo}}",
                """
                <h2 style="margin:0 0 14px;color:#1d4ed8;font-size:19px">Reporte de margen y rentabilidad</h2>
                <p style="margin:0 0 12px;color:#1f2937;font-size:15px;line-height:1.55">
                  Hola {{cliente}}, le enviamos el reporte de margen y rentabilidad de
                  {{negocio}} para el período <strong>{{periodo}}</strong>.
                </p>
                <p style="margin:0 0 18px;color:#1f2937;font-size:15px;line-height:1.55">
                  {{resumen}}
                </p>
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"
                       style="margin:18px 0;background:#f8fafc;border:1px solid #e5e9f2;border-radius:10px">
                  <tr><td style="padding:14px 18px;color:#374151;font-size:14px;line-height:1.7">
                    <strong>Reporte:</strong> {{reporte}}<br>
                    <strong>Período:</strong> {{periodo}}<br>
                    <strong>Margen bruto:</strong> {{total}}<br>
                    <strong>Generado:</strong> {{fecha}}
                  </td></tr>
                </table>
                <p style="margin:0 0 18px;color:#1f2937;font-size:15px;line-height:1.55">
                  El reporte adjunto compara ingreso, costo y margen por categoría y por
                  producto, con la variación contra el período anterior.
                </p>
                <p style="margin:0">
                  <a href="{{enlace}}" style="display:inline-block;background:#0b3b8c;color:#ffffff;
                     text-decoration:none;padding:12px 22px;border-radius:8px;font-size:15px;font-weight:600">
                    Ver rentabilidad
                  </a>
                </p>
                <p style="margin:16px 0 0;color:#6b7280;font-size:13px;line-height:1.6">
                  {{negocio}} · Enviado automáticamente por Mercurius.
                </p>
                """, usuario));
        lista.add(plantilla("Reporte de compras y proveedores", "Compras y proveedores {{periodo}}",
                """
                <h2 style="margin:0 0 14px;color:#9a3412;font-size:19px">Reporte de compras y proveedores</h2>
                <p style="margin:0 0 12px;color:#1f2937;font-size:15px;line-height:1.55">
                  Hola {{cliente}}, adjuntamos el reporte de compras y proveedores de
                  {{negocio}} del período <strong>{{periodo}}</strong>.
                </p>
                <p style="margin:0 0 18px;color:#1f2937;font-size:15px;line-height:1.55">
                  {{resumen}}
                </p>
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"
                       style="margin:18px 0;background:#f8fafc;border:1px solid #e5e9f2;border-radius:10px">
                  <tr><td style="padding:14px 18px;color:#374151;font-size:14px;line-height:1.7">
                    <strong>Reporte:</strong> {{reporte}}<br>
                    <strong>Período:</strong> {{periodo}}<br>
                    <strong>Total comprado:</strong> {{total}}<br>
                    <strong>Generado:</strong> {{fecha}}
                  </td></tr>
                </table>
                <p style="margin:0 0 18px;color:#1f2937;font-size:15px;line-height:1.55">
                  El adjunto detalla órdenes de compra, recepciones y saldos pendientes
                  por proveedor.
                </p>
                <p style="margin:0">
                  <a href="{{enlace}}" style="display:inline-block;background:#0b3b8c;color:#ffffff;
                     text-decoration:none;padding:12px 22px;border-radius:8px;font-size:15px;font-weight:600">
                    Ver compras
                  </a>
                </p>
                <p style="margin:16px 0 0;color:#6b7280;font-size:13px;line-height:1.6">
                  {{negocio}} · Enviado automáticamente por Mercurius.
                </p>
                """, usuario));
        lista.add(plantilla("Reporte de impuestos y Hacienda", "Impuestos y Hacienda {{periodo}}",
                """
                <h2 style="margin:0 0 14px;color:#134e4a;font-size:19px">Reporte de impuestos y Hacienda</h2>
                <p style="margin:0 0 12px;color:#1f2937;font-size:15px;line-height:1.55">
                  Hola {{cliente}}, le compartimos el reporte de impuestos y Hacienda de
                  {{negocio}} correspondiente al período <strong>{{periodo}}</strong>.
                </p>
                <p style="margin:0 0 18px;color:#1f2937;font-size:15px;line-height:1.55">
                  {{resumen}}
                </p>
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"
                       style="margin:18px 0;background:#f8fafc;border:1px solid #e5e9f2;border-radius:10px">
                  <tr><td style="padding:14px 18px;color:#374151;font-size:14px;line-height:1.7">
                    <strong>Reporte:</strong> {{reporte}}<br>
                    <strong>Período:</strong> {{periodo}}<br>
                    <strong>Impuesto del período:</strong> {{total}}<br>
                    <strong>Generado:</strong> {{fecha}}
                  </td></tr>
                </table>
                <p style="margin:0 0 18px;color:#1f2937;font-size:15px;line-height:1.55">
                  El reporte adjunto resume el IVA por tarifa, los comprobantes emitidos y
                  recibidos, y el estado de las declaraciones ante Hacienda.
                </p>
                <p style="margin:0">
                  <a href="{{enlace}}" style="display:inline-block;background:#0b3b8c;color:#ffffff;
                     text-decoration:none;padding:12px 22px;border-radius:8px;font-size:15px;font-weight:600">
                    Ver reporte de impuestos
                  </a>
                </p>
                <p style="margin:16px 0 0;color:#6b7280;font-size:13px;line-height:1.6">
                  {{negocio}} · Enviado automáticamente por Mercurius.
                </p>
                """, usuario));
        lista.add(plantilla("Resumen ejecutivo", "Resumen ejecutivo {{periodo}}",
                """
                <h2 style="margin:0 0 14px;color:#0b3b8c;font-size:19px">Resumen ejecutivo</h2>
                <p style="margin:0 0 12px;color:#1f2937;font-size:15px;line-height:1.55">
                  Hola {{cliente}}, este es el resumen ejecutivo de {{negocio}} para el
                  período <strong>{{periodo}}</strong>.
                </p>
                <p style="margin:0 0 18px;color:#1f2937;font-size:15px;line-height:1.55">
                  {{resumen}}
                </p>
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"
                       style="margin:18px 0;background:#f8fafc;border:1px solid #e5e9f2;border-radius:10px">
                  <tr><td style="padding:14px 18px;color:#374151;font-size:14px;line-height:1.7">
                    <strong>Reporte:</strong> {{reporte}}<br>
                    <strong>Período:</strong> {{periodo}}<br>
                    <strong>Resultado del período:</strong> {{total}}<br>
                    <strong>Generado:</strong> {{fecha}}
                  </td></tr>
                </table>
                <p style="margin:0 0 18px;color:#1f2937;font-size:15px;line-height:1.55">
                  El adjunto reúne los indicadores principales: ventas, margen, inventario,
                  cartera y caja, en una sola vista.
                </p>
                <p style="margin:0">
                  <a href="{{enlace}}" style="display:inline-block;background:#0b3b8c;color:#ffffff;
                     text-decoration:none;padding:12px 22px;border-radius:8px;font-size:15px;font-weight:600">
                    Abrir resumen ejecutivo
                  </a>
                </p>
                <p style="margin:16px 0 0;color:#6b7280;font-size:13px;line-height:1.6">
                  {{negocio}} · Enviado automáticamente por Mercurius.
                </p>
                """, usuario));
        lista.add(plantilla("Reporte programado (detallado)", "Reporte programado detallado {{periodo}}",
                """
                <h2 style="margin:0 0 14px;color:#0b3b8c;font-size:19px">Reporte programado (detallado)</h2>
                <p style="margin:0 0 12px;color:#1f2937;font-size:15px;line-height:1.55">
                  Hola {{cliente}}, este es el reporte programado (detallado) de {{negocio}}
                  para el período <strong>{{periodo}}</strong>.
                </p>
                <p style="margin:0 0 18px;color:#1f2937;font-size:15px;line-height:1.55">
                  {{resumen}}
                </p>
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"
                       style="margin:18px 0;background:#f8fafc;border:1px solid #e5e9f2;border-radius:10px">
                  <tr><td style="padding:14px 18px;color:#374151;font-size:14px;line-height:1.7">
                    <strong>Reporte:</strong> {{reporte}}<br>
                    <strong>Período:</strong> {{periodo}}<br>
                    <strong>Total del reporte:</strong> {{total}}<br>
                    <strong>Generado:</strong> {{fecha}}
                  </td></tr>
                </table>
                <p style="margin:0 0 18px;color:#1f2937;font-size:15px;line-height:1.55">
                  El adjunto incluye el detalle completo de las secciones configuradas en la
                  programación, sin resúmenes intermedios.
                </p>
                <p style="margin:0">
                  <a href="{{enlace}}" style="display:inline-block;background:#0b3b8c;color:#ffffff;
                     text-decoration:none;padding:12px 22px;border-radius:8px;font-size:15px;font-weight:600">
                    Abrir reporte completo
                  </a>
                </p>
                <p style="margin:16px 0 0;color:#6b7280;font-size:13px;line-height:1.6">
                  {{negocio}} · Envío automático según la programación configurada.
                </p>
                """, usuario));
        return lista;
    }

    @Nonnull
    private static EmailTemplate plantilla(@Nonnull String nombre, @Nonnull String asunto,
                                           @Nonnull String cuerpoHtml,
                                           @Nullable Models.Usuarios usuario) {
        EmailTemplate template = new EmailTemplate();
        template.setNombre(nombre);
        template.setAsunto(asunto);
        template.setTipo("REPORTES");
        template.setCuerpoHtml(cuerpoHtml.strip());
        template.setStatus(true);
        template.setFechaCreacion(new java.util.Date());
        template.setFechaModificacion(new java.util.Date());
        template.setUsuario(usuario);
        return template;
    }
}
