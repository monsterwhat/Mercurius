package Models.Enums;

public enum Tipo_CondicionVenta {
    CONTADO("01", "Contado"),
    CREDITO("02", "Crédito"),
    CONSIGNACION("03", "Consignación"),
    APARTADO("04", "Apartado"),
    ARRENDAMIENTO_CON_OPCION("05", "Arrendamiento con opción de compra"),
    ARRENDAMIENTO_FUNCION_FINANCIERA("06", "Arrendamiento en función financiera"),
    COBRO_A_FAVOR_DE_UN_TERCERO("07", "Cobro a favor de un tercero"),
    SERVICIOS_PRESTADOS_AL_ESTADO("08", "Servicios prestados al Estado a crédito"),
    PAGO_SERVICIOS_PRESTADOS_AL_ESTADO("09", "Pago del servicios prestado al Estado"),
    VENTA_CREDITO_IVA_HASTA_90_DIAS("10", "Venta a crédito en IVA hasta 90 días (Artículo 27, LIVA)"),
    PAGO_VENTA_CREDITO_IVA_HASTA_90_DIAS("11", "Pago de venta a crédito en IVA hasta 90 días (Artículo 27, LIVA)"),
    VENTA_MERCANCIA_NO_NACIONALIZADA("12","Venta de Mercaderia no nacionalizada"),
    VENTA_BIENES_USADOS_NO_CONTRIBUYENTE("13","Venta de bienes usados no contribuyentes"),
    ARRENDAMIENTO_OPERATIVO("14","Arrendamiento Operativo"),
    ARRENDAMIENTO_FINANCIERO("15","Arrendamiento Financiero"),
    OTROS("99", "Otros (se debe indicar la condición de la venta)");

    private final String codigo;
    private final String descripcion;

    Tipo_CondicionVenta(String codigo, String descripcion) {
        this.codigo = codigo;
        this.descripcion = descripcion;
    }

    public String getCodigo() {
        return codigo;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public static Tipo_CondicionVenta fromCodigo(String codigo) {
        for (Tipo_CondicionVenta condicion : Tipo_CondicionVenta.values()) {
            if (condicion.getCodigo().equals(codigo)) {
                return condicion;
            }
        }
        throw new IllegalArgumentException("Código de condición de venta no válido: " + codigo);
    }
}
