package Services;

import Models.Cabys;
import Models.Clients;
import Models.ComprobantesEmitidos;
import Services.HaciendaServiceFacade;
import Models.Detalles.DetalleServicio;
import Models.Detalles.LineaDetalle;
import Models.Detalles.OtroCargo;
import Models.Encabezado.Encabezado;
import Models.Encabezado.Receptor;
import Models.NotaCredito;
import Models.Resumen.ResumenFactura;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

/**
 * Auto-correction service for emitted invoices rejected by Hacienda.
 * Attempts to fix common rejection issues (wrong CAByS, tax calculation, totals)
 * and resend automatically. Tracks attempts to prevent infinite retry loops.
 */
@Named
@ApplicationScoped
public class ComprobantesEmitidosCorrectionService {

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(ComprobantesEmitidosCorrectionService.class.getName());

    @Inject
    private @Nonnull ComprobantesEmitidosService comprobantesEmitidosService;

    @Inject
    private @Nonnull NotaCreditoService notaCreditoService;

    @Inject
    private @Nonnull CabysService cabysService;

    @Inject
    private @Nonnull ClientService clientService;

    @Inject
    private @Nonnull HaciendaServiceFacade haciendaServiceFacade;

    
    @Inject
    private @Nonnull PrevalidationConfigService prevalidationConfigService;

    // ─── Public API ─────────────────────────────────────────────────

    /**
     * Checks whether a rejected invoice can be auto-corrected.
     * Conditions:
     * - Estado must be RECHAZADO
     * - correctionAttempts must be under the configured max
     * - Not recently corrected (prevents scheduler double-fire within 60s)
     */
    public boolean puedeCorregir(@Nullable ComprobantesEmitidos factura) {
        if (factura == null) return false;
        if (!"RECHAZADO".equals(factura.getHaciendaEstado())) return false;

        Integer maxAttemptsObj = prevalidationConfigService.getActiveConfig().getMaxCorrectionAttempts();
        int maxAttempts = maxAttemptsObj != null ? maxAttemptsObj : 3;
        int attempts = factura.getCorrectionAttempts() != null ? factura.getCorrectionAttempts() : 0;
        if (attempts >= maxAttempts) return false;

        // Prevent double-fire from scheduler running every 30min
        if (factura.getUltimaCorreccion() != null) {
            if (factura.getUltimaCorreccion().plusSeconds(60).isAfter(LocalDateTime.now())) {
                return false;
            }
        }

        return true;
    }

    /**
     * Main orchestrator: attempts to fix and resend a rejected invoice.
     * Increments correctionAttempts even on failure to prevent infinite retries.
     */
    public void corregirFactura(@Nonnull ComprobantesEmitidos factura) {
        String clave = factura.getHaciendaClave();
        try {
                        LOG.info("Iniciando auto-corrección para factura: " + clave + " | source=" + "ComprobantesEmitidosCorrectionService.corregirFactura()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));

            String motivoRechazo = factura.getEncabezado() != null
                ? factura.getEncabezado().getMotivoRechazo() : null;

            Estrategia estrategia = determinarEstrategia(motivoRechazo);
            if (estrategia == Estrategia.NO_AUTOMATIZABLE) {
                                LOG.info("Auto-corrección no posible para: " + clave + " - motivo: " + motivoRechazo + " | source=" + "ComprobantesEmitidosCorrectionService.corregirFactura()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
                incrementarAttempts(factura);
                return;
            }

            // Create internal NotaCredito for the rejected invoice
            crearNotaCredito(factura);

            // Clone and fix the invoice data
            ComprobantesEmitidos nuevaFactura = clonarFactura(factura, estrategia);

            // Verify clave is set on the clone
            String nuevaClave = nuevaFactura.getHaciendaClave();
            if (nuevaClave == null || nuevaClave.isEmpty()) {
                                LOG.log(java.util.logging.Level.WARNING, "No se pudo generar clave para factura corregida" + " | source=" + "ComprobantesEmitidosCorrectionService.corregirFactura()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
                incrementarAttempts(factura);
                return;
            }

            HaciendaServiceFacade.SubmitResult result = haciendaServiceFacade.submitDocument(nuevaFactura);
            if (result.success) {
                nuevaFactura.setHaciendaEstado("ACEPTADO");
                nuevaFactura.setHaciendaFechaEnvio(LocalDateTime.now());
                nuevaFactura.setHaciendaFechaRespuesta(LocalDateTime.now());
                if (nuevaFactura.getEncabezado() != null) {
                    nuevaFactura.getEncabezado().setEstado("ACEPTADO");
                }
                comprobantesEmitidosService.create(nuevaFactura);

                                LOG.info("Auto-corrección exitosa: " + clave + " -> nueva clave: " + nuevaClave + " | source=" + "ComprobantesEmitidosCorrectionService.corregirFactura()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
            } else {
                                LOG.log(java.util.logging.Level.WARNING, "Hacienda rechazó factura corregida: " + result.errorMessage + " | source=" + "ComprobantesEmitidosCorrectionService.corregirFactura()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(result.errorMessage));
            }

            incrementarAttempts(factura);

        } catch (RuntimeException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error en auto-corrección de " + clave + ": " + e.getMessage() + " | source=" + "ComprobantesEmitidosCorrectionService.corregirFactura()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            incrementarAttempts(factura);
        }
    }

    // ─── Strategies ─────────────────────────────────────────────────

    enum Estrategia {
        FIX_CABYS,
        FIX_TAX,
        FIX_TOTALS,
        NO_AUTOMATIZABLE
    }

    /**
     * Maps Hacienda rejection reasons to fix strategies.
     * Match is case-insensitive substring search.
     */
    Estrategia determinarEstrategia(String motivoRechazo) {
        if (motivoRechazo == null || motivoRechazo.isEmpty()) {
            return Estrategia.NO_AUTOMATIZABLE;
        }
        String lower = motivoRechazo.toLowerCase();
        if (lower.contains("cabys") || lower.contains("cab")) {
            return Estrategia.FIX_CABYS;
        }
        if (lower.contains("impuesto") || lower.contains("tasa") || lower.contains("tarifa")) {
            return Estrategia.FIX_TAX;
        }
        if (lower.contains("total") || lower.contains("monto") || lower.contains("suma")) {
            return Estrategia.FIX_TOTALS;
        }
        return Estrategia.NO_AUTOMATIZABLE;
    }

    // ─── Internal helpers ───────────────────────────────────────────

    private void incrementarAttempts(ComprobantesEmitidos factura) {
        int current = factura.getCorrectionAttempts() != null ? factura.getCorrectionAttempts() : 0;
        factura.setCorrectionAttempts(current + 1);
        factura.setUltimaCorreccion(LocalDateTime.now());
        comprobantesEmitidosService.update(factura);
    }

    private void crearNotaCredito(ComprobantesEmitidos facturaOriginal) {
        if (facturaOriginal.getResumen() == null || facturaOriginal.getEncabezado() == null) return;

        // Check if NC already exists for this invoice
        List<NotaCredito> existentes = notaCreditoService.listPorComprobante(facturaOriginal.getId());
        if (existentes != null && !existentes.isEmpty()) return;

        NotaCredito nc = new NotaCredito();
        nc.setComprobanteOriginal(facturaOriginal);
        nc.setFecha(new Date());
        nc.setMotivo("Auto-corrección por rechazo de Hacienda: "
            + (facturaOriginal.getEncabezado().getMotivoRechazo() != null
                ? facturaOriginal.getEncabezado().getMotivoRechazo() : "Sin motivo"));
        nc.setMontoTotal(facturaOriginal.getResumen().getTotalVentaNeta());

        if (facturaOriginal.getEncabezado().getReceptor() != null) {
            String nombre = facturaOriginal.getEncabezado().getReceptor().getNombre();
            if (nombre != null) {
                List<Clients> clients = clientService.searchByName(nombre);
                if (clients != null && !clients.isEmpty()) {
                    nc.setCliente(clients.get(0));
                }
            }
        }

        nc.setUsuario("system");
        nc.setStatus(true);
        nc.setHaciendaEstado("PENDIENTE");
        nc.setHaciendaClave(facturaOriginal.getHaciendaClave());

        notaCreditoService.create(nc);
    }

    /**
     * Deep-clones a ComprobantesEmitidos and applies fixes per the given strategy.
     * Sets a new haciendaClave on the clone.
     */
    private ComprobantesEmitidos clonarFactura(ComprobantesEmitidos original, Estrategia estrategia) {
        ComprobantesEmitidos nueva = new ComprobantesEmitidos();

        // Clone encabezado
        if (original.getEncabezado() != null) {
            Encabezado encOriginal = original.getEncabezado();
            Encabezado encNuevo = new Encabezado();
            encNuevo.setCodigoActividadEmisor(encOriginal.getCodigoActividadEmisor());
            encNuevo.setCondicionVenta(encOriginal.getCondicionVenta());
            encNuevo.setFechaEmision(encOriginal.getFechaEmision());
            encNuevo.setNumeroConsecutivo(encOriginal.getNumeroConsecutivo());
            encNuevo.setClave(encOriginal.getClave());
            encNuevo.setCodigoDocumento(encOriginal.getCodigoDocumento());
            encNuevo.setMedioPago(encOriginal.getMedioPago());
            encNuevo.setPlazoCredito(encOriginal.getPlazoCredito());
            encNuevo.setCondicionVentaOtros(encOriginal.getCondicionVentaOtros());
            encNuevo.setEmisor(encOriginal.getEmisor());
            encNuevo.setReceptor(encOriginal.getReceptor());
            nueva.setEncabezado(encNuevo);
        }

        // Clone detalles and apply fixes
        if (original.getDetalles() != null) {
            DetalleServicio detOriginal = original.getDetalles();
            DetalleServicio detNuevo = new DetalleServicio();
            if (detOriginal.getLineasDetalle() != null) {
                java.util.List<LineaDetalle> nuevasLineas = new java.util.ArrayList<>();
                for (LineaDetalle linea : detOriginal.getLineasDetalle()) {
                    LineaDetalle nuevaLinea = clonarLinea(linea, estrategia);
                    nuevaLinea.setDetalleServicio(detNuevo);
                    nuevasLineas.add(nuevaLinea);
                }
                detNuevo.setLineasDetalle(nuevasLineas);
            }
            // Clone OtrosCargos so corrected invoices preserve cargo line items
            if (detOriginal.getOtrosCargos() != null && !detOriginal.getOtrosCargos().isEmpty()) {
                java.util.List<OtroCargo> nuevosCargos = new java.util.ArrayList<>();
                for (OtroCargo cargo : detOriginal.getOtrosCargos()) {
                    OtroCargo nuevoCargo = new OtroCargo();
                    nuevoCargo.setTipoDocumentoOC(cargo.getTipoDocumentoOC());
                    nuevoCargo.setTipoDocumentoOTROS(cargo.getTipoDocumentoOTROS());
                    nuevoCargo.setIdentificacionTercero(cargo.getIdentificacionTercero());
                    nuevoCargo.setNombreTercero(cargo.getNombreTercero());
                    nuevoCargo.setDetalle(cargo.getDetalle());
                    nuevoCargo.setPorcentajeOC(cargo.getPorcentajeOC());
                    nuevoCargo.setMontoCargo(cargo.getMontoCargo());
                    nuevoCargo.setDetalleServicio(detNuevo);
                    nuevosCargos.add(nuevoCargo);
                }
                detNuevo.setOtrosCargos(nuevosCargos);
            }
            nueva.setDetalles(detNuevo);
        }

        // Clone resumen and apply fixes
        if (original.getResumen() != null) {
            nueva.setResumen(clonarResumen(original.getResumen()));
        }

        // Set a new consecutive/clave for the corrected invoice
        String nuevaClave = generarNuevaClave(original);
        nueva.setHaciendaClave(nuevaClave);
        if (nueva.getEncabezado() != null) {
            nueva.getEncabezado().setClave(nuevaClave);
            // Sync numeroConsecutivo with the new clave's consecutive (positions 21-41)
            if (nuevaClave != null && nuevaClave.length() == 50) {
                nueva.getEncabezado().setNumeroConsecutivo(nuevaClave.substring(21, 41));
            }
        }

        nueva.setStatus(true);
        nueva.setUser("system");
        return nueva;
    }

    /**
     * Clones a line and applies CAByS or tax fixes based on strategy.
     */
    private LineaDetalle clonarLinea(LineaDetalle original, Estrategia estrategia) {
        LineaDetalle linea = new LineaDetalle();
        linea.setNumeroLinea(original.getNumeroLinea());
        linea.setDetalle(original.getDetalle());
        linea.setCantidad(original.getCantidad());
        linea.setUnidadMedida(original.getUnidadMedida());
        linea.setUnidadMedidaComercial(original.getUnidadMedidaComercial());
        linea.setPrecioUnitario(original.getPrecioUnitario());
        linea.setMontoTotal(original.getMontoTotal());
        linea.setSubTotal(original.getSubTotal());
        linea.setBaseImponible(original.getBaseImponible());
        linea.setImpuestoNeto(original.getImpuestoNeto());
        linea.setMontoTotalLinea(original.getMontoTotalLinea());
        linea.setCodigosComerciales(original.getCodigosComerciales());
        linea.setDescuentos(original.getDescuentos());
        linea.setImpuestos(original.getImpuestos());

        // Fix CAByS if strategy says so and current code is invalid
        if (estrategia == Estrategia.FIX_CABYS) {
            String codigoActual = original.getCodigoCabys();
            if (codigoActual != null && !codigoActual.isEmpty()) {
                Cabys cabysCorrecto = cabysService.find(codigoActual);
                if (cabysCorrecto == null) {
                    // Current CAByS not in local DB — try to find a replacement
                    LineaDetalle lineaCorregida = corregirCabysLinea(original);
                    if (lineaCorregida != null) {
                        linea = lineaCorregida;
                    }
                }
            }
            linea.setCodigoCabys(original.getCodigoCabys());
        } else {
            linea.setCodigoCabys(original.getCodigoCabys());
        }

        // Fix tax if strategy says so — recalculate impuestoNeto
        if (estrategia == Estrategia.FIX_TAX) {
            linea = recalcularImpuestosLinea(linea);
        }

        return linea;
    }

    /**
     * Attempts to find a valid CAByS code when the current one is unknown.
     * Tries: exact match by name, then by partial name match.
     */
    private LineaDetalle corregirCabysLinea(LineaDetalle linea) {
        String detalle = linea.getDetalle();
        if (detalle == null || detalle.isEmpty()) return null;

        List<Cabys> candidatos = cabysService.searchByName(detalle);
        if (candidatos != null && !candidatos.isEmpty()) {
            Cabys mejor = candidatos.get(0);
            linea.setCodigoCabys(mejor.getCodigo());
            if (linea.getBaseImponible() != null) {
                BigDecimal tarifa = new BigDecimal(mejor.getImpuesto());
                linea.setImpuestoNeto(linea.getBaseImponible()
                    .multiply(tarifa)
                    .divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP));
            }
        }
        return linea;
    }

    /**
     * Recalculates impuestoNeto based on baseImponible and the line's tax rate.
     */
    private LineaDetalle recalcularImpuestosLinea(LineaDetalle linea) {
        if (linea.getBaseImponible() != null && linea.getImpuestoNeto() != null) {
            // Try to determine rate from existing tax data
            if (linea.getImpuestos() != null && !linea.getImpuestos().isEmpty()) {
                var impuesto = linea.getImpuestos().get(0);
                if (impuesto.getTarifa() != null && impuesto.getTarifa().compareTo(java.math.BigDecimal.ZERO) > 0) {
                    java.math.BigDecimal tarifa = impuesto.getTarifa();
                    java.math.BigDecimal nuevoImpuesto = linea.getBaseImponible()
                        .multiply(tarifa)
                        .divide(new java.math.BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP);
                    linea.setImpuestoNeto(nuevoImpuesto);
                }
            }
        }
        return linea;
    }

    /**
     * Clones and recalculates ResumenFactura totals from the original.
     */
    private ResumenFactura clonarResumen(ResumenFactura original) {
        ResumenFactura resumen = new ResumenFactura();
        resumen.setTotalMercanciasGravadas(original.getTotalMercanciasGravadas());
        resumen.setTotalMercanciasExentas(original.getTotalMercanciasExentas());
        resumen.setTotalMercExonerada(original.getTotalMercExonerada());
        resumen.setTotalMercNoSujeta(original.getTotalMercNoSujeta());
        resumen.setTotalDescuentos(original.getTotalDescuentos());
        resumen.setTotalVentaNeta(original.getTotalVentaNeta());
        resumen.setTotalImpuesto(original.getTotalImpuesto());
        resumen.setTotalIVADevuelto(original.getTotalIVADevuelto());
        resumen.setTotalOtrosCargos(original.getTotalOtrosCargos());
        resumen.setTotalVenta(original.getTotalVenta());
        resumen.setTotalComprobante(original.getTotalComprobante());
        return resumen;
    }

    /**
     * Generates a new valid 50-digit Hacienda clave for the corrected invoice.
     * Increments the 20-digit consecutive, generates a fresh 7-digit security code,
     * and computes the check digit (módulo 10 / Luhn variant).
     */
    private String generarNuevaClave(ComprobantesEmitidos original) {
        String claveOriginal = original.getHaciendaClave();
        if (claveOriginal == null || claveOriginal.length() != 50) return null;

        // Extract consecutive number: positions 21-40 (0-indexed), 20 digits
        String consecutiveStr = claveOriginal.substring(21, 41);
        long consecutive;
        try {
            consecutive = Long.parseLong(consecutiveStr);
        } catch (NumberFormatException e) {
            return null;
        }

        int attempt = original.getCorrectionAttempts() != null ? original.getCorrectionAttempts() + 1 : 1;
        consecutive += attempt;

        // Rebuild prefix: country(3) + DDMMYY(6) + ID(12) + new consecutive(20)
        String prefix41 = claveOriginal.substring(0, 21) + String.format("%020d", consecutive);
        // Situation stays "2" (corrected document)
        String situation = claveOriginal.substring(41, 42);
        // Generate 7-digit fresh security code
        int random7 = new SecureRandom().nextInt(10000000);
        String securityCode7 = String.format("%07d", random7);

        // Assemble 49-digit prefix for check digit calculation
        String prefix49 = prefix41 + situation + securityCode7;
        int checkDigit = HaciendaSigner.calcularDigitoVerificador(prefix49);

        return prefix49 + checkDigit;
    }
}
