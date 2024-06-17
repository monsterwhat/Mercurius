package Models.Comprobantes.Enums;

/**
 *
 * @author Al
 */

public enum TipoDocumentoReferencia {
    FACTURA_ELECTRONICA("01", "Factura electrónica"),
    NOTA_DE_DEBITO_ELECTRONICA("02", "Nota de débito electrónica"),
    NOTA_DE_CREDITO_ELECTRONICA("03", "Nota de crédito electrónica"),
    TIQUETE_ELECTRONICO("04", "Tiquete electrónico"),
    NOTA_DE_DESPACHO("05", "Nota de despacho"),
    CONTRATO("06", "Contrato"),
    PROCEDIMIENTO("07", "Procedimiento"),
    COMPROBANTE_CONTINGENCIA("08", "Comprobante emitido en contingencia"),
    DEVOLUCION_MERCADERIA("09", "Devolución mercadería"),
    SUSTITUYE_FACTURA_RECHAZADA_HACIENDA("10", "Sustituye factura rechazada por el Ministerio de Hacienda"),
    SUSTITUYE_FACTURA_RECHAZADA_RECEPTOR("11", "Sustituye factura rechazada por el Receptor del comprobante"),
    SUSTITUYE_FACTURA_EXPORTACION("12", "Sustituye Factura de exportación"),
    FACTURACION_MES_VENCIDO("13", "Facturación mes vencido"),
    COMPROBANTE_REGIMEN_SIMPLIFICADO("14", "Comprobante aportado por contribuyente del Régimen de Tributación Simplificado"),
    SUSTITUYE_FACTURA_COMPRA("15", "Sustituye una Factura electrónica de Compra"),
    OTROS("99", "Otros");

    private final String codigo;
    private final String descripcion;

    TipoDocumentoReferencia(String codigo, String descripcion) {
        this.codigo = codigo;
        this.descripcion = descripcion;
    }

    public String getCodigo() {
        return codigo;
    }

    public String getDescripcion() {
        return descripcion;
    }

    // Método estático para obtener un TipoDocumentoReferencia a partir del código
    public static TipoDocumentoReferencia fromCodigo(String codigo) {
        for (TipoDocumentoReferencia tipo : TipoDocumentoReferencia.values()) {
            if (tipo.getCodigo().equals(codigo)) {
                return tipo;
            }
        }
        throw new IllegalArgumentException("Código de tipo de documento de referencia no válido: " + codigo);
    }
}

