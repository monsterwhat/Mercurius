package Models.Enums;

public enum Tipo_Transaccion {
    VENTA_NORMAL("01", "Venta Normal de Bienes y Servicios (Transacción General)"),
    AUTOCONSUMO_EXENTO_BIEN("02", "Mercancía de Autoconsumo exento"),
    AUTOCONSUMO_GRAVADO_BIEN("03", "Mercancía de Autoconsumo gravado"),
    AUTOCONSUMO_EXENTO_SERVICIO("04", "Servicio de Autoconsumo exento"),
    AUTOCONSUMO_GRAVADO_SERVICIO("05", "Servicio de Autoconsumo gravado"),
    CUOTA_AFILIACION("06", "Cuota de afiliación"),
    CUOTA_AFILIACION_EXENTA("07", "Cuota de afiliación Exenta"),
    BIEN_CAPITAL_EMISOR("08", "Bienes de Capital para el emisor"),
    BIEN_CAPITAL_RECEPTOR("09", "Bienes de Capital para el receptor."),
    BIEN_CAPITAL_AMBOS("10", "Bienes de Capital para para el emisor y el receptor."),
    AUTOCONSUMO_EXENTO_CAPITAL_EMISOR("11", "Bienes de capital de autoconsumo exento para el emisor"),
    SIN_CONTRAPRESTACION_EXENTO_EMISOR("12", "Bienes de capital sin contraprestación a terceros exento para el emisor"),
    SIN_CONTRAPRESTACION_TERCEROS("13", "Sin contraprestación a terceros");

    private final String codigo;
    private final String descripcion;

    Tipo_Transaccion(String codigo, String descripcion) {
        this.codigo = codigo;
        this.descripcion = descripcion;
    }

    public String getCodigo() {
        return codigo;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public static Tipo_Transaccion fromCodigo(String codigo) {
        for (Tipo_Transaccion tipo : Tipo_Transaccion.values()) {
            if (tipo.getCodigo().equals(codigo)) {
                return tipo;
            }
        }
        throw new IllegalArgumentException("Código de tipo de documento de exoneración o autorización no válido: " + codigo);
    }
}
