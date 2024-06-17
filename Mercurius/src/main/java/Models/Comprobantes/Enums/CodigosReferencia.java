package Models.Comprobantes.Enums;

/**
 *
 * @author Al
 */

public enum CodigosReferencia {
    ANULA_DOCUMENTO_REFERENCIA("01", "Anula Documento de Referencia"),
    CORRIGE_MONTO("02", "Corrige monto"),
    REFERENCIA_OTRO_DOCUMENTO("04", "Referencia a otro documento"),
    SUSTITUYE_COMPROBANTE_PROVISIONAL("05", "Sustituye comprobante provisional por contingencia."),
    OTROS("99", "Otros");

    private final String codigo;
    private final String descripcion;

    CodigosReferencia(String codigo, String descripcion) {
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
    public static CodigosReferencia fromCodigo(String codigo) {
        for (CodigosReferencia campo : CodigosReferencia.values()) {
            if (campo.getCodigo().equals(codigo)) {
                return campo;
            }
        }
        throw new IllegalArgumentException("Código de descripción del campo no válido: " + codigo);
    }
}

