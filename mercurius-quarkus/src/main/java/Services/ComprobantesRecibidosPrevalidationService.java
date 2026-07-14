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
                "Comprobante ID cannot be null"));
            return result;
        }
        result.setComprobanteId(String.valueOf(comprobanteId));

        // Load the invoice with all details
        ComprobantesRecibidos factura = comprobantesRecibidosService.findByIdWithDetails(comprobanteId);
        if (factura == null) {
            result.addError(new ValidationError(
                ValidationError.Category.valueOf("RECEPTOR_INFO"),
                "comprobanteId", "NOT_FOUND",
                "Comprobante not found: " + comprobanteId));
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
                "ComprobantesRecibidos entity cannot be null"));
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
                "Comprobante ID is not a valid number: " + comprobanteId));
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
                    "Line " + (linea.getNumeroLinea() != null ? linea.getNumeroLinea() : "?") +
                    ": CAByS code is missing"));
                continue;
            }

            codigo = codigo.trim();

            // Validate 13-digit format
            if (!codigo.matches("\\d{13}")) {
                result.addError(new ValidationError(
                    ValidationError.Category.valueOf("CABYS"),
                    "codigoCabys", "INVALID_FORMAT",
                    "CAByS code '" + codigo + "' is not 13 digits",
                    "13-digit number", codigo));
                continue;
            }

            // Look up in local DB only
            Cabys cabys = cabysService.find(codigo);
            if (cabys == null) {
                String msg = "CAByS code '" + codigo + "' not found in local catalog";
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

            // Check if CAByS is active
            if (!"ACTIVO".equalsIgnoreCase(cabys.getEstado())) {
                result.addWarning(new ValidationError(
                    ValidationError.Category.valueOf("CABYS"),
                    "codigoCabys", "INACTIVE_CABYS",
                    "CAByS code '" + codigo + "' is not ACTIVE (estado=" + cabys.getEstado() + ")",
                    ValidationError.Severity.WARNING));
            }
        }
    }

    // ─── Tax Calculation Validator ────────────────────────────────────

    /**
     * Verifies tax calculations both line-by-line and at resumen level.
     * Tolerance: ±0.01 on all comparisons.
     */
    void verificarCalculosImpuestos(@Nullable List<LineaDetalle> lineas, @Nullable ResumenFactura resumen, @Nonnull PrevalidationResult result) {
        if (lineas == null || lineas.isEmpty()) {
            return;
        }

        // Line-level check: baseImponible * (impuesto / 100) ≈ monto
        BigDecimal sumaBaseImponible = BigDecimal.ZERO;
        BigDecimal sumaMonto = BigDecimal.ZERO;
        BigDecimal sumaSubTotal = BigDecimal.ZERO;

        for (LineaDetalle linea : lineas) {
            String lineLabel = "Line " + (linea.getNumeroLinea() != null ? linea.getNumeroLinea() : "?");

            // Check individual line tax calculation if all fields present
            if (linea.getBaseImponible() != null && linea.getImpuestoNeto() != null) {
                // baseImponible + impuestoNeto should approximate montoTotal
                if (linea.getMontoTotal() != null) {
                    BigDecimal expectedTotal = linea.getBaseImponible().add(linea.getImpuestoNeto());
                    if (expectedTotal.compareTo(linea.getMontoTotal()) != 0) {
                        BigDecimal diff = expectedTotal.subtract(linea.getMontoTotal()).abs();
                        if (diff.compareTo(getConfig().getTaxTolerance()) > 0) {
                            result.addError(new ValidationError(
                                ValidationError.Category.valueOf("TAX_CALCULATION"),
                                "impuestoNeto", "LINE_TAX_MISMATCH",
                                lineLabel + ": baseImponible + impuestoNeto (" + expectedTotal +
                                ") != montoTotal (" + linea.getMontoTotal() + "), diff=" + diff,
                                expectedTotal, linea.getMontoTotal()));
                        }
                    }
                }
            }

            // Accumulate for resumen checks
            if (linea.getBaseImponible() != null) {
                sumaBaseImponible = sumaBaseImponible.add(linea.getBaseImponible());
            }
            if (linea.getImpuestoNeto() != null) {
                sumaMonto = sumaMonto.add(linea.getImpuestoNeto());
            }
            if (linea.getSubTotal() != null) {
                sumaSubTotal = sumaSubTotal.add(linea.getSubTotal());
            }
        }

        // Resumen-level checks
        if (resumen != null) {
            // sum(baseImponible) ≈ resumen.totalMercancia
            checkResumenMatch(result, "totalMercancia", sumaBaseImponible, resumen.getTotalMercanciasGravadas(),
                "sum(linea.baseImponible) vs resumen.totalMercanciasGravadas");

            // sum(monto) ≈ resumen.totalImpuesto
            if (resumen.getTotalImpuesto() != null) {
                checkResumenMatch(result, "totalImpuesto", sumaMonto, resumen.getTotalImpuesto(),
                    "sum(linea.monto) vs resumen.totalImpuesto");
            }

            // sum(subTotal) ≈ resumen.totalComprobante
            if (resumen.getTotalComprobante() != null) {
                checkResumenMatch(result, "totalComprobante", sumaSubTotal, resumen.getTotalComprobante(),
                    "sum(linea.subTotal) vs resumen.totalComprobante");
            }
        }
    }

    private void checkResumenMatch(@Nonnull PrevalidationResult result, @Nonnull String field,
                                    @Nullable BigDecimal sumValue, @Nullable BigDecimal resumenValue,
                                    @Nonnull String description) {
        if (sumValue == null || resumenValue == null) return;
        BigDecimal diff = sumValue.subtract(resumenValue).abs();
        if (diff.compareTo(getConfig().getTaxTolerance()) > 0) {
            result.addError(new ValidationError(
                ValidationError.Category.valueOf("TAX_CALCULATION"),
                field, "RESUMEN_MISMATCH",
                description + " mismatch: sum=" + sumValue +
                ", resumen=" + resumenValue + ", diff=" + diff,
                resumenValue, sumValue));
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
                "Receptor information is missing"));
            return;
        }

        // Validate receptor name (optional for TE documents)
        if (receptor.getNombre() == null || receptor.getNombre().trim().isEmpty()) {
            if ("04".equals(docCode)) {
                result.addWarning(new ValidationError(
                    ValidationError.Category.valueOf("RECEPTOR_INFO"),
                    "nombre", "MISSING_NAME",
                    "Receptor name is missing (optional for TE documents)",
                    ValidationError.Severity.WARNING));
            } else {
                result.addError(new ValidationError(
                    ValidationError.Category.valueOf("RECEPTOR_INFO"),
                    "nombre", "MISSING_NAME",
                    "Receptor name is required"));
            }
        }

        // Validate identification
        IdentificacionReceptor id = receptor.getIdentificacion();
        if (id != null) {
            validarIdentificacion(id, result);
        } else if (receptor.getIdentificacionExtranjero() == null || receptor.getIdentificacionExtranjero().trim().isEmpty()) {
            // Foreign receptors must have identificacionExtranjero
            result.addWarning(new ValidationError(
                ValidationError.Category.valueOf("RECEPTOR_INFO"),
                "identificacion", "MISSING_IDENTIFICATION",
                "Receptor has no identification (Costa Rican or foreign)",
                ValidationError.Severity.WARNING));
        }

        // Validate ubicacion
        validarUbicacion(receptor.getUbicacion(), result);

        // Validate email (informational only for TE documents)
        boolean hasEmail = receptor.getCorreosElectronicos() != null
            && receptor.getCorreosElectronicos().stream().anyMatch(c -> c.getCorreo() != null && !c.getCorreo().trim().isEmpty());
        if (!hasEmail && !"04".equals(docCode)) {
            result.addWarning(new ValidationError(
                ValidationError.Category.valueOf("RECEPTOR_INFO"),
                "correoElectronico", "MISSING_EMAIL",
                "Receptor email is missing",
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
                "Identification type is required"));
            return;
        }

        tipo = tipo.trim();

        // Valid ID types for Costa Rica per Anexos V4.4: 01-06
        if (!tipo.matches("0[1-6]")) {
            result.addWarning(new ValidationError(
                ValidationError.Category.valueOf("RECEPTOR_INFO"),
                "identificacion.tipo", "UNKNOWN_ID_TYPE",
                "Unknown identification type: " + tipo + " (expected 01-06)",
                ValidationError.Severity.WARNING));
            return;
        }

        if (numero == null || numero.trim().isEmpty()) {
            result.addError(new ValidationError(
                ValidationError.Category.valueOf("RECEPTOR_INFO"),
                "identificacion.numero", "MISSING_ID_NUMBER",
                "Identification number is required for type " + tipo));
            return;
        }

        numero = numero.trim().replace("-", "").replace(" ", "");

        switch (tipo) {
            case "01": // Cedula Fisica
                if (!numero.matches("[01]\\d{8,9}")) {
                    result.addError(new ValidationError(
                        ValidationError.Category.valueOf("RECEPTOR_INFO"),
                        "identificacion.numero", "INVALID_01_FORMAT",
                        "Cedula Fisica (01) must be 9-10 digits starting with 0 or 1",
                        "9-10 digits starting with 0 or 1", numero));
                }
                break;
            case "02": // Cedula Juridica
                // Accept both: old numeric (3XXXXXXXXX) and new alfanumerico per RN Decreto 44648-MJ Art.137
                //   Old format: 3\d{9} (10 digits, starts with 3)
                //   New format: 3\d{3}[0-9A-Za-z]{6} (class code 3-digit numeric + consecutivo 6-char alfanumerico)
                //                e.g. 3-101-A00001 → stripped 3101A00001
                //   New format effective: Q4 2026, pending RN activation (min 2 months notice)
                if (!numero.matches("3\\d{9,11}") && !numero.matches("3\\d{3}[0-9A-Za-z]{6}")) {
                    result.addError(new ValidationError(
                        ValidationError.Category.valueOf("RECEPTOR_INFO"),
                        "identificacion.numero", "INVALID_02_FORMAT",
                        "Cedula Juridica (02) must be 10-12 digits starting with 3, or 10 alfanumericos (3 + 3 digitos + 6 alfanumericos)",
                        "10-12 digits starting with 3, or 10 alfanumericos", numero));
                }
                break;
            case "03": // DIMEX
                if (!numero.matches("\\d{11,12}")) {
                    result.addError(new ValidationError(
                        ValidationError.Category.valueOf("RECEPTOR_INFO"),
                        "identificacion.numero", "INVALID_03_FORMAT",
                        "DIMEX (03) must be 11-12 digits",
                        "11-12 digits", numero));
                }
                break;
            case "04": // NITE
                if (!numero.matches("\\d{10,12}")) {
                    result.addError(new ValidationError(
                        ValidationError.Category.valueOf("RECEPTOR_INFO"),
                        "identificacion.numero", "INVALID_04_FORMAT",
                        "NITE (04) must be 10-12 digits",
                        "10-12 digits", numero));
                }
                break;
            case "05": // Extranjero No Domiciliado — V4.4 redefined (FEC exclusivo)
                // Hasta 20 caracteres alfanumericos per Anexos V4.4 Nota 4
                if (!numero.matches("[0-9A-Za-z]{1,20}")) {
                    result.addError(new ValidationError(
                        ValidationError.Category.valueOf("RECEPTOR_INFO"),
                        "identificacion.numero", "INVALID_05_FORMAT",
                        "Extranjero No Domiciliado (05) must be 1-20 alfanumericos",
                        "1-20 alfanumericos", numero));
                }
                break;
            case "06": // No Contribuyente — V4.4 new (FEC exclusivo)
                // Hasta 20 caracteres alfanumericos per Anexos V4.4 Nota 4
                if (!numero.matches("[0-9A-Za-z]{1,20}")) {
                    result.addError(new ValidationError(
                        ValidationError.Category.valueOf("RECEPTOR_INFO"),
                        "identificacion.numero", "INVALID_06_FORMAT",
                        "No Contribuyente (06) must be 1-20 alfanumericos",
                        "1-20 alfanumericos", numero));
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
                "Receptor ubicacion is missing",
                ValidationError.Severity.WARNING));
            return;
        }

        // Provincia is required (1 digit)
        if (ubicacion.getProvincia() == null || ubicacion.getProvincia().trim().isEmpty()) {
            result.addWarning(new ValidationError(
                ValidationError.Category.valueOf("RECEPTOR_INFO"),
                "ubicacion.provincia", "MISSING_PROVINCIA",
                "Provincia is required in ubicacion",
                ValidationError.Severity.WARNING));
        }

        // Canton is required (2 digits)
        if (ubicacion.getCanton() == null || ubicacion.getCanton().trim().isEmpty()) {
            result.addWarning(new ValidationError(
                ValidationError.Category.valueOf("RECEPTOR_INFO"),
                "ubicacion.canton", "MISSING_CANTON",
                "Canton is required in ubicacion",
                ValidationError.Severity.WARNING));
        }

        // Distrito is required (2 digits)
        if (ubicacion.getDistrito() == null || ubicacion.getDistrito().trim().isEmpty()) {
            result.addWarning(new ValidationError(
                ValidationError.Category.valueOf("RECEPTOR_INFO"),
                "ubicacion.distrito", "MISSING_DISTRITO",
                "Distrito is required in ubicacion",
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
        // NC (02) and ND (03) require at least one InformacionReferencia
        if ("02".equals(docCode) || "03".equals(docCode)) {
            if (refs == null || refs.isEmpty()) {
                result.addError(new ValidationError(
                    ValidationError.Category.valueOf("INFORMACION_REFERENCIA"),
                    "informacionReferencia", "MISSING_INFORMACION_REFERENCIA",
                    "Document type " + docCode + " requires at least one InformacionReferencia entry"));
                return;
            }
        }

        // No refs to validate — nothing more to do
        if (refs == null || refs.isEmpty()) {
            return;
        }

        // Validate each InformacionReferencia entry
        for (InformacionReferencia ref : refs) {
            String prefix = "informacionReferencia";

            // tipoDoc must be present
            if (ref.getTipoDoc() == null || ref.getTipoDoc().trim().isEmpty()) {
                result.addWarning(new ValidationError(
                    ValidationError.Category.valueOf("INFORMACION_REFERENCIA"),
                    prefix + ".tipoDoc", "MISSING_TIPO_DOC",
                    "InformacionReferencia tipoDoc is missing",
                    ValidationError.Severity.WARNING));
            }

            // codigo must be present and one of the valid codes (01-06)
            if (ref.getCodigo() == null || ref.getCodigo().trim().isEmpty()) {
                result.addWarning(new ValidationError(
                    ValidationError.Category.valueOf("INFORMACION_REFERENCIA"),
                    prefix + ".codigo", "MISSING_CODIGO",
                    "InformacionReferencia codigo is missing",
                    ValidationError.Severity.WARNING));
            } else {
                String codigo = ref.getCodigo().trim();
                if (!codigo.matches("0[1-9]|1[0-7]|99")) {
                    result.addWarning(new ValidationError(
                        ValidationError.Category.valueOf("INFORMACION_REFERENCIA"),
                        prefix + ".codigo", "UNKNOWN_CODIGO",
                        "InformacionReferencia codigo '" + codigo + "' is unknown (expected 01-17, 99)",
                        ValidationError.Severity.WARNING));
                }
            }

            // numero must be present
            if (ref.getNumero() == null || ref.getNumero().trim().isEmpty()) {
                result.addWarning(new ValidationError(
                    ValidationError.Category.valueOf("INFORMACION_REFERENCIA"),
                    prefix + ".numero", "MISSING_NUMERO",
                    "InformacionReferencia numero is missing",
                    ValidationError.Severity.WARNING));
            }

            // fechaEmision must be present
            if (ref.getFechaEmision() == null) {
                result.addWarning(new ValidationError(
                    ValidationError.Category.valueOf("INFORMACION_REFERENCIA"),
                    prefix + ".fechaEmision", "MISSING_FECHA_EMISION",
                    "InformacionReferencia fechaEmision is missing",
                    ValidationError.Severity.WARNING));
            }

            // razon must be present and max 180 characters
            if (ref.getRazon() == null || ref.getRazon().trim().isEmpty()) {
                result.addWarning(new ValidationError(
                    ValidationError.Category.valueOf("INFORMACION_REFERENCIA"),
                    prefix + ".razon", "MISSING_RAZON",
                    "InformacionReferencia razon is missing",
                    ValidationError.Severity.WARNING));
            } else if (ref.getRazon().length() > 180) {
                result.addWarning(new ValidationError(
                    ValidationError.Category.valueOf("INFORMACION_REFERENCIA"),
                    prefix + ".razon", "RAZON_TOO_LONG",
                    "InformacionReferencia razon exceeds 180 characters (length=" + ref.getRazon().length() + ")",
                    ValidationError.Severity.WARNING));
            }
        }
    }
}