package Models.Comprobantes.Enums;

/**
 *
 * @author Al
 */

public enum Mensaje {
    ACEPTADO("1", "Aceptado"),
    ACEPTACION_PARCIAL("2", "Aceptación parcial"),
    RECHAZADO("3", "Rechazado");

    private final String codigo;
    private final String descripcion;

    Mensaje(String codigo, String descripcion) {
        this.codigo = codigo;
        this.descripcion = descripcion;
    }

    public String getCodigo() {
        return codigo;
    }

    public String getDescripcion() {
        return descripcion;
    }

    // Método estático para obtener un Mensaje a partir del código
    public static Mensaje fromCodigo(String codigo) {
        for (Mensaje mensaje : Mensaje.values()) {
            if (mensaje.getCodigo().equals(codigo)) {
                return mensaje;
            }
        }
        throw new IllegalArgumentException("Código de mensaje no válido: " + codigo);
    }
}

