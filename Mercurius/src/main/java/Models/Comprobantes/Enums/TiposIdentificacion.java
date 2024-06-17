package Models.Comprobantes.Enums;

/**
 *
 * @author Al
 */

public enum TiposIdentificacion {
    CEDULA_FISICA("01", "Cédula Física"),
    CEDULA_JURIDICA("02", "Cédula Jurídica"),
    DIMEX("03", "DIMEX"),
    NITE("04", "NITE");
    
    private final String codigo;
    private final String descripcion;

    TiposIdentificacion(String codigo, String descripcion) {
        this.codigo = codigo;
        this.descripcion = descripcion;
    }

    public String getCodigo() {
        return codigo;
    }

    public String getDescripcion() {
        return descripcion;
    }

    // Método estático para obtener un TipoIdentificacion a partir del código
    public static TiposIdentificacion fromCodigo(String codigo) {
        for (TiposIdentificacion tipo : TiposIdentificacion.values()) {
            if (tipo.getCodigo().equals(codigo)) {
                return tipo;
            }
        }
        throw new IllegalArgumentException("Código de tipo de identificación no válido: " + codigo);
    }
}
