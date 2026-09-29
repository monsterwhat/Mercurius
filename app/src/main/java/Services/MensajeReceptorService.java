package Services;

import Models.ConfiguracionAplicacion;
import Models.ComprobantesRecibidos;
import org.jboss.logging.Logger;
import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Mensaje Receptor: la respuesta del obligated tributario a una factura recibida
 * (1=aceptada, 2=aceptada parcialmente, 3=rechazada).
 *
 * <p><b>Este rechazo NO es el rechazo del Art. 19.</b> El Art. 19 del Reglamento
 * de Comprobantes Electrónicos se refiere al rechazo de la DGT sobre un
 * comprobante emitido, que obliga al emisor a re-emitirlo y prohíbe la nota de
 * crédito. El mensaje 3 de aquí es la rechazo del receptor de una factura
 * recibida y no genera comprobante propio de ningún tipo. Los documentos
 * emitidos que Hacienda rechaza se re-emiten en
 * {@link ComprobantesEmitidosCorrectionService} con el código 16 de la nota 9.
 */
@Named
@ApplicationScoped
public class MensajeReceptorService {

    private static final Logger LOG = Logger.getLogger(MensajeReceptorService.class);

    @Inject AppSettingsService appSettingsService;
    @Inject HaciendaSigner haciendaSigner;
    @Inject HaciendaApiService haciendaApiService;
    @Inject ConsecutivoReceptorService consecutivoReceptorService;
    @Inject ComprobanteService comprobanteService;
    @Inject ComprobantesRecibidosService comprobantesRecibidosService;

    public static class MRResult {
        public final boolean success;
        public final String message;
        public final String estado;

        public MRResult(boolean success, String message, String estado) {
            this.success = success;
            this.message = message;
            this.estado = estado;
        }
    }

    @Transactional
    public MRResult enviarMensajeReceptor(ComprobantesRecibidos factura, int codigoMensaje,
                                           String accion, BigDecimal montoTotalImpuesto,
                                           BigDecimal montoTotalFactura) {
        try {
            LOG.debug("MR start id=" + (factura != null ? factura.getId() : "null") + " codigo=" + codigoMensaje + " accion=" + accion);
            if (factura.getEncabezado() == null) {
                LOG.warn("MR fail: sin encabezado");
                return new MRResult(false, "Factura sin encabezado", null);
            }

            ConfiguracionAplicacion settings = appSettingsService.returnCurrent();
            if (settings == null) {
                return new MRResult(false, "No hay configuración de Hacienda", null);
            }

            // ── Preflight Mensaje Receptor ──────────────────────────────────────
            // La cédula que se emite como <NumeroCedulaReceptor> es la del obligado
            // tributario (esta cuenta), no la del emisor de la factura: por eso se
            // valida settings.getIdentificacion(). MensajeReceptor_V4.4.xsd la
            // restringe a \d{9,12} (solo dígitos), así que una identificación
            // alfanumérica no puede validar contra el esquema oficial.
            // Se valida aquí, antes de consumir el consecutivo y antes de construir
            // el XML, para no gastar numeración en un envío que no podría validarse
            // y para que el operador reciba la causa, no un "cvc-datatype-valid" sin contexto.
            try {
                ComprobanteService.validarCedulaMensajeReceptor(settings.getIdentificacion());
            } catch (IllegalArgumentException e) {
                LOG.warn("MR bloqueado: " + e.getMessage()
                    + " | source=MensajeReceptorService.enviarMensajeReceptor()"
                    + " | clave=" + String.valueOf(factura.getEncabezado().getClave())
                    + " | despues=envio bloqueado, factura queda pendiente");
                return new MRResult(false, e.getMessage(), null);
            }

            String clave = factura.getEncabezado().getClave();
            if (clave == null || clave.isEmpty()) {
                LOG.warn("MR clave missing, using fallback clave for offline queue accion=" + accion);
                clave = "50600000000000000000000000000000000000000000000000";
                factura.getEncabezado().setClave(clave);
            }

            String receptorId = settings.getIdentificacion() != null ? settings.getIdentificacion() : "0";
            String emisorId = "0";
            if (factura.getEncabezado().getEmisor() != null
                && factura.getEncabezado().getEmisor().getIdentificacion() != null
                && factura.getEncabezado().getEmisor().getIdentificacion().getNumero() != null) {
                emisorId = factura.getEncabezado().getEmisor().getIdentificacion().getNumero();
            }

            LocalDateTime fechaEmision = factura.getEncabezado().getFechaEmision();

            String codigoSucursal = settings.getCodigoSucursal() != null ? settings.getCodigoSucursal() : "001";
            String codigoTerminal = settings.getCodigoTerminal() != null ? settings.getCodigoTerminal() : "001";
            String mrType = codigoMensaje == 1 ? "05" : (codigoMensaje == 2 ? "06" : "07");
            String sucursalFmt = String.format("%03d", Integer.parseInt(codigoSucursal));
            String terminalFmt = String.format("%05d", Integer.parseInt(codigoTerminal));
            String seq = consecutivoReceptorService.getNextSequential(sucursalFmt, terminalFmt, mrType);
            String numeroConsecutivoReceptor = sucursalFmt + terminalFmt + mrType + seq;

            String xmlMensaje = comprobanteService.generateMensajeReceptorXml(
                settings, clave, emisorId, receptorId, fechaEmision, codigoMensaje,
                accion, montoTotalImpuesto, montoTotalFactura, numeroConsecutivoReceptor
            );

            if (xmlMensaje == null) {
                // El XML NO se genero: no hay documento que firmar, enviar ni
                // encolar. Se marcaba como exito ("Mensaje Receptor encolado")
                // cuando en realidad no ocurrio nada, y el operador veia una
                // factura como_notificada_ que nunca llego a Hacienda.
                return pendiente(factura, "No se pudo generar el XML del Mensaje Receptor para la factura.");
            }

            HaciendaSigner.SignResult signResult = haciendaSigner.signXml(xmlMensaje);
            if (!signResult.success) {
                // Fallo de firma XAdES: tampoco hubo envio. Antes se reportaba
                // como exito por el mismo motivo que el caso anterior.
                return pendiente(factura, "No se pudo firmar el XML del Mensaje Receptor para la factura.");
            }

            String emisorTipoId = settings.getTipoIdentificacion();
            String emisorNumeroId = settings.getIdentificacion();
            String receptorTipoId = "01";
            String receptorNumeroId = "000000000";
            if (factura.getEncabezado() != null
                && factura.getEncabezado().getEmisor() != null
                && factura.getEncabezado().getEmisor().getIdentificacion() != null) {
                receptorTipoId = factura.getEncabezado().getEmisor().getIdentificacion().getTipo();
                receptorNumeroId = factura.getEncabezado().getEmisor().getIdentificacion().getNumero();
            }

            HaciendaApiService.ApiResponse response;
            try {
                if (codigoMensaje == 1) {
                    response = haciendaApiService.acceptInvoice(clave, signResult.signedXml,
                        emisorTipoId, emisorNumeroId, receptorTipoId, receptorNumeroId);
                } else {
                    response = haciendaApiService.rejectInvoice(clave, signResult.signedXml,
                        emisorTipoId, emisorNumeroId, receptorTipoId, receptorNumeroId);
                }
            } catch (Exception e) {
                // Fallo de transporte contra Hacienda (timeout, TLS, 5xx, fallo
                // de autenticacion). Antes se fabricaba una respuesta "ok" y la
                // factura quedaba registrada comonotificada. Ahora es un fallo
                // real y la factura vuelve a PENDIENTE para poder reintentarse.
                LOG.warn("MR: fallo de transporte al enviar a Hacienda: " + e.getMessage()
                    + " | source=MensajeReceptorService.enviarMensajeReceptor()"
                    + " | clave=" + clave);
                return pendiente(factura,
                    "No se pudo comunicar con Hacienda para enviar el Mensaje Receptor: " + e.getMessage());
            }
            if (response == null) {
                LOG.warn("MR: Hacienda devolvio una respuesta nula"
                    + " | source=MensajeReceptorService.enviarMensajeReceptor()"
                    + " | clave=" + clave);
                return pendiente(factura,
                    "Hacienda no devolvio respuesta al enviar el Mensaje Receptor.");
            }

            if (response.isSuccess()) {
                factura.setHaciendaMensajeReceptorEstado(accion.toUpperCase());
                factura.setHaciendaMensajeReceptorFecha(LocalDateTime.now());
                comprobantesRecibidosService.update(factura);

                                LOG.info("Mensaje Receptor " + accion + ": " + clave + " | source=" + "MensajeReceptorService.enviarMensajeReceptor()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));

                return new MRResult(true,
                    "Factura " + accion.toLowerCase() + " correctamente. Mensaje Receptor enviado a Hacienda.",
                    accion.toUpperCase());
            }

            // Hacienda RECHAZO el Mensaje Receptor. Este era el defecto mas grave
            // del metodo: las dos ramas del if eran identicas byte a byte, la de
            // rechazo devolvia MRResult(true, "...enviado a Hacienda.") y sellaba
            // el estado como si la notificacion hubiera surtido efecto. Ahora el
            // motivo real de Hacienda se propaga al operador y la factura vuelve a
            // PENDIENTE, que es el estado que Consultas y el programador de tareas
            // reconocen como "todavia sin notificar".
            LOG.warn("MR: Hacienda rechazo el Mensaje Receptor: " + response.errorMessage
                + " | source=MensajeReceptorService.enviarMensajeReceptor()"
                + " | clave=" + clave);
            return pendiente(factura,
                "Hacienda rechazo el Mensaje Receptor: "
                    + (response.errorMessage != null ? response.errorMessage : response.responseBody));

        } catch (RuntimeException e) {
                        LOG.warn("Error en Mensaje Receptor: " + e.getMessage() + " | source=" + "MensajeReceptorService.enviarMensajeReceptor()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));

            return new MRResult(false, "Error al procesar Mensaje Receptor: " + e.getMessage(), null);
        }
    }

    /**
     * Leaves a received invoice in {@code PENDIENTE} and reports the failure.
     *
     * <p>{@code PENDIENTE} is the state Consultas and the scheduler agree on for
     * "still to notify": {@code ComprobantesRecibidosService} selects
     * {@code estado IS NULL OR estado = 'PENDIENTE'} to build that list, and
     * {@code ProgramadorTareas} skips only rows whose state is already set. Using
     * it here keeps a failed Mensaje Receptor visible and retryable instead of
     * silently marking the invoice as notified.
     *
     * <p>The timestamp is deliberately NOT stamped: {@code
     * haciendaMensajeReceptorFecha} records when Hacienda actually received the
     * document, and no document was received.</p>
     *
     * <p>Wrapped in its own guard because this runs from the failure branches of
     * a method that is itself {@code @Transactional}: a persistence problem while
     * recording the failure must not replace the real cause with a second
     * exception and hide it from the operator.</p>
     */
    private MRResult pendiente(@Nonnull ComprobantesRecibidos factura, @Nonnull String motivo) {
        try {
            factura.setHaciendaMensajeReceptorEstado("PENDIENTE");
            comprobantesRecibidosService.update(factura);
        } catch (RuntimeException e) {
            LOG.warn("No se pudo registrar el estado PENDIENTE del Mensaje Receptor: " + e.getMessage()
                + " | source=MensajeReceptorService.pendiente()"
                + " | motivoOriginal=" + motivo);
        }
        return new MRResult(false, motivo, "PENDIENTE");
    }
}
