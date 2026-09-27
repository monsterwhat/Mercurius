package Models.Enums;

import jakarta.annotation.Nonnull;

/**
 * Nota 10 — tipo de documento de referencia ({@code InformacionReferencia/TipoDocIR}).
 *
 * <p>Los códigos coinciden con el Catálogo de Tipo de Documento del encabezado
 * (01 FE, 02 NC, 03 ND, 04 TE, 05 FEE, 08 FEC, 10 REP), porque
 * {@code TipoDocIR} referencia el documento por su CodigoDocumento.
 *
 * <p><b>No copie el texto del XSD para 02 y 03 sin verificar:</b> el
 * {@code TipoDocReferenciaType} oficial documenta 02 como "Nota de debido
 * electrónica" y 03 como "Nota de crédito electrónica", al revés del catálogo
 * real, y "nota de debido" no es un documento que exista en Costa Rica. El error
 * es del XSD y viene en v4.3 y v4.4 por igual; aquí se conserva la
 * correspondencia real con CodigoDocumento, que es la que Hacienda valida.
 *
 * <p>El 17 de esta nota es "Nota de Crédito a Factura Electrónica de Compra" y
 * nada tiene que ver con el 17 de {@link Tipo_CodigosReferencia} ("Pago a
 * comprobante electrónico", exclusivo del Recibo Electrónico de Pago).
 */
public enum Tipo_Documento_Referencia {
    FACTURA_ELECTRONICA("01", "Factura electrónica"),
    NOTA_CREDITO_ELECTRONICA("02", "Nota de crédito electrónica"),
    NOTA_DEBITO_ELECTRONICA("03", "Nota de débito electrónica"),
    TIQUETE_ELECTRONICO("04", "Tiquete electrónico"),
    NOTA_DE_DESPACHO("05", "Nota de despacho"),
    CONTRATO("06", "Contrato"),
    PROCEDIMIENTO("07", "Procedimiento"),
    COMPROBANTE_CONTINGENCIA("08", "Comprobante emitido en contingencia"),
    DEVOLUCION_MERCADERIA("09", "Devolución mercadería"),
    COMPROBANTE_ELECTRONICO_RECHAZADO_HACIENDA("10", "Comprobante electrónico rechazado por el Ministerio de Hacienda"),
    SUSTITUYE_FACTURA_RECHAZADA_RECEPTOR("11", "Sustituye factura rechazada por el Receptor del comprobante"),
    SUSTITUYE_FACTURA_EXPORTACION("12", "Sustituye Factura de exportación"),
    FACTURACION_MES_VENCIDO("13", "Facturación mes vencido"),
    COMPROBANTE_REGIMEN_SIMPLIFICADO("14", "Comprobante aportado por contribuyente del Régimen de Tributación Simplificado"),
    SUSTITUYE_FACTURA_COMPRA("15", "Sustituye una Factura electrónica de Compra"),
    COMPROBANTE_DE_PROVEEDOR_NO_DOMICILIADO("16", "Comprobante de Proveedor No Domiciliado"),
    NOTA_CREDITO_FACTURA_ELECTRONICA_COMPRA("17", "Nota de Crédito a Factura Electrónica de Compra"),
    NOTA_DEBITO_FACTURA_ELECTRONICA_COMPRA("18", "Nota de Débito a Factura Electrónica de Compra"),
    FACTURA_ELECTRONICA_EXPORTACION_REF("19", "Factura Electrónica de Exportación"),
    RECIBO_ELECTRONICO_PAGO_REF("20", "Recibo Electrónico de Pago"),
    OTROS("99", "Otros");

    @Nonnull
    private final String codigo;
    @Nonnull
    private final String descripcion;

    Tipo_Documento_Referencia(@Nonnull String codigo, @Nonnull String descripcion) {
        this.codigo = codigo;
        this.descripcion = descripcion;
    }

    @Nonnull
    public String getCodigo() {
        return codigo;
    }

    @Nonnull
    public String getDescripcion() {
        return descripcion;
    }

    @Nonnull
    public static Tipo_Documento_Referencia fromCodigo(@Nonnull String codigo) {
        for (Tipo_Documento_Referencia tipo : Tipo_Documento_Referencia.values()) {
            if (tipo.getCodigo().equals(codigo)) {
                return tipo;
            }
        }
        throw new IllegalArgumentException("Código de tipo de documento de referencia no válido: " + codigo);
    }
}
