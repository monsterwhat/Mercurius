package Models.Enums;

public enum Tipo_TarifaIVA {
    TARIFA_0_EXENTO("01", "Tarifa 0% (Exento)"),
    TARIFA_REDUCIDA_1("02", "Tarifa reducida 1%"),
    TARIFA_REDUCIDA_2("03", "Tarifa reducida 2%"),
    TARIFA_REDUCIDA_4("04", "Tarifa reducida 4%"),
    TRANSITORIO_0("05", "Transitorio 0%"),
    TRANSITORIO_4("06", "Transitorio 4%"),
    TRANSITORIO_8("07", "Transitorio 8%"),
    TARIFA_GENERAL_13("08", "Tarifa general 13%"),
    TARIFA_REDUCIDA_05("09", "Tarifa reducida 0.5%"),
    TARIFA_EXENTA("10","Tarifa Exenta"),
    TARIFA_0_SIN_CREDITO("11","Tarifa 0 sin derecho a credito");

    private final String codigo;
    private final String descripcion;

    Tipo_TarifaIVA(String codigo, String descripcion) {
        this.codigo = codigo;
        this.descripcion = descripcion;
    }

    public String getCodigo() {
        return codigo;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public static Tipo_TarifaIVA fromCodigo(String codigo) {
        for (Tipo_TarifaIVA tarifa : Tipo_TarifaIVA.values()) {
            if (tarifa.getCodigo().equals(codigo)) {
                return tarifa;
            }
        }
        throw new IllegalArgumentException("Código de tarifa de IVA no válido: " + codigo);
    }
    
    public static Tipo_TarifaIVA getTarifa(String codigoImpuesto) {
        Tipo_TarifaIVA tarifa;
        switch (codigoImpuesto) {
            case "":
            case "0":
                tarifa = Tipo_TarifaIVA.TARIFA_0_EXENTO;
                break;
            case "0.5":
                tarifa = Tipo_TarifaIVA.TARIFA_REDUCIDA_05;
                break;
            case "1":
                tarifa = Tipo_TarifaIVA.TARIFA_REDUCIDA_1;
                break;
            case "2":
                tarifa = Tipo_TarifaIVA.TARIFA_REDUCIDA_2;
                break;
            case "4":
                tarifa = Tipo_TarifaIVA.TARIFA_REDUCIDA_4;
                break;
            case "8":
                tarifa = Tipo_TarifaIVA.TRANSITORIO_8;
                break;
            case "13":
                tarifa = Tipo_TarifaIVA.TARIFA_GENERAL_13;
                break;
            default:
                throw new IllegalArgumentException("Código de impuesto no válido: " + codigoImpuesto);
        }
        return tarifa;
    }

}
