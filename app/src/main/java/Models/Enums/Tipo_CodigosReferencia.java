package Models.Enums;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.util.Set;

/**
 * Nota 9 — código de referencia ({@code InformacionReferencia/Codigo}).
 *
 * <p>Descripciones copiadas del {@code CodigoReferenciaType} de los XSD oficiales
 * v4.4. El catálogo salta el 03 a propósito; no existe.
 *
 * <p><b>Ojo:</b> el 17 de esta nota es "Pago a comprobante electrónico" y sólo
 * existe en {@code ReciboElectronicoPago_V4.4.xsd}. Los demás esquemas lo
 * rechazan. No confundir con el 17 de {@link Tipo_Documento_Referencia}, que es
 * "Nota de Crédito a Factura Electrónica de Compra" y sí es válido en todas partes.
 * Para emitirlo use {@link #validarParaDocumento(String, Tipo_CodigosReferencia)}.
 */
public enum Tipo_CodigosReferencia {
    ANULA_DOCUMENTO_REFERENCIA("01", "Anula documento de referencia"),
    CORRIGE_TEXTO_DOCUMENTO_REFERENCIA("02", "Corrige texto de documento de referencia"),
    REFERENCIA_OTRO_DOCUMENTO("04", "Referencia a otro documento"),
    SUSTITUYE_COMPROBANTE_PROVISIONAL("05", "Sustituye comprobante provisional por contingencia"),
    DEVOLUCION_MERCANCIA("06", "Devolución de mercancía"),
    SUSTITUYE_COMPROBANTE_ELECTRONICO("07", "Sustituye comprobante electrónico"),
    FACTURA_ENDOSADA("08", "Factura Endosada"),
    NOTA_CREDITO_FINANCIERA("09", "Nota de crédito financiera"),
    NOTA_DEBITO_FINANCIERA("10", "Nota de débito financiera"),
    PROVEEDOR_NO_DOMICILIADO("11", "Proveedor No Domiciliado"),
    CREDITO_POR_EXONERACION_POSTERIOR("12", "Crédito por exoneración posterior a la facturación"),
    ANULA_DOCUMENTO_REFERENCIA_ERROR_MATERIAL("13", "Anula documento de referencia por error material"),
    CORRIGE_MONTO_ERROR_MATERIAL("14", "Corrige monto por error material"),
    SUSTITUYE_COMPROBANTE_ERROR_MATERIAL("15", "Sustituye comprobante electrónico por error material"),
    SUSTITUYE_COMPROBANTE_ELECTRONICO_RECHAZADO("16", "Sustituye comprobante electrónico rechazado"),
    APLICACION_PAGO_REP("17", "Pago a comprobante electrónico"),
    OTROS("99", "Otros");

    /**
     * CodigoDocumento del Recibo Electrónico de Pago. Mantener en sincronía con
     * {@code ReciboElectronicoPagoStrategy.getCodigoDocumento()}.
     */
    private static final String CODIGO_DOCUMENTO_RECIBO_ELECTRONICO_PAGO = "10";

    /** Códigos que el esquema oficial sólo admite en el Recibo Electrónico de Pago. */
    private static final Set<String> EXCLUSIVOS_RECIBO_ELECTRONICO_PAGO = Set.of("17");

    @Nonnull
    private final String codigo;
    @Nonnull
    private final String descripcion;

    Tipo_CodigosReferencia(String codigo, String descripcion) {
        this.codigo = codigo;
        this.descripcion = descripcion;
    }

    public String getCodigo() {
        return codigo;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public static Tipo_CodigosReferencia fromCodigo(String codigo) {
        for (Tipo_CodigosReferencia campo : Tipo_CodigosReferencia.values()) {
            if (campo.getCodigo().equals(codigo)) {
                return campo;
            }
        }
        throw new IllegalArgumentException("Código de descripción del campo no válido: " + codigo);
    }

    /**
     * Igual que {@link #fromCodigo(String)} pero devuelve {@code null} en vez de
     * lanzar, para validar datos entrantes sin envolver todo en un try/catch.
     */
    @Nullable
    public static Tipo_CodigosReferencia fromCodigoOrNull(@Nullable String codigo) {
        if (codigo == null) {
            return null;
        }
        for (Tipo_CodigosReferencia campo : Tipo_CodigosReferencia.values()) {
            if (campo.getCodigo().equals(codigo.trim())) {
                return campo;
            }
        }
        return null;
    }

    /**
     * {@code true} si el esquema oficial acepta este código únicamente en el
     * Recibo Electrónico de Pago (hoy: 17).
     */
    public boolean esExclusivoDeReciboElectronicoPago() {
        return EXCLUSIVOS_RECIBO_ELECTRONICO_PAGO.contains(codigo);
    }

    /**
     * {@code true} si el código puede emitirse en un documento con el
     * CodigoDocumento dado, según el XSD oficial que le corresponde.
     */
    public static boolean esValidoParaDocumento(@Nullable String codigoDocumento,
                                                 @Nonnull Tipo_CodigosReferencia codigoRef) {
        return !codigoRef.esExclusivoDeReciboElectronicoPago()
                || CODIGO_DOCUMENTO_RECIBO_ELECTRONICO_PAGO.equals(codigoDocumento);
    }

    /**
     * Falla si el código no es válido para el documento que se está emitiendo.
     * El esquema oficial rechaza el documento completo, no sólo la referencia.
     *
     * @param codigoDocumento CodigoDocumento del comprobante que se emite
     * @throws IllegalArgumentException si el esquema oficial no admite el código
     */
    public static void validarParaDocumento(@Nullable String codigoDocumento,
                                            @Nonnull Tipo_CodigosReferencia codigoRef) {
        if (!esValidoParaDocumento(codigoDocumento, codigoRef)) {
            throw new IllegalArgumentException("El código de referencia " + codigoRef.getCodigo()
                    + " (" + codigoRef.getDescripcion() + ") es de uso exclusivo del Recibo "
                    + "Electrónico de Pago y el esquema oficial rechaza el documento "
                    + codigoDocumento + " si lo lleva.");
        }
    }
}
