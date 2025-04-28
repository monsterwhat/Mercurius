package Models.ComprobantesV44.Enums;

/**
 *
 * @author Al
 */

public enum Tipo_Identificacion {
    CEDULA_FISICA("01", "Cédula Física"),
    CEDULA_JURIDICA("02", "Cédula Jurídica"),
    DIMEX("03", "DIMEX"),
    NITE("04", "NITE"),
    EXTRANJERO_NO_DOMICILIADO("05","Extranjero No Domiciliado"),
    NO_CONTRIBUYENTE("06","No Contribuyente");
    
    private final String codigo;
    private final String descripcion;

    Tipo_Identificacion(String codigo, String descripcion) {
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
    public static Tipo_Identificacion fromCodigo(String codigo) {
        for (Tipo_Identificacion tipo : Tipo_Identificacion.values()) {
            if (tipo.getCodigo().equals(codigo)) {
                return tipo;
            }
        }
        throw new IllegalArgumentException("Código de tipo de identificación no válido: " + codigo);
    }
}
