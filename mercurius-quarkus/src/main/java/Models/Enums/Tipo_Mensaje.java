package Models.Enums;

import jakarta.annotation.Nonnull;

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

    @Nonnull
    public String getCodigo() {
        return codigo;
    }

    @Nonnull
    public String getDescripcion() {
        return descripcion;
    }

    @Nonnull
    public static Tipo_Mensaje fromCodigo(@Nonnull String codigo) {
        for (Tipo_Mensaje mensaje : Tipo_Mensaje.values()) {
            if (mensaje.getCodigo().equals(codigo)) {
                return mensaje;
            }
        }
        throw new IllegalArgumentException("Código de mensaje no válido: " + codigo);
    }
}
