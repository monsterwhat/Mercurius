package Models.Enums;

public enum NombreInstitucion { 

    MINISTERIO_HACIENDA("01", "Ministerio de Hacienda"),
    RELACIONES_EXTERIORES("02", "Ministerio de Relaciones Exteriores y Culto"),
    AGRICULTURA_GANADERIA("03", "Ministerio de Agricultura y Ganadería"),
    ECONOMIA_INDUSTRIA_COMERCIO("04", "Ministerio de Economía, Industria y Comercio"),
    CRUZ_ROJA("05", "Cruz Roja Costarricense"),
    CUERPO_BOMBEROS("06", "Benemérito Cuerpo de Bomberos de Costa Rica"),
    OBRAS_ESPIRITU_SANTO("07", "Asociación Obras del Espíritu Santo"),
    FECRUNAPA("08", "Federación Cruzada Nacional de protección al Anciano (Fecrunapa)"),
    EARTH("09", "Escuela de Agricultura de la Región Húmeda (EARTH)"),
    INCAE("10", "Instituto Centroamericano de Administración de Empresas (INCAE)"),
    JPS("11", "Junta de Protección Social (JPS)"),
    ARESEP("12", "Autoridad Reguladora de los Servicios Públicos (Aresep)"),
    OTROS("99", "Otros");

    private final String codigo;
    private final String nombre;

    NombreInstitucion(String codigo, String nombre) {
        this.codigo = codigo;
        this.nombre = nombre;
    }

    public String getCodigo() {
        return codigo;
    }

    public String getNombre() {
        return nombre;
    }

    public static NombreInstitucion fromCodigo(String codigo) {
        for (NombreInstitucion inst : values()) {
            if (inst.codigo.equals(codigo)) {
                return inst;
            }
        }
        throw new IllegalArgumentException("Código desconocido: " + codigo);
    }
}
