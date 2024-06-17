package Models.Comprobantes.Enums;

/**
 *
 * @author Al
 */

public enum TarifaIVA {
    TARIFA_0_EXENTO("01", "Tarifa 0% (Exento)"),
    TARIFA_REDUCIDA_1("02", "Tarifa reducida 1%"),
    TARIFA_REDUCIDA_2("03", "Tarifa reducida 2%"),
    TARIFA_REDUCIDA_4("04", "Tarifa reducida 4%"),
    TRANSITORIO_0("05", "Transitorio 0%"),
    TRANSITORIO_4("06", "Transitorio 4%"),
    TRANSITORIO_8("07", "Transitorio 8%"),
    TARIFA_GENERAL_13("08", "Tarifa general 13%"),
    TARIFA_REDUCIDA_05("09", "Tarifa reducida 0.5%");

    private final String codigo;
    private final String descripcion;

    TarifaIVA(String codigo, String descripcion) {
        this.codigo = codigo;
        this.descripcion = descripcion;
    }

    public String getCodigo() {
        return codigo;
    }

    public String getDescripcion() {
        return descripcion;
    }

    // Método estático para obtener una TarifaIVA a partir del código
    public static TarifaIVA fromCodigo(String codigo) {
        for (TarifaIVA tarifa : TarifaIVA.values()) {
            if (tarifa.getCodigo().equals(codigo)) {
                return tarifa;
            }
        }
        throw new IllegalArgumentException("Código de tarifa de IVA no válido: " + codigo);
    }
}

