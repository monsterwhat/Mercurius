package Services.Correos;

import Models.Correos.EmailTemplate;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * Catálogo de plantillas predeterminadas para el envío de comprobantes
 * electrónicos (facturas, tiquetes, notas y recibos).
 *
 * <p>El HTML usa únicamente estilos en línea para que se vea igual en Gmail y
 * Outlook: sin hoja de estilos, sin enlaces externos y sin scripts.</p>
 *
 * <p>Placeholders disponibles: {{cliente}}, {{consecutivo}}, {{fecha}},
 * {{total}}, {{enlace}}, {{tipoComprobante}}, {{estado}}, {{referencia}},
 * {{motivo}}, {{saldo}}, {{vencimiento}}, {{moneda}} y {{negocio}}.</p>
 *
 * @author Al
 */
public final class EmailTemplateDefaultsComprobantes {

    public static final String TIPO = "COMPROBANTES";

    private EmailTemplateDefaultsComprobantes() {
        // Clase de utilidad: no se instancia.
    }

    /**
     * Plantillas de comprobantes electrónicos.
     *
     * @param usuario usuario al que se asocian las plantillas (puede ser null)
     * @return lista de plantillas lista para persistir
     */
    @Nonnull
    public static java.util.List<EmailTemplate> templates(@Nullable Models.Usuarios usuario) {
        java.util.List<EmailTemplate> lista = new java.util.ArrayList<>();

        lista.add(plantilla("Factura electrónica - entrega", "Su factura electrónica {{consecutivo}}",
                """
                <h2 style="margin:0 0 14px;color:#0b3b8c;font-size:19px">Su factura electrónica está lista</h2>
                <p style="margin:0 0 12px;color:#1f2937;font-size:15px;line-height:1.55">
                  Hola {{cliente}}, adjuntamos la factura electrónica <strong>{{consecutivo}}</strong>
                  emitida el {{fecha}} por un total de <strong>{{total}}</strong>.
                </p>
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"
                       style="margin:18px 0;background:#f8fafc;border:1px solid #e5e9f2;border-radius:10px">
                  <tr><td style="padding:14px 18px;color:#374151;font-size:14px;line-height:1.7">
                    <strong>Comprobante:</strong> {{tipoComprobante}}<br>
                    <strong>Consecutivo:</strong> {{consecutivo}}<br>
                    <strong>Fecha de emisión:</strong> {{fecha}}<br>
                    <strong>Total:</strong> {{total}}<br>
                    <strong>Moneda:</strong> {{moneda}}
                  </td></tr>
                </table>
                <p style="margin:0 0 18px;color:#1f2937;font-size:15px;line-height:1.55">
                  Se adjuntan el XML firmado y el PDF de la factura. El XML es el comprobante
                  tributario válido ante Hacienda; el PDF es la copia de cortesía para su archivo
                  contable.
                </p>
                <p style="margin:0">
                  <a href="{{enlace}}" style="display:inline-block;background:#0b3b8c;color:#ffffff;
                     text-decoration:none;padding:12px 22px;border-radius:8px;font-size:15px;font-weight:600">
                    Ver factura
                  </a>
                </p>
                """, usuario));

        lista.add(plantilla("Tiquete electrónico", "Su tiquete electrónico {{consecutivo}}",
                """
                <h2 style="margin:0 0 14px;color:#0f766e;font-size:19px">Su tiquete electrónico está listo</h2>
                <p style="margin:0 0 12px;color:#1f2937;font-size:15px;line-height:1.55">
                  Hola {{cliente}}, gracias por su compra. Adjuntamos el tiquete electrónico
                  <strong>{{consecutivo}}</strong> del {{fecha}} por un total de <strong>{{total}}</strong>.
                </p>
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"
                       style="margin:18px 0;background:#f8fafc;border:1px solid #e5e9f2;border-radius:10px">
                  <tr><td style="padding:14px 18px;color:#374151;font-size:14px;line-height:1.7">
                    <strong>Documento:</strong> {{tipoComprobante}}<br>
                    <strong>Consecutivo:</strong> {{consecutivo}}<br>
                    <strong>Fecha:</strong> {{fecha}}<br>
                    <strong>Total:</strong> {{total}}<br>
                    <strong>Moneda:</strong> {{moneda}}
                  </td></tr>
                </table>
                <p style="margin:0 0 18px;color:#1f2937;font-size:15px;line-height:1.55">
                  Adjuntamos el XML del tiquete y su PDF. El tiquete respalda su compra; consérvelo
                  para cualquier cambio o devolución.
                </p>
                <p style="margin:0">
                  <a href="{{enlace}}" style="display:inline-block;background:#0b3b8c;color:#ffffff;
                     text-decoration:none;padding:12px 22px;border-radius:8px;font-size:15px;font-weight:600">
                    Ver tiquete
                  </a>
                </p>
                """, usuario));

        lista.add(plantilla("Nota de crédito", "Nota de crédito {{consecutivo}}",
                """
                <h2 style="margin:0 0 14px;color:#15803d;font-size:19px">Emitimos una nota de crédito</h2>
                <p style="margin:0 0 12px;color:#1f2937;font-size:15px;line-height:1.55">
                  Hola {{cliente}}, adjuntamos la nota de crédito <strong>{{consecutivo}}</strong> del
                  {{fecha}}, que aplica sobre el documento <strong>{{referencia}}</strong> por un total
                  de <strong>{{total}}</strong>.
                </p>
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"
                       style="margin:18px 0;background:#f8fafc;border:1px solid #e5e9f2;border-radius:10px">
                  <tr><td style="padding:14px 18px;color:#374151;font-size:14px;line-height:1.7">
                    <strong>Comprobante:</strong> {{tipoComprobante}}<br>
                    <strong>Consecutivo:</strong> {{consecutivo}}<br>
                    <strong>Documento original:</strong> {{referencia}}<br>
                    <strong>Fecha:</strong> {{fecha}}<br>
                    <strong>Motivo:</strong> {{motivo}}<br>
                    <strong>Total:</strong> {{total}}<br>
                    <strong>Moneda:</strong> {{moneda}}
                  </td></tr>
                </table>
                <p style="margin:0 0 18px;color:#1f2937;font-size:15px;line-height:1.55">
                  Se adjuntan el XML firmado y el PDF de la nota de crédito. El XML es el documento
                  tributario válido ante Hacienda y el PDF su copia de cortesía.
                </p>
                <p style="margin:0">
                  <a href="{{enlace}}" style="display:inline-block;background:#0b3b8c;color:#ffffff;
                     text-decoration:none;padding:12px 22px;border-radius:8px;font-size:15px;font-weight:600">
                    Ver nota de crédito
                  </a>
                </p>
                """, usuario));

        lista.add(plantilla("Nota de débito", "Nota de débito {{consecutivo}}",
                """
                <h2 style="margin:0 0 14px;color:#b45309;font-size:19px">Emitimos una nota de débito</h2>
                <p style="margin:0 0 12px;color:#1f2937;font-size:15px;line-height:1.55">
                  Hola {{cliente}}, adjuntamos la nota de débito <strong>{{consecutivo}}</strong> del
                  {{fecha}}, asociada al documento <strong>{{referencia}}</strong> por un total de
                  <strong>{{total}}</strong>.
                </p>
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"
                       style="margin:18px 0;background:#f8fafc;border:1px solid #e5e9f2;border-radius:10px">
                  <tr><td style="padding:14px 18px;color:#374151;font-size:14px;line-height:1.7">
                    <strong>Comprobante:</strong> {{tipoComprobante}}<br>
                    <strong>Consecutivo:</strong> {{consecutivo}}<br>
                    <strong>Documento original:</strong> {{referencia}}<br>
                    <strong>Fecha:</strong> {{fecha}}<br>
                    <strong>Motivo:</strong> {{motivo}}<br>
                    <strong>Total:</strong> {{total}}<br>
                    <strong>Moneda:</strong> {{moneda}}
                  </td></tr>
                </table>
                <p style="margin:0 0 18px;color:#1f2937;font-size:15px;line-height:1.55">
                  Se adjuntan el XML firmado y el PDF de la nota de débito. Si tiene dudas sobre el
                  motivo del cargo, responda a este correo citando el consecutivo.
                </p>
                <p style="margin:0">
                  <a href="{{enlace}}" style="display:inline-block;background:#0b3b8c;color:#ffffff;
                     text-decoration:none;padding:12px 22px;border-radius:8px;font-size:15px;font-weight:600">
                    Ver nota de débito
                  </a>
                </p>
                """, usuario));

        lista.add(plantilla("Factura electrónica de exportación",
                "Factura de exportación {{consecutivo}}",
                """
                <h2 style="margin:0 0 14px;color:#0b3b8c;font-size:19px">Su factura de exportación está lista</h2>
                <p style="margin:0 0 12px;color:#1f2937;font-size:15px;line-height:1.55">
                  Hola {{cliente}}, adjuntamos la factura electrónica de exportación
                  <strong>{{consecutivo}}</strong> emitida el {{fecha}} por un total de
                  <strong>{{total}}</strong> en <strong>{{moneda}}</strong>.
                </p>
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"
                       style="margin:18px 0;background:#f8fafc;border:1px solid #e5e9f2;border-radius:10px">
                  <tr><td style="padding:14px 18px;color:#374151;font-size:14px;line-height:1.7">
                    <strong>Comprobante:</strong> {{tipoComprobante}}<br>
                    <strong>Consecutivo:</strong> {{consecutivo}}<br>
                    <strong>Fecha:</strong> {{fecha}}<br>
                    <strong>Total:</strong> {{total}}<br>
                    <strong>Moneda:</strong> {{moneda}}
                  </td></tr>
                </table>
                <p style="margin:0 0 18px;color:#1f2937;font-size:15px;line-height:1.55">
                  Se adjuntan el XML firmado y el PDF de la factura de exportación. El XML es el
                  comprobante tributario válido ante Hacienda; el PDF acompaña la documentación de
                  exportación.
                </p>
                <p style="margin:0">
                  <a href="{{enlace}}" style="display:inline-block;background:#0b3b8c;color:#ffffff;
                     text-decoration:none;padding:12px 22px;border-radius:8px;font-size:15px;font-weight:600">
                    Ver factura de exportación
                  </a>
                </p>
                """, usuario));

        lista.add(plantilla("Factura electrónica de compra",
                "Factura de compra {{consecutivo}}",
                """
                <h2 style="margin:0 0 14px;color:#6d28d9;font-size:19px">Registramos su factura de compra</h2>
                <p style="margin:0 0 12px;color:#1f2937;font-size:15px;line-height:1.55">
                  Hola {{cliente}}, recibimos y registramos la factura electrónica de compra
                  <strong>{{consecutivo}}</strong> del {{fecha}} por un total de
                  <strong>{{total}}</strong>.
                </p>
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"
                       style="margin:18px 0;background:#f8fafc;border:1px solid #e5e9f2;border-radius:10px">
                  <tr><td style="padding:14px 18px;color:#374151;font-size:14px;line-height:1.7">
                    <strong>Comprobante:</strong> {{tipoComprobante}}<br>
                    <strong>Consecutivo:</strong> {{consecutivo}}<br>
                    <strong>Proveedor:</strong> {{negocio}}<br>
                    <strong>Fecha:</strong> {{fecha}}<br>
                    <strong>Total:</strong> {{total}}<br>
                    <strong>Moneda:</strong> {{moneda}}
                  </td></tr>
                </table>
                <p style="margin:0 0 18px;color:#1f2937;font-size:15px;line-height:1.55">
                  Adjuntamos el XML recibido y el PDF de la factura de compra tal como quedaron
                  registrados en nuestro sistema.
                </p>
                <p style="margin:0">
                  <a href="{{enlace}}" style="display:inline-block;background:#0b3b8c;color:#ffffff;
                     text-decoration:none;padding:12px 22px;border-radius:8px;font-size:15px;font-weight:600">
                    Ver factura de compra
                  </a>
                </p>
                """, usuario));

        lista.add(plantilla("Recibo electrónico de pago",
                "Recibo de pago {{consecutivo}}",
                """
                <h2 style="margin:0 0 14px;color:#047857;font-size:19px">Confirmamos su pago</h2>
                <p style="margin:0 0 12px;color:#1f2937;font-size:15px;line-height:1.55">
                  Hola {{cliente}}, adjuntamos el recibo electrónico de pago
                  <strong>{{consecutivo}}</strong> del {{fecha}} por un monto de
                  <strong>{{total}}</strong>.
                </p>
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"
                       style="margin:18px 0;background:#f8fafc;border:1px solid #e5e9f2;border-radius:10px">
                  <tr><td style="padding:14px 18px;color:#374151;font-size:14px;line-height:1.7">
                    <strong>Comprobante:</strong> {{tipoComprobante}}<br>
                    <strong>Consecutivo:</strong> {{consecutivo}}<br>
                    <strong>Documento relacionado:</strong> {{referencia}}<br>
                    <strong>Fecha:</strong> {{fecha}}<br>
                    <strong>Total pagado:</strong> {{total}}<br>
                    <strong>Saldo pendiente:</strong> {{saldo}}<br>
                    <strong>Moneda:</strong> {{moneda}}
                  </td></tr>
                </table>
                <p style="margin:0 0 18px;color:#1f2937;font-size:15px;line-height:1.55">
                  Se adjuntan el XML firmado y el PDF del recibo. Guarde este comprobante como respaldo
                  del pago aplicado a su cuenta.
                </p>
                <p style="margin:0">
                  <a href="{{enlace}}" style="display:inline-block;background:#0b3b8c;color:#ffffff;
                     text-decoration:none;padding:12px 22px;border-radius:8px;font-size:15px;font-weight:600">
                    Ver recibo
                  </a>
                </p>
                """, usuario));

        lista.add(plantilla("Factura rechazada - reemisión",
                "Factura {{consecutivo}} rechazada: reemisión",
                """
                <h2 style="margin:0 0 14px;color:#b91c1c;font-size:19px">Su factura fue rechazada</h2>
                <p style="margin:0 0 12px;color:#1f2937;font-size:15px;line-height:1.55">
                  Hola {{cliente}}, la factura electrónica <strong>{{consecutivo}}</strong> del
                  {{fecha}} por un total de <strong>{{total}}</strong> fue rechazada con el estado
                  <strong>{{estado}}</strong>.
                </p>
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"
                       style="margin:18px 0;background:#f8fafc;border:1px solid #e5e9f2;border-radius:10px">
                  <tr><td style="padding:14px 18px;color:#374151;font-size:14px;line-height:1.7">
                    <strong>Comprobante:</strong> {{tipoComprobante}}<br>
                    <strong>Consecutivo:</strong> {{consecutivo}}<br>
                    <strong>Fecha:</strong> {{fecha}}<br>
                    <strong>Estado:</strong> {{estado}}<br>
                    <strong>Motivo del rechazo:</strong> {{motivo}}<br>
                    <strong>Total:</strong> {{total}}
                  </td></tr>
                </table>
                <p style="margin:0 0 18px;color:#1f2937;font-size:15px;line-height:1.55">
                  Estamos corrigiendo el documento y emitiremos una nueva versión. Adjuntamos el XML
                  rechazado y el PDF con el detalle del rechazo para su control.
                </p>
                <p style="margin:0">
                  <a href="{{enlace}}" style="display:inline-block;background:#0b3b8c;color:#ffffff;
                     text-decoration:none;padding:12px 22px;border-radius:8px;font-size:15px;font-weight:600">
                    Revisar reemisión
                  </a>
                </p>
                """, usuario));

        lista.add(plantilla("Recordatorio de pago - factura vencida",
                "Recordatorio: factura {{consecutivo}} vence el {{vencimiento}}",
                """
                <h2 style="margin:0 0 14px;color:#b45309;font-size:19px">Tiene una factura pendiente de pago</h2>
                <p style="margin:0 0 12px;color:#1f2937;font-size:15px;line-height:1.55">
                  Hola {{cliente}}, le recordamos que la factura electrónica
                  <strong>{{consecutivo}}</strong> del {{fecha}} por un total de
                  <strong>{{total}}</strong> está pendiente de pago.
                </p>
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"
                       style="margin:18px 0;background:#f8fafc;border:1px solid #e5e9f2;border-radius:10px">
                  <tr><td style="padding:14px 18px;color:#374151;font-size:14px;line-height:1.7">
                    <strong>Comprobante:</strong> {{tipoComprobante}}<br>
                    <strong>Consecutivo:</strong> {{consecutivo}}<br>
                    <strong>Fecha de emisión:</strong> {{fecha}}<br>
                    <strong>Vencimiento:</strong> {{vencimiento}}<br>
                    <strong>Saldo pendiente:</strong> {{saldo}}<br>
                    <strong>Moneda:</strong> {{moneda}}
                  </td></tr>
                </table>
                <p style="margin:0 0 18px;color:#1f2937;font-size:15px;line-height:1.55">
                  Reenviamos adjuntos el XML y el PDF de la factura para que pueda completar el pago.
                  Si ya lo realizó, ignore este mensaje.
                </p>
                <p style="margin:0">
                  <a href="{{enlace}}" style="display:inline-block;background:#0b3b8c;color:#ffffff;
                     text-decoration:none;padding:12px 22px;border-radius:8px;font-size:15px;font-weight:600">
                    Ver factura y pagar
                  </a>
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
        template.setTipo(TIPO);
        template.setCuerpoHtml(cuerpoHtml.strip());
        template.setStatus(true);
        template.setFechaCreacion(new java.util.Date());
        template.setFechaModificacion(new java.util.Date());
        template.setUsuario(usuario);
        return template;
    }
}
