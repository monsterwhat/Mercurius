package Services;

import Models.ConfiguracionAplicacion;
import Models.ComprobantesEmitidos;
import Models.Encabezado.Encabezado;
import Services.Strategies.DocumentoStrategy;
import Services.Strategies.DocumentoStrategyFactory;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.xml.bind.JAXBException;

import org.jboss.logging.Logger;

/**
 * Facade that routes electronic invoice operations to either the Fides API
 * or the direct Hacienda API based on the {@link ConfiguracionAplicacion#useFides} flag.
 * <p>
 * Only one provider is active at a time. The old direct Hacienda code paths
 * are fully preserved as the fallback when Fides is disabled.
 * </p>
 *
 * <h3>Submission flow:</h3>
 * <ul>
 *   <li><b>Fides mode</b>: Extracts invoice data from the comprobante entity,
 *       sends it to Fides which handles XML generation, signing, and submission.</li>
 *   <li><b>Direct mode</b>: Generates XML via {@link DocumentoStrategy},
 *       signs with {@link HaciendaSigner}, submits via {@link HaciendaApiService}.</li>
 * </ul>
 *
 * <h3>Status checks &amp; MensajeReceptor:</h3>
 * Always route through {@link HaciendaApiService} since these are
 * Hacienda-native operations that Fides does not replace.
 */
@ApplicationScoped
public class HaciendaServiceFacade {

    private static final Logger LOG = Logger.getLogger(HaciendaServiceFacade.class);

    @Inject
    private AppSettingsService appSettingsService;

    @Inject
    private FidesApiService fidesApiService;

    @Inject
    private HaciendaApiService haciendaApiService;

    @Inject
    private HaciendaSigner haciendaSigner;

    @Inject
    private DocumentoStrategyFactory strategyFactory;

    // ── Unified result types ──────────────────────────────────────────────

    /** Result of a document submission (via Fides or direct Hacienda). */
    public static class SubmitResult {
        public boolean success;
        public String estado;        // ACEPTADO, RECHAZADO, ENVIADO, etc.
        public String errorMessage;

        public static SubmitResult accepted() {
            SubmitResult r = new SubmitResult();
            r.success = true;
            r.estado = "ACEPTADO";
            return r;
        }

        public static SubmitResult rejected(String message) {
            SubmitResult r = new SubmitResult();
            r.success = false;
            r.estado = "RECHAZADO";
            r.errorMessage = message;
            return r;
        }

        public static SubmitResult error(String message) {
            SubmitResult r = new SubmitResult();
            r.success = false;
            r.estado = "ERROR";
            r.errorMessage = message;
            return r;
        }

        public static SubmitResult pending(String message) {
            SubmitResult r = new SubmitResult();
            r.success = true;
            r.estado = "PENDIENTE";
            r.errorMessage = message;
            return r;
        }
    }

    // ── Configuration ────────────────────────────────────────────────────

    /** Returns true when Fides mode is active in ConfiguracionAplicacion. */
    public boolean isFidesEnabled() {
        try {
            ConfiguracionAplicacion settings = appSettingsService.returnCurrent();
            return settings != null && settings.isUseFides();
        } catch (RuntimeException e) {
            LOG.warn("Failed to read ConfiguracionAplicacion for Fides flag", e);
            return false;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Public API
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Submits a comprobante to Hacienda through the active provider.
     *
     * @param comprobante the fully-persisted comprobante with encabezado, detalles,
     *                    resumen, and clave set
     * @return SubmitResult with success/failure and estado
     */
    public @Nonnull SubmitResult submitDocument(@Nonnull ComprobantesEmitidos comprobante) {
        if (isFidesEnabled()) {
            return submitViaFides(comprobante);
        } else {
            return submitViaDirectHacienda(comprobante);
        }
    }

    /**
     * Checks the Hacienda status of a document by its access key (clave).
     * Always routes through {@link HaciendaApiService} regardless of Fides mode.
     */
    public @Nonnull HaciendaApiService.ApiResponse checkStatus(@Nonnull String clave) {
        return haciendaApiService.checkInvoiceStatus(clave);
    }

    /**
     * Sends an acceptance (MensajeReceptor) for a received invoice.
     * Always goes through {@link HaciendaApiService}.
     */
    public @Nonnull HaciendaApiService.ApiResponse acceptInvoice(
            @Nonnull String clave, @Nonnull String xmlAcceptance,
            @Nonnull String emisorTipoId, @Nonnull String emisorNumeroId,
            @Nonnull String receptorTipoId, @Nonnull String receptorNumeroId) {
        return haciendaApiService.acceptInvoice(clave, xmlAcceptance,
                emisorTipoId, emisorNumeroId, receptorTipoId, receptorNumeroId);
    }

    /**
     * Sends a rejection (MensajeReceptor) for a received invoice.
     * Always goes through {@link HaciendaApiService}.
     */
    public @Nonnull HaciendaApiService.ApiResponse rejectInvoice(
            @Nonnull String clave, @Nonnull String xmlRejection,
            @Nonnull String emisorTipoId, @Nonnull String emisorNumeroId,
            @Nonnull String receptorTipoId, @Nonnull String receptorNumeroId) {
        return haciendaApiService.rejectInvoice(clave, xmlRejection,
                emisorTipoId, emisorNumeroId, receptorTipoId, receptorNumeroId);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Fides submission path
    // ═════════════════════════════════════════════════════════════════════

    private @Nonnull SubmitResult submitViaFides(@Nonnull ComprobantesEmitidos comprobante) {
        try {
            ConfiguracionAplicacion appSettings = appSettingsService.returnCurrent();
            if (appSettings == null) {
                return SubmitResult.error("No hay configuracion para enviar comprobante");
            }

            FidesApiService.InvoiceData invoiceData = buildInvoiceData(comprobante, appSettings);

            FidesApiService.FidesResponse fidesResp = fidesApiService.submitToHaciendaViaFides(invoiceData);

            if (fidesResp.success) {
                // Identity check: Fides must file under OUR clave. A mismatch
                // means Hacienda accepted a different document than the one
                // stored here — reject loudly instead of marking ACEPTADO.
                String enviada = comprobante.getHaciendaClave();
                if (enviada != null && !enviada.isBlank()
                        && fidesResp.filedAccessKey != null
                        && !enviada.equals(fidesResp.filedAccessKey)) {
                    LOG.error("Fides radico otra clave: enviada=" + enviada
                            + " radicada=" + fidesResp.filedAccessKey
                            + " | source=HaciendaServiceFacade.submitViaFides()");
                    return SubmitResult.rejected("Fides radico una clave distinta a la enviada"
                            + " (enviada " + enviada + ", radicada " + fidesResp.filedAccessKey + ")");
                }
                return SubmitResult.accepted();
            } else {
                return SubmitResult.rejected(fidesResp.errorMessage != null
                        ? fidesResp.errorMessage : "Fides/Hacienda rechazo el comprobante");
            }

        } catch (RuntimeException e) {
            LOG.error("Fides submission error", e);
            return SubmitResult.error("Error al enviar via Fides: " + e.getMessage());
        }
    }

    /**
     * Extracts invoice data from a persisted ComprobantesEmitidos into
     * the Fides API InvoiceData DTO.
     */
    private FidesApiService.InvoiceData buildInvoiceData(
            @Nonnull ComprobantesEmitidos comprobante, @Nonnull ConfiguracionAplicacion appSettings) {
        FidesApiService.InvoiceData data = new FidesApiService.InvoiceData();

        data.issuerTaxId = appSettings.getIdentificacion();
        data.issuerName = appSettings.getNombreNegocio();

        Encabezado enc = comprobante.getEncabezado();
        if (enc != null && enc.getReceptor() != null) {
            data.receiverTaxId = enc.getReceptor().getIdentificacion() != null
                    ? enc.getReceptor().getIdentificacion().getNumero() : null;
            data.receiverName = enc.getReceptor().getNombre();
        }

        data.accessKey = comprobante.getHaciendaClave();
        if (enc != null && enc.getNumeroConsecutivo() != null
                && !enc.getNumeroConsecutivo().isBlank()) {
            data.consecutivo = enc.getNumeroConsecutivo();
        }
        if (enc != null) {
            if (enc.getCondicionVenta() != null && !enc.getCondicionVenta().isBlank()) {
                data.condicionVenta = enc.getCondicionVenta().trim();
            }
            if (enc.getCondicionVentaOtros() != null && !enc.getCondicionVentaOtros().isBlank()) {
                data.condicionVentaOtros = enc.getCondicionVentaOtros().trim();
            }
            if (enc.getPlazoCredito() != null && !enc.getPlazoCredito().isBlank()) {
                try {
                    data.plazoCredito = Integer.valueOf(enc.getPlazoCredito().trim());
                } catch (NumberFormatException e) {
                    LOG.warn("PlazoCredito no numérico '" + enc.getPlazoCredito()
                            + "': se omite en el envío a Fides"
                            + " | source=HaciendaServiceFacade.buildInvoiceData()");
                }
            }
            String actividad = enc.getCodigoActividadEmisor();
            if ((actividad == null || actividad.isBlank()) && appSettings.getCodigoActividad() != null) {
                actividad = appSettings.getCodigoActividad();
            }
            if (actividad != null && !actividad.isBlank()) {
                data.codigoActividadEmisor = actividad.trim();
            }
        }

        if (comprobante.getDetalles() != null
                && comprobante.getDetalles().getLineasDetalle() != null) {
            data.items = new java.util.ArrayList<>();
            for (Models.Detalles.LineaDetalle linea : comprobante.getDetalles().getLineasDetalle()) {
                FidesApiService.InvoiceData.ItemData item =
                        new FidesApiService.InvoiceData.ItemData();
                item.code = linea.getCodigoCabys();
                item.description = linea.getDetalle();
                item.quantity = linea.getCantidad() != null
                        ? linea.getCantidad().toPlainString() : "1";
                item.unitPrice = linea.getPrecioUnitario() != null
                        ? linea.getPrecioUnitario().toPlainString() : "0";
                if (linea.getImpuestos() != null && !linea.getImpuestos().isEmpty()) {
                    item.taxRate = linea.getImpuestos().get(0).getTarifa() != null
                            ? linea.getImpuestos().get(0).getTarifa().toPlainString() : null;
                }
                data.items.add(item);
            }
        }

        data.total = comprobante.getResumen() != null
                && comprobante.getResumen().getTotalComprobante() != null
                ? comprobante.getResumen().getTotalComprobante().toPlainString() : "0";

        return data;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Direct Hacienda submission path
    // ═════════════════════════════════════════════════════════════════════

    private @Nonnull SubmitResult submitViaDirectHacienda(@Nonnull ComprobantesEmitidos comprobante) {
        try {
            ConfiguracionAplicacion appSettings = appSettingsService.returnCurrent();
            if (appSettings == null) {
                return SubmitResult.error("No hay configuracion de Hacienda");
            }

            String clave = comprobante.getHaciendaClave();
            if (clave == null || clave.isEmpty()) {
                return SubmitResult.error("Comprobante sin clave de Hacienda");
            }

            // 1. Determine document type and build XML
            String docCode = comprobante.getEncabezado() != null
                    ? comprobante.getEncabezado().getCodigoDocumento() : null;
            DocumentoStrategy strategy = strategyFactory.forCode(docCode);

            String xmlContent;
            try {
                xmlContent = strategy.buildXml(comprobante);
            } catch (JAXBException e) {
                return SubmitResult.error("Error generando XML: " + e.getMessage());
            }

            // 2. Sign the XML
            HaciendaSigner.SignResult signResult = haciendaSigner.signXml(xmlContent);
            if (!signResult.success) {
                return SubmitResult.error("Error al firmar XML: " + signResult.errorMessage);
            }

            // 3. Build sender/receiver IDs
            String emisorTipo = appSettings.getTipoIdentificacion();
            String emisorNumero = appSettings.getIdentificacion();
            String receptorTipo = "01";
            String receptorNumero = "000000000";

            if (comprobante.getEncabezado() != null
                    && comprobante.getEncabezado().getReceptor() != null
                    && comprobante.getEncabezado().getReceptor().getIdentificacion() != null) {
                receptorTipo = comprobante.getEncabezado().getReceptor().getIdentificacion().getTipo();
                receptorNumero = comprobante.getEncabezado().getReceptor().getIdentificacion().getNumero();
            }

            // 4. Submit and wait for result
            HaciendaApiService.ApiResponse apiResponse = haciendaApiService.submitAndWait(
                    clave, signResult.signedXml,
                    emisorTipo, emisorNumero, receptorTipo, receptorNumero);

            if (apiResponse.isSuccess()) {
                return SubmitResult.accepted();
            } else {
                return SubmitResult.rejected(apiResponse.errorMessage != null
                        ? apiResponse.errorMessage : "Hacienda rechazo el comprobante");
            }

        } catch (RuntimeException e) {
            LOG.error("Direct Hacienda submission error", e);
            return SubmitResult.error("Error al enviar a Hacienda: " + e.getMessage());
        }
    }
}
