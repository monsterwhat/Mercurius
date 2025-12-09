package Models.ComprobantesV44.Enums;

/**
 *
 * @author Al
 */

public enum Tipo_CodigosReferencia {
    ANULA_DOCUMENTO_REFERENCIA("01", "Anula Documento de Referencia"),
    CORRIGE_MONTO("02", "Corrige monto"),
    REFERENCIA_OTRO_DOCUMENTO("04", "Referencia a otro documento"),
    SUSTITUYE_COMPROBANTE_PROVISIONAL("05", "Sustituye comprobante provisional por contingencia."),
    DEVOLUCION_MERCANCIA("06","Devolucion de mercancia"),
    SUSTITUYE_COMPROBANTE_ELECTRONICO("07","Sustituye comprobante electronico"),
    FACTURA_ENDOSADA("08","Factura Endosada"),
    NOTA_CREDITO_FINANCIERA("09","Nota de Credito Financiera"),
    NOTA_DEBITO_FINANCIERA("10","Nota de Debito Financiera"),
    PROVEEDOR_NO_DOMICILIADO("11","Proveedor no domiciliado"),
    CREDITO_POR_EXONERACION_POSTERIOR("12","Credito por exoneracion posterior a la facturacion"),
    OTROS("99", "Otros");

    private final String codigo;
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

    // Método estático para obtener una CodigosReferencia a partir del código
    public static Tipo_CodigosReferencia fromCodigo(String codigo) {
        for (Tipo_CodigosReferencia campo : Tipo_CodigosReferencia.values()) {
            if (campo.getCodigo().equals(codigo)) {
                return campo;
            }
        }
        throw new IllegalArgumentException("Código de descripción del campo no válido: " + codigo);
    }
}

