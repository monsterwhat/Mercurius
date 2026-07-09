package Models.Enums;

import jakarta.annotation.Nonnull;

public enum Tipo_MedioPago {
    EFECTIVO("01", "Efectivo"),
    TARJETA("02", "Tarjeta"),
    CHEQUE("03", "Cheque"),
    TRANSFERENCIA_DEPOSITO("04", "Transferencia – depósito bancario"),
    RECAUDADO_POR_TERCEROS("05", "Recaudado por terceros"),
    SINPE_MOVIL("06","SINPE MOVIL"),
    PLATAFORMA_DIGITAL("07","Plataforma Digital"),
    OTROS("99", "Otros (se debe indicar el medio de pago)");

    @Nonnull
    private final String codigo;
    @Nonnull
    private final String descripcion;

    Tipo_MedioPago(@Nonnull String codigo, @Nonnull String descripcion) {
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
    public static Tipo_MedioPago fromCodigo(@Nonnull String codigo) {
        for (Tipo_MedioPago medio : Tipo_MedioPago.values()) {
            if (medio.getCodigo().equals(codigo)) {
                return medio;
            }
        }
        throw new IllegalArgumentException("Código de medio de pago no válido: " + codigo);
    }
}
