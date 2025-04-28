package Models.ComprobantesV44.Enums;

/**
 *
 * @author Al
 */

public enum Tipo_CondicionImpuesto {
    GENERA_CREDITO_IVA("01", "Genera crédito IVA"),
    GENERA_CREDITO_PARCIAL_IVA("02", "Genera Crédito parcial del IVA"),
    BIENES_DE_CAPITAL("03", "Bienes de Capital"),
    GASTO_CORRIENTE_NO_GENERA_CREDITO("04", "Gasto corriente no genera crédito"),
    PROPORCIONALIDAD("05", "Proporcionalidad");

    private final String codigo;
    private final String descripcion;

    Tipo_CondicionImpuesto(String codigo, String descripcion) {
        this.codigo = codigo;
        this.descripcion = descripcion;
    }

    public String getCodigo() {
        return codigo;
    }

    public String getDescripcion() {
        return descripcion;
    }

    // Método estático para obtener una CondicionImpuesto a partir del código
    public static Tipo_CondicionImpuesto fromCodigo(String codigo) {
        for (Tipo_CondicionImpuesto condicion : Tipo_CondicionImpuesto.values()) {
            if (condicion.getCodigo().equals(codigo)) {
                return condicion;
            }
        }
        throw new IllegalArgumentException("Código de condición de impuesto no válido: " + codigo);
    }
}

