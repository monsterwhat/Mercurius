package Models.Enums;

public enum Tipo_Codigo_Descuento {
    DESCUENTO_POR_REGALIA("01", "Descuento por Regalia"),
    DESCUENTO_POR_REGALIA_IVA_COBRADO_CLIENTE("02", "Descuento por Regalia con IVA cobrado al cliente"),
    DESCUENTO_POR_BONIFICACION("03","Descuento por Bonificacion"),
    DESCUENTO_POR_VOLUMEN("04","Descuento por Volumen"),
    DESCUENTO_POR_TEMPORADA("05","Descuento por Temporada"),
    DESCUENTO_PROMOCIONAL("06","Descuento promocional"),
    DESCUENTO_COMERCIAL("07","Descuento comercial"),
    DESCUENTO_POR_FRECUENCIA("08","Descuento por frecuencia"),
    DESCUENTO_SOSTENIDO("09","Descuento sostenido"),
    OTROS_DESCUENTOS("99","Otros descuentos");

    private final String codigo;
    private final String descripcion;

    Tipo_Codigo_Descuento(String codigo, String descripcion) {
        this.codigo = codigo;
        this.descripcion = descripcion;
    }

    public String getCodigo() {
        return codigo;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public static Tipo_Codigo_Descuento fromCodigo(String codigo) {
        for (Tipo_Codigo_Descuento tipo : Tipo_Codigo_Descuento.values()) {
            if (tipo.getCodigo().equals(codigo)) {
                return tipo;
            }
        }
        throw new IllegalArgumentException("Código de tipo de descuento no válido: " + codigo);
    }
}
