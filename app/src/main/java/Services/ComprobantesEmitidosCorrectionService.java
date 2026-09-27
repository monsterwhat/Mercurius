package Services;

import Models.Encabezado.MedioPago;
import Models.Cabys;
import Models.ComprobantesEmitidos;
import Services.HaciendaServiceFacade;
import Models.Detalles.DetalleServicio;
import Models.Detalles.LineaDetalle;
import Models.Detalles.OtroCargo;
import Models.Encabezado.Encabezado;
import Models.Encabezado.Receptor;
import Models.Referencias.InformacionReferencia;
import Models.Resumen.ResumenFactura;
import org.jboss.logging.Logger;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Auto-correction service for emitted invoices rejected by Hacienda.
 * Attempts to fix common rejection issues (wrong CAByS, tax calculation, totals)
 * and resend automatically. Tracks attempts to prevent infinite retry loops.
 *
 * <p><b>Un rechazo no se corrige con nota de crédito (Art. 19 del Reglamento de
 * Comprobantes Electrónicos).</b> El comprobante rechazado no tiene validez fiscal
 * y el emisor debe emitir inmediatamente un comprobante NUEVO que lo referencie;
 * el reglamento lo dice en términos —"Para efectos tributarios no debe realizarse
 * la respectiva nota de crédito"— y el material del MH lo confirma: los
 * comprobantes rechazados no requieren notas de crédito. Este servicio emite
 * entonces una re-emisión del mismo tipo con el código 16 "Sustituye
 * comprobante electrónico rechazado" (nota 9 de los XSD oficiales v4.4), que es
 * exactamente lo que este servicio reemplaza: la nota de crédito interna que se
 * creaba aquí era una vía fiscalmente incorrecta. La nota de crédito sigue
 * existiendo para devoluciones reales sobre comprobantes aceptados, en
 * {@code Controllers.Api.App.DevolucionesResource}, que además se niega a
 * emitirla contra un documento rechazado.
 *
 * @see SustitucionComprobanteService
 */
@Named
@ApplicationScoped
public class ComprobantesEmitidosCorrectionService {

    private static final Logger LOG = Logger.getLogger(ComprobantesEmitidosCorrectionService.class);

    @Inject
    private @Nonnull ComprobantesEmitidosService comprobantesEmitidosService;

    @Inject
    private @Nonnull SustitucionComprobanteService sustitucionComprobanteService;

    @Inject
    private @Nonnull CabysService cabysService;

    @Inject
    private @Nonnull HaciendaServiceFacade haciendaServiceFacade;

    @Inject
    private @Nonnull PrevalidationConfigService prevalidationConfigService;

    // ─── Public API ─────────────────────────────────────────────────

    /**
     * Checks whether a rejected invoice can be re-emitted (Art. 19).
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
     * Main orchestrator: re-emits a rejected invoice as a NEW document that
     * references the rejected one (Art. 19, código 16). No credit note is
     * created — a rejected document has no fiscal validity, so it cannot be
     * credited, only replaced. Increments correctionAttempts even on failure to
     * prevent infinite retries.
     *
     * <p><b>Devuelve el comprobante REEMPLAZO ya persistido, no una promesa de
     * él.</b> El id se lee del propio objeto que {@code create()} acaba de
     * insertar dentro de esta transacción, así que el que llama no tiene que
     * re-consultar la base para saber si la re-emisión ocurrió: el valor
     * presente es la prueba, y una consulta posterior no lo es —depende de que
     * la fila ya sea visible para otra sesión, que es justo lo que no puede
     * darse por cierto en la misma petición. Todos los caminos en que no se
     * emite nada (rechazo no automatizable, clave que no se puede generar,
     * Hacienda que rechaza la re-emisión, excepción) devuelven
     * {@link Optional#empty()} y nunca un id.
     *
     * @return el id del comprobante re-emitido y persistido, o vacío si no se
     *         emitió ninguno
     */
    @jakarta.transaction.Transactional
    public @Nonnull Optional<Long> corregirFactura(@Nonnull ComprobantesEmitidos factura) {
        // The caller may hand us an entity still attached to ANOTHER session: both
        // the scheduler and the REST resource invoke this from a worker thread, so
        // the rejected document's Encabezado.medioPago PersistentCollection stays
        // owned by the session that loaded it. Persisting the clone then cascades
        // into a collection belonging to a different session and Hibernate throws
        // "Illegal attempt to associate a collection with two open sessions",
        // which made the re-emission fail silently at create(). Re-read the
        // document inside THIS transaction and use only that instance from here
        // on; the argument is demoted to a carrier for the id.
        ComprobantesEmitidos facturaDeEstaSesion = comprobantesEmitidosService.find(factura.getId());
        if (facturaDeEstaSesion != null) {
            factura = facturaDeEstaSesion;
        }
        String clave = factura.getHaciendaClave();
        try {
                        LOG.info("Iniciando re-emisión por rechazo para factura: " + clave + " | source=" + "ComprobantesEmitidosCorrectionService.corregirFactura()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));

            String motivoRechazo = factura.getEncabezado() != null
                ? factura.getEncabezado().getMotivoRechazo() : null;

            Estrategia estrategia = determinarEstrategia(motivoRechazo);
            if (estrategia == Estrategia.NO_AUTOMATIZABLE) {
                                LOG.info("Re-emisión no posible para: " + clave + " - motivo: " + motivoRechazo + " | source=" + "ComprobantesEmitidosCorrectionService.corregirFactura()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
                incrementarAttempts(factura);
                return Optional.empty();
            }

            // Clone and fix the invoice data
            ComprobantesEmitidos nuevaFactura = clonarFactura(factura, estrategia);

            // Art. 19: la re-emisión queda referenciada al rechazado con el código 16
            // y conserva su periodo fiscal, para que el efecto contable caiga donde
            // corresponde.
            aplicarReferenciaReemision(factura, nuevaFactura, motivoRechazo);

            // Verify clave is set on the clone
            String nuevaClave = nuevaFactura.getHaciendaClave();
            if (nuevaClave == null || nuevaClave.isEmpty()) {
                                LOG.warn("No se pudo generar clave para la factura re-emitida" + " | source=" + "ComprobantesEmitidosCorrectionService.corregirFactura()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
                incrementarAttempts(factura);
                return Optional.empty();
            }

            HaciendaServiceFacade.SubmitResult result = haciendaServiceFacade.submitDocument(nuevaFactura);
            // Vacío mientras no haya un comprobante emitido Y persistido: sólo se
            // llena en la rama aceptada y sólo si la inserción produjo id.
            Optional<Long> idReemision = Optional.empty();
            if (result.success) {
                nuevaFactura.setHaciendaEstado("ACEPTADO");
                nuevaFactura.setHaciendaFechaEnvio(LocalDateTime.now());
                nuevaFactura.setHaciendaFechaRespuesta(LocalDateTime.now());
                if (nuevaFactura.getEncabezado() != null) {
                    nuevaFactura.getEncabezado().setEstado("ACEPTADO");
                }
                comprobantesEmitidosService.create(nuevaFactura);

                // create() se traga su propia PersistenceException, así que la
                // única prueba de que la fila existe es el id que la asignación
                // dejó en la entidad. Sin id no hay sustituto: se reporta vacío
                // en vez de dar por buena una re-emisión que nadie persistió.
                Long idPersistido = nuevaFactura.getId();
                if (idPersistido == null) {
                                LOG.warn("La re-emisión de " + clave + " fue aceptada por Hacienda pero no quedó persistida, así que no hay comprobante sustitutivo" + " | source=" + "ComprobantesEmitidosCorrectionService.corregirFactura()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
                } else {
                                LOG.info("Re-emisión por rechazo aceptada: " + clave + " -> nueva clave: " + nuevaClave + " (código 16, Art. 19; no se emitió nota de crédito)" + " | source=" + "ComprobantesEmitidosCorrectionService.corregirFactura()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
                    idReemision = Optional.of(idPersistido);
                }
            } else {
                                LOG.warn("Hacienda rechazó la factura re-emitida: " + result.errorMessage + " | source=" + "ComprobantesEmitidosCorrectionService.corregirFactura()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(result.errorMessage));
            }

            incrementarAttempts(factura);
            return idReemision;

        } catch (RuntimeException e) {
                        LOG.warn("Error en la re-emisión por rechazo de " + clave + ": " + e.getMessage() + " | source=" + "ComprobantesEmitidosCorrectionService.corregirFactura()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            incrementarAttempts(factura);
            return Optional.empty();
        }
    }

    /**
     * Referencia de la re-emisión (Art. 19): código 16 "Sustituye comprobante
     * electrónico rechazado" apuntando al documento rechazado, y verificación de
     * que el reemplazo cae en el mismo periodo fiscal que el original —sin eso el
     * efecto contable de la sustitución quedaría en un periodo distinto al del
     * documento que se está sustituyendo.
     *
     * <p>El documento de referencia conserva la fecha de emisión del rechazado, lo
     * que además mantiene consistente la fecha que ya viaja en las posiciones 4-9
     * de la clave re-emitida.</p>
     */
    private void aplicarReferenciaReemision(@Nonnull ComprobantesEmitidos original,
                                            @Nonnull ComprobantesEmitidos reemplazo,
                                            @Nullable String motivoRechazo) {
        String codigoDocumentoNuevo = reemplazo.getEncabezado() != null
            ? reemplazo.getEncabezado().getCodigoDocumento()
            : (original.getEncabezado() != null ? original.getEncabezado().getCodigoDocumento() : null);

        String razon = motivoRechazo != null && !motivoRechazo.isBlank()
            ? "Re-emisión por rechazo de Hacienda (Art. 19, código 16). No corresponde nota de crédito. Motivo: " + motivoRechazo
            : null; // SustitucionComprobanteService pone el texto por defecto y recorta a 180

        InformacionReferencia referencia = sustitucionComprobanteService
                .referenciaReemisionPorRechazo(original, codigoDocumentoNuevo, razon);
        reemplazo.setInformacionReferencia(new ArrayList<>(List.of(referencia)));
        sustitucionComprobanteService.exigirMismoPeriodo(original, reemplazo);
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

    /**
     * Deep-clones a ComprobantesEmitidos and applies fixes per the given strategy.
     * Sets a new haciendaClave on the clone.
     * <p>
     * The clone keeps the original's {@code fechaEmision} (and therefore its fiscal
     * period) on purpose: the re-emitted clave reuses positions 1-21 of the original,
     * which include the DDMMYY emission date, so changing the date would make the
     * document contradict its own clave and Hacienda would reject it.
     */
    /**
     * Deep-copies the header's payment methods.
     *
     * <p>This cannot share the original list. {@code medioPago} is a
     * {@code @OneToMany} whose collection instance is already bound to the open
     * persistence session that loaded the rejected document. Handing that same
     * instance to the clone makes Hibernate persist the new encabezado with a
     * collection owned by another session:
     * {@code Illegal attempt to associate a collection with two open sessions:
     * Collection: [Encabezado.medioPago with owner id '...']}. The entity
     * creation then fails and the re-emission silently does not happen.
     * {@code lineasDetalle} and {@code otrosCargos} were already copied this
     * way; this collection was the one that was missed.</p>
     */
    private static java.util.List<MedioPago> clonarMediosPago(
            java.util.List<MedioPago> original, Encabezado encabezadoNuevo) {
        if (original == null) {
            return null;
        }
        java.util.List<MedioPago> copia = new java.util.ArrayList<>(original.size());
        for (MedioPago medio : original) {
            if (medio == null) {
                continue;
            }
            MedioPago nuevo = new MedioPago();
            nuevo.setMedioPago(medio.getMedioPago());
            nuevo.setSchemaVersion(medio.getSchemaVersion());
            nuevo.setComprobante(encabezadoNuevo);
            copia.add(nuevo);
        }
        return copia;
    }

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
            encNuevo.setMedioPago(clonarMediosPago(encOriginal.getMedioPago(), encNuevo));
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
     * Generates a new valid 50-character Hacienda clave for the corrected invoice.
     * Increments the 20-digit consecutive, generates a fresh 7-digit security code,
     * and computes the check digit (módulo 10 / Luhn variant).
     * <p>
     * The country+date+identification prefix (positions 1-21) is copied verbatim
     * from the original clave, so an alphanumeric emitter ID (ClaveType is
     * [a-zA-Z0-9] since the 2026-04-22 v4.4 revision) survives untouched. The
     * check digit below is still digits-only, so an alphanumeric ID propagates the
     * {@link HaciendaSigner#calcularDigitoVerificador} IllegalArgumentException
     * until DGT publishes the alphanumeric check digit rule.
     * </p>
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
