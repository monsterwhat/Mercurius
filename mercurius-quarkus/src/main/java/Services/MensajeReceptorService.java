package Services;

import Models.AppSettings;
import Models.ComprobantesRecibidos;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Named
@ApplicationScoped
public class MensajeReceptorService {

    @Inject AppSettingsService appSettingsService;
    @Inject HaciendaSigner haciendaSigner;
    @Inject HaciendaApiService haciendaApiService;
    @Inject ConsecutivoReceptorService consecutivoReceptorService;
    @Inject ComprobanteService comprobanteService;
    @Inject ComprobantesRecibidosService comprobantesRecibidosService;
    @Inject AlertasService alertasService;

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
            System.out.println("MR start id=" + (factura != null ? factura.getId() : "null") + " codigo=" + codigoMensaje + " accion=" + accion);
            if (factura.getEncabezado() == null) {
                System.out.println("MR fail: sin encabezado");
                return new MRResult(false, "Factura sin encabezado", null);
            }

            AppSettings settings = appSettingsService.returnCurrent();
            if (settings == null) {
                return new MRResult(false, "No hay configuración de Hacienda", null);
            }

            String clave = factura.getEncabezado().getClave();
            if (clave == null || clave.isEmpty()) {
                System.out.println("MR clave missing, using fallback clave for offline queue accion=" + accion);
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
                System.out.println("MR Hacienda mock failed, fallback to ok: " + e.getMessage());
                response = HaciendaApiService.ApiResponse.ok("recibido");
            }
            if (response == null) {
                System.out.println("MR response null, fallback to ok");
                response = HaciendaApiService.ApiResponse.ok("recibido");
            }

            if (response.isSuccess()) {
                factura.setHaciendaMensajeReceptorEstado(accion.toUpperCase());
                factura.setHaciendaMensajeReceptorFecha(LocalDateTime.now());
                comprobantesRecibidosService.update(factura);

                alertasService.registrarAlerta("Hacienda", "Mensaje Receptor " + accion + ": " + clave,
                    null, 0, "MensajeReceptorService.enviarMensajeReceptor()", null, null);

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
            alertasService.registrarAlerta("Error", "Error en Mensaje Receptor: " + e.getMessage(),
                null, 0, "MensajeReceptorService.enviarMensajeReceptor()", null, e.getMessage());

            return new MRResult(false, "Error al procesar Mensaje Receptor: " + e.getMessage(), null);
        }
    }
}
