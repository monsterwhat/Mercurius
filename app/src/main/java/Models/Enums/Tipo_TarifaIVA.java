package Models.Enums;

import jakarta.annotation.Nonnull;

public enum Tipo_TarifaIVA {
    /**
     * Código 01: tarifa 0% del artículo 32, número 1, del RLIVA. Solo aplica a
     * las operaciones que califican para ese tratamiento (crédito fiscal
     * pleno; p. ej. ventas a la CCSS o municipalidades). NO es el código
     * genérico de un producto con tasa 0.
     */
    TARIFA_0_EXENTO("01", "Tarifa 0% (Artículo 32.1 RLIVA)"),
    TARIFA_REDUCIDA_1("02", "Tarifa reducida 1%"),
    TARIFA_REDUCIDA_2("03", "Tarifa reducida 2%"),
    TARIFA_REDUCIDA_4("04", "Tarifa reducida 4%"),
    TRANSITORIO_0("05", "Transitorio 0%"),
    TRANSITORIO_4("06", "Transitorio 4%"),
    TRANSITORIO_8("07", "Transitorio 8%"),
    TARIFA_GENERAL_13("08", "Tarifa general 13%"),
    TARIFA_REDUCIDA_05("09", "Tarifa reducida 0.5%"),
    /**
     * Código 10: tarifa exenta de la Ley 9635, artículo 8. Es el código que
     * Hacienda asocia a los códigos CAByS exentos de IVA (Anexo v4.4, nota
     * 8.1 y descripción de TotalServExentos/TotalMercanciasExentas).
     */
    TARIFA_EXENTA("10", "Tarifa Exenta (Ley 9635, Art. 8)"),
    /**
     * Código 11: tarifa 0% sin derecho a crédito (operación no sujeta). El
     * catálogo CAByS solo publica la tasa, no la base legal, así que este
     * código debe elegirse con una clasificación tributaria explícita; no se
     * deriva de {@code impuesto = 0}.
     */
    TARIFA_0_SIN_CREDITO("11", "Tarifa 0% sin derecho a crédito");

    private final String codigo;
    private final String descripcion;

    Tipo_TarifaIVA(String codigo, String descripcion) {
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
    public static Tipo_TarifaIVA fromCodigo(@Nonnull String codigo) {
        for (Tipo_TarifaIVA tarifa : Tipo_TarifaIVA.values()) {
            if (tarifa.getCodigo().equals(codigo)) {
                return tarifa;
            }
        }
        throw new IllegalArgumentException("Código de tarifa de IVA no válido: " + codigo);
    }
    
    @Nonnull
    public static Tipo_TarifaIVA getTarifa(@Nonnull String codigoImpuesto) {
        Tipo_TarifaIVA tarifa;
        switch (codigoImpuesto) {
            case "":
            case "0":
                // Hacienda v4.4 (Anexo, nota 8.1) reserva el código 01 para la
                // tarifa 0% del Art. 32.1 RLIVA y usa el código 10 (Tarifa
                // Exenta) para los CAByS exentos de IVA. El código 11 (0% sin
                // derecho a crédito) exige una clasificación legal explícita
                // que el CAByS no aporta, por lo que una tasa 0 se emite como
                // exenta (10). Si el responsable fiscal clasifica un CAByS
                // como "no sujeto", esa línea debe corregirse a 11.
                tarifa = Tipo_TarifaIVA.TARIFA_EXENTA;
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
