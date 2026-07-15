package Services;

import Models.Cabys;
import Models.ComprobantesRecibidos;
import Models.Detalles.LineaDetalle;
import Models.Encabezado.IdentificacionReceptor;
import Models.Encabezado.Receptor;
import Models.Encabezado.Ubicacion;
import Models.Resumen.ResumenFactura;
import Models.Validacion.PrevalidationConfig;
import Models.Validacion.PrevalidationResult;
import Models.Validacion.ValidationError;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Collections;
import Models.Referencias.InformacionReferencia;

/**
 * Pre-validation service for received invoices (Comprobantes Recibidos).
 * Runs at acceptance time — validates CAByS codes, tax calculations, and
 * receptor information before allowing Mensaje Receptor acceptance.
 *
 * Four validators:
 *   validarCabys()                    — checks CAByS codes against local DB
 *   verificarCalculosImpuestos()      — line-by-line + resumen tax verification
 *   validarInfoReceptor()             — CR ID type/format, ubicacion completeness
 *   validarInformacionReferencia()    — NC/ND/REP InformacionReferencia fields
 */
@Named
@ApplicationScoped
public class ComprobantesRecibidosPrevalidationService {

    @Inject @Nonnull
    private CabysService cabysService;

    @Inject @Nonnull
    private ComprobantesRecibidosService comprobantesRecibidosService;

    @Inject @Nonnull
    private AlertasService alertasService;

    @Inject @Nonnull
    private PrevalidationConfigService prevalidationConfigService;

    @PostConstruct
    public void init() {
    }

    @Nonnull
    private PrevalidationConfig getConfig() {
        return prevalidationConfigService.getActiveConfig();
    }

    // ─── Orchestrator ─────────────────────────────────────────────────

    /**
     * Main orchestrator: runs all three validators and returns aggregated results.
     * Accepts a comprobante ID (Long).
     */
    @Nonnull
    public PrevalidationResult prevalidarCompleto(@Nullable Long comprobanteId) {
        PrevalidationResult result = new PrevalidationResult();
        if (comprobanteId == null) {
            result.addError(new ValidationError(
                ValidationError.Category.valueOf("RECEPTOR_INFO"),
                "comprobanteId", "NULL_ID",
                "El ID del comprobante no puede ser nulo"));
            return result;
        }
        result.setComprobanteId(String.valueOf(comprobanteId));

        // Load the invoice with all details
        ComprobantesRecibidos factura = comprobantesRecibidosService.findByIdWithDetails(comprobanteId);
        if (factura == null) {
            result.addError(new ValidationError(
                ValidationError.Category.valueOf("RECEPTOR_INFO"),
                "comprobanteId", "NOT_FOUND",
                "Comprobante no encontrado: " + comprobanteId));
            return result;
        }

        // Extract consecutive number for reference
        if (factura.getEncabezado() != null && factura.getEncabezado().getNumeroConsecutivo() != null) {
            result.setNumeroConsecutivo(factura.getEncabezado().getNumeroConsecutivo());
        }

        // Get line items
        List<LineaDetalle> lineas = null;
        if (factura.getDetalles() != null) {
            lineas = factura.getDetalles().getLineasDetalle();
        }

        // Extract document type code for document-aware validation
        String codigoDocumento = factura.getEncabezado() != null ? factura.getEncabezado().getCodigoDocumento() : null;

        // Run all validators
        validarCabys(lineas, result);
        verificarCalculosImpuestos(lineas, factura.getResumen(), result);
        validarInfoReceptor(factura.getEncabezado() != null ? factura.getEncabezado().getReceptor() : null, codigoDocumento, result);
        validarInformacionReferencia(factura.getInformacionReferencia(), codigoDocumento, result);

        return result;
    }

    /**
     * Pre-validates a ComprobantesRecibidos entity directly (without persisting first).
     * Used by ComprobantesRecibidosService.createWithRelatedEntities() before persisting.
     */
    @Nonnull
    public PrevalidationResult prevalidarCompleto(@Nullable ComprobantesRecibidos factura) {
        PrevalidationResult result = new PrevalidationResult();

        if (factura == null) {
            result.addError(new ValidationError(
                ValidationError.Category.valueOf("RECEPTOR_INFO"),
                "comprobante", "NULL_ENTITY",
                "La entidad ComprobantesRecibidos no puede ser nula"));
            return result;
        }

        if (factura.getEncabezado() != null && factura.getEncabezado().getNumeroConsecutivo() != null) {
            result.setNumeroConsecutivo(factura.getEncabezado().getNumeroConsecutivo());
        }

        List<LineaDetalle> lineas = null;
        if (factura.getDetalles() != null) {
            lineas = factura.getDetalles().getLineasDetalle();
        }

        String codigoDocumento = factura.getEncabezado() != null ? factura.getEncabezado().getCodigoDocumento() : null;

        validarCabys(lineas, result);
        verificarCalculosImpuestos(lineas, factura.getResumen(), result);
        validarInfoReceptor(factura.getEncabezado() != null ? factura.getEncabezado().getReceptor() : null, codigoDocumento, result);
        validarInformacionReferencia(factura.getInformacionReferencia(), codigoDocumento, result);

        return result;
    }

    /**
     * Overload accepting a comprobante ID as String (converts to Long).
     */
    @Nonnull
    public PrevalidationResult prevalidarCompleto(@Nullable String comprobanteId) {
        try {
            return prevalidarCompleto(Long.parseLong(comprobanteId));
        } catch (NumberFormatException e) {
            PrevalidationResult result = new PrevalidationResult();
            result.setComprobanteId(comprobanteId);
            result.addError(new ValidationError(
                ValidationError.Category.valueOf("RECEPTOR_INFO"),
                "comprobanteId", "INVALID_ID",
                "El ID del comprobante no es un número válido: " + comprobanteId));
            return result;
        }
    }

    // ─── CAByS Validator ──────────────────────────────────────────────

    /**
     * Validates CAByS codes against the LOCAL Cabys table only.
     * NEVER calls external Hacienda APIs.
     *
     * STRICT mode: missing codes = ERROR (reject invoice).
     * LENIENT mode: missing codes = WARNING (allow acceptance).
     */
    void validarCabys(@Nullable List<LineaDetalle> lineas, @Nonnull PrevalidationResult result) {
        if (lineas == null || lineas.isEmpty()) {
            return;
        }

        for (LineaDetalle linea : lineas) {
            String codigo = linea.getCodigoCabys();
            if (codigo == null || codigo.trim().isEmpty()) {
                result.addError(new ValidationError(
                    ValidationError.Category.valueOf("CABYS"),
                    "codigoCabys", "EMPTY_CABYS",
                    "Línea " + (linea.getNumeroLinea() != null ? linea.getNumeroLinea() : String.valueOf(lineas.indexOf(linea) + 1)) +
                    ": código CAByS faltante"));
                continue;
            }

            codigo = codigo.trim();

            if (!codigo.matches("\\d{13}")) {
                result.addError(new ValidationError(
                    ValidationError.Category.valueOf("CABYS"),
                    "codigoCabys", "INVALID_FORMAT",
                    "El código CAByS '" + codigo + "' no tiene 13 dígitos",
                    "13 dígitos", codigo));
                continue;
            }

            Cabys cabys = cabysService.find(codigo);
            if (cabys == null) {
                String msg = "El código CAByS '" + codigo + "' no fue encontrado en el catálogo local";
                if (getConfig().isCabysStrictMode()) {
                    result.addError(new ValidationError(
                        ValidationError.Category.valueOf("CABYS"),
                        "codigoCabys", "MISSING_CABYS",
                        msg));
                } else {
                    result.addWarning(new ValidationError(
                        ValidationError.Category.valueOf("CABYS"),
                        "codigoCabys", "MISSING_CABYS",
                        msg,
                        ValidationError.Severity.WARNING));
                }
                continue;
            }

            if (cabys.getEstado() != null && !cabys.getEstado().trim().isEmpty()
                    && !"ACTIVO".equalsIgnoreCase(cabys.getEstado())) {
                result.addWarning(new ValidationError(
                    ValidationError.Category.valueOf("CABYS"),
                    "codigoCabys", "INACTIVE_CABYS",
                    "El código CAByS '" + codigo + "' no está ACTIVO (estado=" + cabys.getEstado() + ")",
                    ValidationError.Severity.WARNING));
            }
        }
    }

    // ─── Tax Calculation Validator ────────────────────────────────────

    void verificarCalculosImpuestos(@Nullable List<LineaDetalle> lineas, @Nullable ResumenFactura resumen, @Nonnull PrevalidationResult result) {
        if (lineas == null || lineas.isEmpty()) {
            return;
        }

        // Costa Rica XSD requires separating merchandise (tipoTransaccion=01) from
        // services (tipoTransaccion=02) because resumen totals are split:
        //   TotalMercanciasGravadas = sum(baseImponible) for merchandise lines only
        //   TotalServGravados       = sum(baseImponible) for service lines only
        //   TotalGravado            = merchandise + services
        BigDecimal sumaBaseMercancias = BigDecimal.ZERO;
        BigDecimal sumaBaseServicios = BigDecimal.ZERO;
        BigDecimal sumaBaseImponible = BigDecimal.ZERO; // total (all lines)
        BigDecimal sumaMonto = BigDecimal.ZERO;
        BigDecimal sumaSubTotal = BigDecimal.ZERO;

        for (LineaDetalle linea : lineas) {
            String lineLabel = "Línea " + (linea.getNumeroLinea() != null ? linea.getNumeroLinea() : String.valueOf(lineas.indexOf(linea) + 1));

            // ── Line-level: impuestoNeto validation ──
            // Without exoneración: impuestoNeto = baseImponible × tarifa / 100
            // With exoneración:    impuestoNeto = baseImponible × tarifa / 100 × (1 - tarifaExonerada / 100)
            //                   or: impuestoNeto = baseImponible × tarifa / 100 - montoExoneracion
            if (linea.getBaseImponible() != null && linea.getImpuestoNeto() != null
                    && linea.getImpuestos() != null && !linea.getImpuestos().isEmpty()) {
                Models.Detalles.Impuesto imp = linea.getImpuestos().get(0);
                BigDecimal tarifa = imp.getTarifa();
                if (tarifa != null && tarifa.compareTo(BigDecimal.ZERO) != 0) {
                    BigDecimal baseImpuesto = linea.getBaseImponible()
                            .multiply(tarifa)
                            .divide(new BigDecimal("100"), 6, java.math.RoundingMode.HALF_UP);

                    // Apply exoneración if present
                    BigDecimal expectedImpuesto;
                    Models.Detalles.Exoneracion exoneracion = imp.getExoneracion();
                    if (exoneracion != null) {
                        if (exoneracion.getMontoExoneracion() != null
                                && exoneracion.getMontoExoneracion().compareTo(BigDecimal.ZERO) > 0) {
                            expectedImpuesto = baseImpuesto.subtract(exoneracion.getMontoExoneracion());
                        } else if (exoneracion.getTarifaExonerada() != null
                                && exoneracion.getTarifaExonerada().compareTo(BigDecimal.ZERO) > 0) {
                            BigDecimal factor = BigDecimal.ONE.subtract(
                                exoneracion.getTarifaExonerada()
                                    .divide(new BigDecimal("100"), 6, java.math.RoundingMode.HALF_UP));
                            expectedImpuesto = baseImpuesto.multiply(factor);
                        } else {
                            expectedImpuesto = baseImpuesto;
                        }
                    } else {
                        expectedImpuesto = baseImpuesto;
                    }
                    expectedImpuesto = expectedImpuesto.setScale(2, java.math.RoundingMode.HALF_UP);

                    BigDecimal diff = expectedImpuesto.subtract(linea.getImpuestoNeto()).abs();
                    if (diff.compareTo(getConfig().getTaxTolerance()) > 0) {
                        String exoneracionNote = (exoneracion != null) ? " [exoneración aplicada]" : "";
                        ValidationError taxError = new ValidationError(
                            ValidationError.Category.valueOf("TAX_CALCULATION"),
                            "impuestoNeto", "LINE_TAX_MISMATCH",
                            lineLabel + ": impuestoNeto (" + linea.getImpuestoNeto() +
                            ") != baseImponible (" + linea.getBaseImponible() + ") × tarifa (" + tarifa + "%) = " + expectedImpuesto +
                            ", diff=" + diff + exoneracionNote,
                            expectedImpuesto, linea.getImpuestoNeto());
                        taxError.setSeverity(ValidationError.Severity.WARNING);
                        result.addWarning(taxError);
                    }
                }
            }

            // Sum baseImponible into total
            BigDecimal baseImponibleLinea = linea.getBaseImponible() != null ? linea.getBaseImponible() : BigDecimal.ZERO;
            if (linea.getBaseImponible() == null && linea.getSubTotal() != null) {
                baseImponibleLinea = linea.getSubTotal();
            }
            sumaBaseImponible = sumaBaseImponible.add(baseImponibleLinea);

            // Separate merchandise vs services by tipoTransaccion
            // "01" = Mercancías, "02" = Servicios
            // Null/empty defaults to "01" (merchandise) — most common case for received purchase invoices
            String tipo = linea.getTipoTransaccion();
            if (tipo == null || tipo.trim().isEmpty()) {
                tipo = "01";
            }
            if ("01".equals(tipo)) {
                sumaBaseMercancias = sumaBaseMercancias.add(baseImponibleLinea);
            } else if ("02".equals(tipo)) {
                sumaBaseServicios = sumaBaseServicios.add(baseImponibleLinea);
            }

            if (linea.getImpuestoNeto() != null) {
                sumaMonto = sumaMonto.add(linea.getImpuestoNeto());
            } else if (linea.getImpuestos() != null) {
                for (Models.Detalles.Impuesto imp : linea.getImpuestos()) {
                    if (imp.getMonto() != null) {
                        sumaMonto = sumaMonto.add(imp.getMonto());
                    }
                }
            }

            if (linea.getSubTotal() != null) {
                sumaSubTotal = sumaSubTotal.add(linea.getSubTotal());
            }
        }

        if (resumen != null) {
            // Compare merchandise baseImponible against TotalMercanciasGravadas
            if (sumaBaseMercancias.compareTo(BigDecimal.ZERO) != 0 || resumen.getTotalMercanciasGravadas() != null) {
                checkResumenMatch(result, "totalMercancia", sumaBaseMercancias, resumen.getTotalMercanciasGravadas(),
                    "La suma de baseImponible de líneas de mercancía (tipoTransaccion=01) no coincide con resumen.TotalMercanciasGravadas");
            }

            // Compare service baseImponible against TotalServGravados
            if (sumaBaseServicios.compareTo(BigDecimal.ZERO) != 0 || resumen.getTotalServGravados() != null) {
                checkResumenMatch(result, "totalServGravados", sumaBaseServicios, resumen.getTotalServGravados(),
                    "La suma de baseImponible de líneas de servicio (tipoTransaccion=02) no coincide con resumen.TotalServGravados");
            }

            // TotalGravado should equal mercancias + servicios
            if (resumen.getTotalGravado() != null) {
                BigDecimal sumaTotalGravado = sumaBaseMercancias.add(sumaBaseServicios);
                checkResumenMatch(result, "totalGravado", sumaTotalGravado, resumen.getTotalGravado(),
                    "La suma de baseImponible (mercancías + servicios) no coincide con resumen.TotalGravado");
            }

            if (resumen.getTotalImpuesto() != null) {
                checkResumenMatch(result, "totalImpuesto", sumaMonto, resumen.getTotalImpuesto(),
                    "La suma de impuestos de las líneas no coincide con resumen.totalImpuesto");
            }

            // totalComprobante = sum(subTotal) + sum(impuestoNeto) — the resumen
            // total includes IVA, so we must add the tax to the subtotal sum.
            if (resumen.getTotalComprobante() != null) {
                BigDecimal sumaSubTotalConImpuesto = sumaSubTotal.add(sumaMonto);
                checkResumenMatch(result, "totalComprobante", sumaSubTotalConImpuesto, resumen.getTotalComprobante(),
                    "La suma de subTotal + impuestos de las líneas no coincide con resumen.totalComprobante");
            }
        }
    }

    private void checkResumenMatch(@Nonnull PrevalidationResult result, @Nonnull String field,
                                    @Nullable BigDecimal sumValue, @Nullable BigDecimal resumenValue,
                                    @Nonnull String description) {
        if (sumValue == null || resumenValue == null) return;
        BigDecimal diff = sumValue.subtract(resumenValue).abs();
        if (diff.compareTo(getConfig().getTaxTolerance()) > 0) {
            ValidationError mismatchError = new ValidationError(
                ValidationError.Category.valueOf("TAX_CALCULATION"),
                field, "RESUMEN_MISMATCH",
                description + " mismatch: sum=" + sumValue +
                ", resumen=" + resumenValue + ", diff=" + diff,
                resumenValue, sumValue);
            mismatchError.setSeverity(ValidationError.Severity.WARNING);
            result.addWarning(mismatchError);
        }
    }

    // ─── Receptor Info Validator ──────────────────────────────────────

    /**
     * Validates receptor information:
     * - CR ID types 01-05 format rules
     * - Ubicacion completeness
     * - Required field presence
     */
    void validarInfoReceptor(@Nullable Receptor receptor, @Nullable String docCode, @Nonnull PrevalidationResult result) {
        if (receptor == null) {
            result.addError(new ValidationError(
                ValidationError.Category.valueOf("RECEPTOR_INFO"),
                "receptor", "NULL_RECEPTOR",
                "La información del receptor está faltante"));
            return;
        }

        if (receptor.getNombre() == null || receptor.getNombre().trim().isEmpty()) {
            if ("04".equals(docCode)) {
                result.addWarning(new ValidationError(
                    ValidationError.Category.valueOf("RECEPTOR_INFO"),
                    "nombre", "MISSING_NAME",
                    "El nombre del receptor está faltante (opcional para documentos TE)",
                    ValidationError.Severity.WARNING));
            } else {
                result.addError(new ValidationError(
                    ValidationError.Category.valueOf("RECEPTOR_INFO"),
                    "nombre", "MISSING_NAME",
                    "El nombre del receptor es requerido"));
            }
        }

        IdentificacionReceptor id = receptor.getIdentificacion();
        if (id != null) {
            validarIdentificacion(id, result);
        } else if (receptor.getIdentificacionExtranjero() == null || receptor.getIdentificacionExtranjero().trim().isEmpty()) {
            result.addWarning(new ValidationError(
                ValidationError.Category.valueOf("RECEPTOR_INFO"),
                "identificacion", "MISSING_IDENTIFICATION",
                "El receptor no tiene identificación (costarricense o extranjero)",
                ValidationError.Severity.WARNING));
        }

        validarUbicacion(receptor.getUbicacion(), result);

        boolean hasEmail = receptor.getCorreosElectronicos() != null
            && receptor.getCorreosElectronicos().stream().anyMatch(c -> c.getCorreo() != null && !c.getCorreo().trim().isEmpty());
        if (!hasEmail && !"04".equals(docCode)) {
            result.addWarning(new ValidationError(
                ValidationError.Category.valueOf("RECEPTOR_INFO"),
                "correoElectronico", "MISSING_EMAIL",
                "El correo electrónico del receptor está faltante",
                ValidationError.Severity.WARNING));
        }
    }

    /**
     * Validates Costa Rican identification types (01-06) per Anexos V4.4.
     *
     * Type format rules (Anexos V4.4 — Nota 4):
     *   01 (Cedula Fisica):    9-10 digits, format 0XXXXXXXX or 1XXXXXXXX
     *   02 (Cedula Juridica):  10-12 digits starting with 3, OR 10 alfanumericos (Q4 2026)
     *   03 (DIMEX):            11-12 digits, sin ceros al inicio
     *   04 (NITE):             10-12 digits, sin ceros al inicio
     *   05 (Extranjero No Domiciliado): hasta 20 caracteres alfanumericos (FEC exclusivo)
     *   06 (No Contribuyente): hasta 20 caracteres alfanumericos (FEC exclusivo)
     */
    void validarIdentificacion(@Nonnull IdentificacionReceptor id, @Nonnull PrevalidationResult result) {
        String tipo = id.getTipo();
        String numero = id.getNumero();

        if (tipo == null || tipo.trim().isEmpty()) {
            result.addError(new ValidationError(
                ValidationError.Category.valueOf("RECEPTOR_INFO"),
                "identificacion.tipo", "MISSING_ID_TYPE",
                "El tipo de identificación es requerido"));
            return;
        }

        tipo = tipo.trim();

        if (!tipo.matches("0[1-6]")) {
            result.addWarning(new ValidationError(
                ValidationError.Category.valueOf("RECEPTOR_INFO"),
                "identificacion.tipo", "UNKNOWN_ID_TYPE",
                "Tipo de identificación desconocido: " + tipo + " (se esperaba 01-06)",
                ValidationError.Severity.WARNING));
            return;
        }

        if (numero == null || numero.trim().isEmpty()) {
            result.addError(new ValidationError(
                ValidationError.Category.valueOf("RECEPTOR_INFO"),
                "identificacion.numero", "MISSING_ID_NUMBER",
                "El número de identificación es requerido para el tipo " + tipo));
            return;
        }

        numero = numero.trim().replace("-", "").replace(" ", "");

        switch (tipo) {
            case "01":
                if (!numero.matches("[01]\\d{8,9}")) {
                    result.addError(new ValidationError(
                        ValidationError.Category.valueOf("RECEPTOR_INFO"),
                        "identificacion.numero", "INVALID_01_FORMAT",
                        "La Cédula Física (01) debe tener 9-10 dígitos comenzando con 0 o 1",
                        "9-10 dígitos comenzando con 0 o 1", numero));
                }
                break;
            case "02":
                if (!numero.matches("3\\d{9,11}") && !numero.matches("3\\d{3}[0-9A-Za-z]{6}")) {
                    result.addError(new ValidationError(
                        ValidationError.Category.valueOf("RECEPTOR_INFO"),
                        "identificacion.numero", "INVALID_02_FORMAT",
                        "La Cédula Jurídica (02) debe tener 10-12 dígitos comenzando con 3, o 10 alfanuméricos (3 + 3 dígitos + 6 alfanuméricos)",
                        "10-12 dígitos comenzando con 3, o 10 alfanuméricos", numero));
                }
                break;
            case "03":
                if (!numero.matches("\\d{11,12}")) {
                    result.addError(new ValidationError(
                        ValidationError.Category.valueOf("RECEPTOR_INFO"),
                        "identificacion.numero", "INVALID_03_FORMAT",
                        "El DIMEX (03) debe tener 11-12 dígitos",
                        "11-12 dígitos", numero));
                }
                break;
            case "04":
                if (!numero.matches("\\d{10,12}")) {
                    result.addError(new ValidationError(
                        ValidationError.Category.valueOf("RECEPTOR_INFO"),
                        "identificacion.numero", "INVALID_04_FORMAT",
                        "El NITE (04) debe tener 10-12 dígitos",
                        "10-12 dígitos", numero));
                }
                break;
            case "05":
                if (!numero.matches("[0-9A-Za-z]{1,20}")) {
                    result.addError(new ValidationError(
                        ValidationError.Category.valueOf("RECEPTOR_INFO"),
                        "identificacion.numero", "INVALID_05_FORMAT",
                        "El Extranjero No Domiciliado (05) debe tener 1-20 caracteres alfanuméricos",
                        "1-20 caracteres alfanuméricos", numero));
                }
                break;
            case "06":
                if (!numero.matches("[0-9A-Za-z]{1,20}")) {
                    result.addError(new ValidationError(
                        ValidationError.Category.valueOf("RECEPTOR_INFO"),
                        "identificacion.numero", "INVALID_06_FORMAT",
                        "El No Contribuyente (06) debe tener 1-20 caracteres alfanuméricos",
                        "1-20 caracteres alfanuméricos", numero));
                }
                break;
        }
    }

    /**
     * Validates ubicacion completeness.
     */
    void validarUbicacion(@Nullable Ubicacion ubicacion, @Nonnull PrevalidationResult result) {
        if (ubicacion == null) {
            result.addWarning(new ValidationError(
                ValidationError.Category.valueOf("RECEPTOR_INFO"),
                "ubicacion", "MISSING_UBICACION",
                "La ubicación del receptor está faltante",
                ValidationError.Severity.WARNING));
            return;
        }

        if (ubicacion.getProvincia() == null || ubicacion.getProvincia().trim().isEmpty()) {
            result.addWarning(new ValidationError(
                ValidationError.Category.valueOf("RECEPTOR_INFO"),
                "ubicacion.provincia", "MISSING_PROVINCIA",
                "La provincia es requerida en la ubicación",
                ValidationError.Severity.WARNING));
        }

        if (ubicacion.getCanton() == null || ubicacion.getCanton().trim().isEmpty()) {
            result.addWarning(new ValidationError(
                ValidationError.Category.valueOf("RECEPTOR_INFO"),
                "ubicacion.canton", "MISSING_CANTON",
                "El cantón es requerido en la ubicación",
                ValidationError.Severity.WARNING));
        }

        if (ubicacion.getDistrito() == null || ubicacion.getDistrito().trim().isEmpty()) {
            result.addWarning(new ValidationError(
                ValidationError.Category.valueOf("RECEPTOR_INFO"),
                "ubicacion.distrito", "MISSING_DISTRITO",
                "El distrito es requerido en la ubicación",
                ValidationError.Severity.WARNING));
        }
    }

    // ─── InformacionReferencia Validator ───────────────────────────────

    /**
     * Validates InformacionReferencia entries for document types that require them.
     *
     * For NC (02) and ND (03): at least one InformacionReferencia is mandatory.
     * For REP (07): InformacionReferencia is optional but if present, each entry is validated.
     * For all present entries: validates tipoDoc, codigo, numero, fechaEmision, and razon.
     */
    private void validarInformacionReferencia(@Nullable List<InformacionReferencia> refs, @Nullable String docCode, @Nonnull PrevalidationResult result) {
        if ("02".equals(docCode) || "03".equals(docCode)) {
            if (refs == null || refs.isEmpty()) {
                result.addError(new ValidationError(
                    ValidationError.Category.valueOf("INFORMACION_REFERENCIA"),
                    "informacionReferencia", "MISSING_INFORMACION_REFERENCIA",
                    "El tipo de documento " + docCode + " requiere al menos una entrada de InformaciónReferencia"));
                return;
            }
        }

        if (refs == null || refs.isEmpty()) {
            return;
        }

        for (InformacionReferencia ref : refs) {
            String prefix = "informacionReferencia";

            if (ref.getTipoDoc() == null || ref.getTipoDoc().trim().isEmpty()) {
                result.addWarning(new ValidationError(
                    ValidationError.Category.valueOf("INFORMACION_REFERENCIA"),
                    prefix + ".tipoDoc", "MISSING_TIPO_DOC",
                    "El tipoDoc de InformaciónReferencia está faltante",
                    ValidationError.Severity.WARNING));
            }

            if (ref.getCodigo() == null || ref.getCodigo().trim().isEmpty()) {
                result.addWarning(new ValidationError(
                    ValidationError.Category.valueOf("INFORMACION_REFERENCIA"),
                    prefix + ".codigo", "MISSING_CODIGO",
                    "El código de InformaciónReferencia está faltante",
                    ValidationError.Severity.WARNING));
            } else {
                String codigo = ref.getCodigo().trim();
                if (!codigo.matches("0[1-9]|1[0-7]|99")) {
                    result.addWarning(new ValidationError(
                        ValidationError.Category.valueOf("INFORMACION_REFERENCIA"),
                        prefix + ".codigo", "UNKNOWN_CODIGO",
                        "El código de InformaciónReferencia '" + codigo + "' es desconocido (se esperaba 01-17, 99)",
                        ValidationError.Severity.WARNING));
                }
            }

            if (ref.getNumero() == null || ref.getNumero().trim().isEmpty()) {
                result.addWarning(new ValidationError(
                    ValidationError.Category.valueOf("INFORMACION_REFERENCIA"),
                    prefix + ".numero", "MISSING_NUMERO",
                    "El número de InformaciónReferencia está faltante",
                    ValidationError.Severity.WARNING));
            }

            if (ref.getFechaEmision() == null) {
                result.addWarning(new ValidationError(
                    ValidationError.Category.valueOf("INFORMACION_REFERENCIA"),
                    prefix + ".fechaEmision", "MISSING_FECHA_EMISION",
                    "La fecha de emisión de InformaciónReferencia está faltante",
                    ValidationError.Severity.WARNING));
            }

            if (ref.getRazon() == null || ref.getRazon().trim().isEmpty()) {
                result.addWarning(new ValidationError(
                    ValidationError.Category.valueOf("INFORMACION_REFERENCIA"),
                    prefix + ".razon", "MISSING_RAZON",
                    "La razón de InformaciónReferencia está faltante",
                    ValidationError.Severity.WARNING));
            } else if (ref.getRazon().length() > 180) {
                result.addWarning(new ValidationError(
                    ValidationError.Category.valueOf("INFORMACION_REFERENCIA"),
                    prefix + ".razon", "RAZON_TOO_LONG",
                    "La razón de InformaciónReferencia excede 180 caracteres (longitud=" + ref.getRazon().length() + ")",
                    ValidationError.Severity.WARNING));
            }
        }
    }
}