package Models.Comprobantes.Enums;

/**
 *
 * @author Al
 */

public enum TipoDocumentoExoneracion {
    COMPRAS_AUTORIZADAS("01", "Compras autorizadas"),
    VENTAS_EXENTAS_DIPLOMATICOS("02", "Ventas exentas a diplomáticos"),
    AUTORIZADO_POR_LEY_ESPECIAL("03", "Autorizado por Ley especial"),
    EXENCIONES_DIRECCION_GENERAL_HACIENDA("04", "Exenciones Dirección General de Hacienda"),
    TRANSITORIO_V("05", "Transitorio V"),
    TRANSITORIO_IX("06", "Transitorio IX"),
    TRANSITORIO_XVII("07", "Transitorio XVII"),
    OTROS("99", "Otros");

    private final String codigo;
    private final String descripcion;

    TipoDocumentoExoneracion(String codigo, String descripcion) {
        this.codigo = codigo;
        this.descripcion = descripcion;
    }

    public String getCodigo() {
        return codigo;
    }

    public String getDescripcion() {
        return descripcion;
    }

    // Método estático para obtener un TipoDocumentoExoneracion a partir del código
    public static TipoDocumentoExoneracion fromCodigo(String codigo) {
        for (TipoDocumentoExoneracion tipo : TipoDocumentoExoneracion.values()) {
            if (tipo.getCodigo().equals(codigo)) {
                return tipo;
            }
        }
        throw new IllegalArgumentException("Código de tipo de documento de exoneración o autorización no válido: " + codigo);
    }
}

