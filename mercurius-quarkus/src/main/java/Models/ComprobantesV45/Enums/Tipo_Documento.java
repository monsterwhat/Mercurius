package Models.ComprobantesV45.Enums;

public enum Tipo_Documento {

    CONTRIBUCION_PARAFISCAL("01", "Contribución parafiscal"),
    TIMBRE_CRUZ_ROJA("02", "Timbre de la Cruz Roja"),
    TIMBRE_BOMBEROS("03", "Timbre de Benemérito Cuerpo de Bomberos de Costa Rica"),
    COBRO_TERCERO("04", "Cobro de un tercero"),
    COSTOS_EXPORTACION("05", "Costos de Exportación"),
    IMPUESTO_SERVICIO("06", "Impuesto de servicio 10%"),
    TIMBRE_COLEGIOS_PROFESIONALES("07", "Timbre de Colegios Profesionales"),
    DEPOSITOS_GARANTIA("08", "Depósitos de Garantía"),
    MULTAS_PENALIZACIONES("09", "Multas o Penalizaciones"),
    INTERESES_MORATORIOS("10", "Intereses Moratorios"),
    OTROS_CARGOS("99", "Otros Cargos");

    private final String codigo;
    private final String descripcion;

    Tipo_Documento(String codigo, String descripcion) {
        this.codigo = codigo;
        this.descripcion = descripcion;
    }

    public String getCodigo() {
        return codigo;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public static Tipo_Documento fromCodigo(String codigo) {
        for (Tipo_Documento tipo : values()) {
            if (tipo.codigo.equals(codigo)) {
                return tipo;
            }
        }
        throw new IllegalArgumentException("Código desconocido: " + codigo);
    }
}
