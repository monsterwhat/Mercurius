package Models.Comprobantes.Enums;

/**
 *
 * @author Al
 */

public enum TipoCodigoProductoServicio {
    CODIGO_PRODUCTO_VENDEDOR("01", "Código del producto del vendedor"),
    CODIGO_PRODUCTO_COMPRADOR("02", "Código del producto del comprador"),
    CODIGO_PRODUCTO_INDUSTRIA("03", "Código del producto asignado por la industria"),
    CODIGO_USO_INTERNO("04", "Código uso interno"),
    OTROS("99", "Otros");

    private final String codigo;
    private final String descripcion;

    TipoCodigoProductoServicio(String codigo, String descripcion) {
        this.codigo = codigo;
        this.descripcion = descripcion;
    }

    public String getCodigo() {
        return codigo;
    }

    public String getDescripcion() {
        return descripcion;
    }

    // Método estático para obtener un TipoCodigoProductoServicio a partir del código
    public static TipoCodigoProductoServicio fromCodigo(String codigo) {
        for (TipoCodigoProductoServicio tipo : TipoCodigoProductoServicio.values()) {
            if (tipo.getCodigo().equals(codigo)) {
                return tipo;
            }
        }
        throw new IllegalArgumentException("Código de tipo de producto/servicio no válido: " + codigo);
    }
}
