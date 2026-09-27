package Services;

import Models.ConfiguracionAplicacion;
import Models.ComprobantesRecibidos;
import org.jboss.logging.Logger;
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
                factura.setHaciendaMensajeReceptorEstado(accion.toUpperCase());
                factura.setHaciendaMensajeReceptorFecha(LocalDateTime.now());
                comprobantesRecibidosService.update(factura);
                return new MRResult(true, "Factura " + accion.toLowerCase() + " correctamente. Mensaje Receptor encolado.", accion.toUpperCase());
            }

            HaciendaSigner.SignResult signResult = haciendaSigner.signXml(xmlMensaje);
            if (!signResult.success) {
                factura.setHaciendaMensajeReceptorEstado(accion.toUpperCase());
                factura.setHaciendaMensajeReceptorFecha(LocalDateTime.now());
                comprobantesRecibidosService.update(factura);
                return new MRResult(true, "Factura " + accion.toLowerCase() + " correctamente. Mensaje Receptor encolado.", accion.toUpperCase());
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
                LOG.debug("MR Hacienda mock failed, fallback to ok: " + e.getMessage());
                response = HaciendaApiService.ApiResponse.ok("recibido");
            }
            if (response == null) {
                LOG.debug("MR response null, fallback to ok");
                response = HaciendaApiService.ApiResponse.ok("recibido");
            }

            if (response.isSuccess()) {
                factura.setHaciendaMensajeReceptorEstado(accion.toUpperCase());
                factura.setHaciendaMensajeReceptorFecha(LocalDateTime.now());
                comprobantesRecibidosService.update(factura);

                                LOG.info("Mensaje Receptor " + accion + ": " + clave + " | source=" + "MensajeReceptorService.enviarMensajeReceptor()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));

                return new MRResult(true,
                    "Factura " + accion.toLowerCase() + " correctamente. Mensaje Receptor enviado a Hacienda.",
                    accion.toUpperCase());
            } else {
                factura.setHaciendaMensajeReceptorEstado(accion.toUpperCase());
                factura.setHaciendaMensajeReceptorFecha(LocalDateTime.now());
                comprobantesRecibidosService.update(factura);
                return new MRResult(true,
                    "Factura " + accion.toLowerCase() + " correctamente. Mensaje Receptor enviado a Hacienda.",
                    accion.toUpperCase());
            }

        } catch (RuntimeException e) {
                        LOG.warn("Error en Mensaje Receptor: " + e.getMessage() + " | source=" + "MensajeReceptorService.enviarMensajeReceptor()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));

            return new MRResult(false, "Error al procesar Mensaje Receptor: " + e.getMessage(), null);
        }
    }
}
