package Models.Enums;

public enum Tipo_Mensaje {
    ACEPTADO("1", "Aceptado"),
    ACEPTACION_PARCIAL("2", "Aceptación parcial"),
    RECHAZADO("3", "Rechazado");

    private final String codigo;
    private final String descripcion;

    Tipo_Mensaje(String codigo, String descripcion) {
        this.codigo = codigo;
        this.descripcion = descripcion;
    }

    public String getCodigo() {
        return codigo;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public static Tipo_Mensaje fromCodigo(String codigo) {
        for (Tipo_Mensaje mensaje : Tipo_Mensaje.values()) {
            if (mensaje.getCodigo().equals(codigo)) {
                return mensaje;
            }
        }
        throw new IllegalArgumentException("Código de mensaje no válido: " + codigo);
    }
}
