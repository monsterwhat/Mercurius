package Models.Comprobantes.Enums;

/**
 *
 * @author Al
 */

public enum MedioPagoEnum {
    EFECTIVO("01", "Efectivo"),
    TARJETA("02", "Tarjeta"),
    CHEQUE("03", "Cheque"),
    TRANSFERENCIA_DEPOSITO("04", "Transferencia – depósito bancario"),
    RECAUDADO_POR_TERCEROS("05", "Recaudado por terceros"),
    OTROS("99", "Otros (se debe indicar el medio de pago)");

    private final String codigo;
    private final String descripcion;

    MedioPagoEnum(String codigo, String descripcion) {
        this.codigo = codigo;
        this.descripcion = descripcion;
    }

    public String getCodigo() {
        return codigo;
    }

    public String getDescripcion() {
        return descripcion;
    }

    // Método estático para obtener un MedioPagoEnum a partir del código
    public static MedioPagoEnum fromCodigo(String codigo) {
        for (MedioPagoEnum medio : MedioPagoEnum.values()) {
            if (medio.getCodigo().equals(codigo)) {
                return medio;
            }
        }
        throw new IllegalArgumentException("Código de medio de pago no válido: " + codigo);
    }
}

