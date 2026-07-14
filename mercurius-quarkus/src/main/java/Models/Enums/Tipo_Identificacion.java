package Models.Enums;

import jakarta.annotation.Nonnull;

public enum Tipo_Identificacion {
    CEDULA_FISICA("01", "Cédula Física"),
    CEDULA_JURIDICA("02", "Cédula Jurídica"),
    DIMEX("03", "DIMEX"),
    NITE("04", "NITE"),
    EXTRANJERO_NO_DOMICILIADO("05","Extranjero No Domiciliado"),
    NO_CONTRIBUYENTE("06","No Contribuyente");
    
    @Nonnull
    private final String codigo;
    @Nonnull
    private final String descripcion;

    Tipo_Identificacion(@Nonnull String codigo, @Nonnull String descripcion) {
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
    public static Tipo_Identificacion fromCodigo(@Nonnull String codigo) {
        for (Tipo_Identificacion tipo : Tipo_Identificacion.values()) {
            if (tipo.getCodigo().equals(codigo)) {
                return tipo;
            }
        }
        throw new IllegalArgumentException("Código de tipo de identificación no válido: " + codigo);
    }
}
