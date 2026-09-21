package Models.Enums;

import jakarta.annotation.Nonnull;

public enum Tipo_Documento_Exoneracion {
    COMPRAS_AUTORIZADAS("01", "Compras autorizadas"),
    VENTAS_EXENTAS_DIPLOMATICOS("02", "Ventas exentas a diplomáticos"),
    AUTORIZADO_POR_LEY_ESPECIAL("03", "Autorizado por Ley especial"),
    EXENCIONES_DIRECCION_GENERAL_HACIENDA("04", "Exenciones Dirección General de Hacienda"),
    TRANSITORIO_V("05", "Transitorio V"),
    TRANSITORIO_IX("06", "Transitorio IX"),
    TRANSITORIO_XVII("07", "Transitorio XVII"),
    EXONERACION_ZONA_FRANCA("08","Exoneración a Zona Franca"),
    EXONERACION_SERVICIOS_COMPLEMENTARIOS("09","Exoneración de servicios complementarios para la exportación articulo 11 RLIVA"),
    ORGANO_CORPORACIONES_MUNICIPALES("10","Organo de las corporaciones municipales "),
    EXENCIONES_HACIENDA_IMPUESTO_LOCAL("11","Exenciones Dirección General de Hacienda Autorización de Impuesto Local Concreta"),
    OTROS("99", "Otros");

    @Nonnull
    private final String codigo;
    @Nonnull
    private final String descripcion;

    Tipo_Documento_Exoneracion(String codigo, String descripcion) {
        this.codigo = codigo;
        this.descripcion = descripcion;
    }

    public String getCodigo() {
        return codigo;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public static Tipo_Documento_Exoneracion fromCodigo(String codigo) {
        for (Tipo_Documento_Exoneracion tipo : Tipo_Documento_Exoneracion.values()) {
            if (tipo.getCodigo().equals(codigo)) {
                return tipo;
            }
        }
        throw new IllegalArgumentException("Código de tipo de documento de exoneración o autorización no válido: " + codigo);
    }
}
