package Models.ComprobantesV44.Enums;

/**
 *
 * @author Al
 */

public enum Tipo_CodigoImpuesto {
    IVA("01", "Impuesto al Valor Agregado"),
    IMPUESTO_SELECTIVO_CONSUMO("02", "Impuesto Selectivo de Consumo"),
    IMPUESTO_UNICO_COMBUSTIBLES("03", "Impuesto Único a los Combustibles"),
    IMPUESTO_ESPECIFICO_ALCOHOL("04", "Impuesto específico de Bebidas Alcohólicas"),
    IMPUESTO_ESPECIFICO_BEBIDAS_ENVASADAS("05", "Impuesto Específico sobre las bebidas envasadas sin contenido alcohólico y jabones de tocador"),
    IMPUESTO_PRODUCTOS_TABACO("06", "Impuesto a los Productos de Tabaco"),
    IVA_CALCULO_ESPECIAL("07", "IVA (cálculo especial)"),
    IVA_REGIMEN_BIENES_USADOS("08", "IVA Régimen de Bienes Usados (Factor)"),
    IMPUESTO_ESPECIFICO_CEMENTO("12", "Impuesto Específico al Cemento"),
    OTROS("99", "Otros");

    private final String codigo;
    private final String descripcion;

    Tipo_CodigoImpuesto(String codigo, String descripcion) {
        this.codigo = codigo;
        this.descripcion = descripcion;
    }

    public String getCodigo() {
        return codigo;
    }

    public String getDescripcion() {
        return descripcion;
    }

    // Método estático para obtener un CodigoImpuesto a partir del código
    public static Tipo_CodigoImpuesto fromCodigo(String codigo) {
        for (Tipo_CodigoImpuesto impuesto : Tipo_CodigoImpuesto.values()) {
            if (impuesto.getCodigo().equals(codigo)) {
                return impuesto;
            }
        }
        throw new IllegalArgumentException("Código de impuesto no válido: " + codigo);
    }
    
    // Método para obtener el código de letra según el impuesto
    public static String getCodigoLetra(int porcentajeImpuesto) {
        switch (porcentajeImpuesto) {
            case 0:
                return "E";
            case 1:
                return "U";
            case 2:
                return "D";
            case 13:
                return "T";
            default:
                throw new IllegalArgumentException("Porcentaje de impuesto no válido: " + porcentajeImpuesto);
        }
    }
}

