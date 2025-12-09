package Models.ComprobantesV44.Enums;

public enum Tipo_IVA_Fabrica {

    CON_IVA("01", "Venta de bienes con IVA según el sistema especial de determinación de IVA a nivel de fábrica"),
    EXENTO("02", "Ventas exentas según el sistema especial de determinación de IVA a nivel de fábrica, mayorista y aduanas");

    private final String codigo;
    private final String descripcion;

    Tipo_IVA_Fabrica(String codigo, String descripcion) {
        this.codigo = codigo;
        this.descripcion = descripcion;
    }

    public String getCodigo() {
        return codigo;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public static Tipo_IVA_Fabrica fromCodigo(String codigo) {
        for (Tipo_IVA_Fabrica tipo : values()) {
            if (tipo.codigo.equals(codigo)) {
                return tipo;
            }
        }
        throw new IllegalArgumentException("Código desconocido: " + codigo);
    }
}
