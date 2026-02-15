package Models.Enums;

public enum Tipo_MedioPago {
    EFECTIVO("01", "Efectivo"),
    TARJETA("02", "Tarjeta"),
    CHEQUE("03", "Cheque"),
    TRANSFERENCIA_DEPOSITO("04", "Transferencia – depósito bancario"),
    RECAUDADO_POR_TERCEROS("05", "Recaudado por terceros"),
    SINPE_MOVIL("06","SINPE MOVIL"),
    PLATAFORMA_DIGITAL("07","Plataforma Digital"),
    OTROS("99", "Otros (se debe indicar el medio de pago)");

    private final String codigo;
    private final String descripcion;

    Tipo_MedioPago(String codigo, String descripcion) {
        this.codigo = codigo;
        this.descripcion = descripcion;
    }

    public String getCodigo() {
        return codigo;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public static Tipo_MedioPago fromCodigo(String codigo) {
        for (Tipo_MedioPago medio : Tipo_MedioPago.values()) {
            if (medio.getCodigo().equals(codigo)) {
                return medio;
            }
        }
        throw new IllegalArgumentException("Código de medio de pago no válido: " + codigo);
    }
}
