package Models.Enums;

import jakarta.annotation.Nonnull;

public enum Tipo_IVA_Fabrica {

    CON_IVA("01", "Venta de bienes con IVA según el sistema especial de determinación de IVA a nivel de fábrica"),
    EXENTO("02", "Ventas exentas según el sistema especial de determinación de IVA a nivel de fábrica, mayorista y aduanas");

    @Nonnull
    private final String codigo;
    @Nonnull
    private final String descripcion;

    Tipo_IVA_Fabrica(@Nonnull String codigo, @Nonnull String descripcion) {
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
    public static Tipo_IVA_Fabrica fromCodigo(@Nonnull String codigo) {
        for (Tipo_IVA_Fabrica tipo : values()) {
            if (tipo.codigo.equals(codigo)) {
                return tipo;
            }
        }
        throw new IllegalArgumentException("Código desconocido: " + codigo);
    }
}
