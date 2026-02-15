package Models.Enums;

public enum Tipo_Documento_Referencia {
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
    COMPROBANTE_DE_PROVEEDOR_NO_DOMICILIADO("16","Comprobante de Proveedor No Domiciliado"),
    NOTA_CREDITO_FACTURA_ELECTRONICA_COMPRA("17","Nota de Crédito a Factura Electrónica de Compra"),
    NOTA_DEBITO_FACTURA_ELECTRONICA_COMPRA("18","Nota de Débito a Factura Electrónica de Compra"),
    OTROS("99", "Otros");

    private final String codigo;
    private final String descripcion;

    Tipo_Documento_Referencia(String codigo, String descripcion) {
        this.codigo = codigo;
        this.descripcion = descripcion;
    }

    public String getCodigo() {
        return codigo;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public static Tipo_Documento_Referencia fromCodigo(String codigo) {
        for (Tipo_Documento_Referencia tipo : Tipo_Documento_Referencia.values()) {
            if (tipo.getCodigo().equals(codigo)) {
                return tipo;
            }
        }
        throw new IllegalArgumentException("Código de tipo de documento de referencia no válido: " + codigo);
    }
}
