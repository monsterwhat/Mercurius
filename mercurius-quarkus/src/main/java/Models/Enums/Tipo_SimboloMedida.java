package Models.Enums;

import jakarta.annotation.Nonnull;

public enum Tipo_SimboloMedida {
    AL("Al", "Alquiler de uso habitacional"),
    ALC("Alc", "Alquiler de uso comercial"),
    CM("Cm", "Comisiones"),
    I("I", "Intereses"),
    OS("Os", "Otro tipo de servicio"),
    SP("Sp", "Servicios Profesionales"),
    SPE("Spe", "Servicios personales"),
    ST("St", "Servicios técnicos"),
    UNO("1", "Uno (índice de refracción)"),
    MINUTO("´", "Minuto"),
    SEGUNDO("´´", "Segundo"),
    CELSIUS("°C", "Grado Celsius"),
    POR_METRO("1/m", "1 por metro"),
    A("A", "Ampere"),
    A_POR_M("A/m", "Ampere por metro"),
    A_POR_M2("A/m²", "Ampere por metro cuadrado"),
    B("B", "Bel"),
    BQ("Bq", "Becquerel"),
    C("C", "Coulomb"),
    C_POR_KG("C/kg", "Coulomb por kilogramo"),
    C_POR_M2("C/m²", "Coulomb por metro cuadrado"),
    C_POR_M3("C/m³", "Coulomb por metro cúbico"),
    CD("cd", "Candela"),
    CD_POR_M2("cd/m²", "Candela por metro cuadrado"),
    CeM("cm", "Centímetro"),
    D("d", "Día"),
    EV("eV", "Electronvolt"),
    F("F", "Farad"),
    F_POR_M("F/m", "Farad por metro"),
    G("g", "Gramo"),
    GAL("Gal", "Galón"),
    GY("Gy", "Gray"),
    GY_POR_S("Gy/s", "Gray por segundo"),
    H("H", "Henry"),
    HORA("h", "Hora"),
    H_POR_M("H/m", "Henry por metro"),
    HZ("Hz", "Hertz"),
    J("J", "Joule"),
    J_POR_KG_K("J/(kg·K)", "Joule por kilogramo kelvin"),
    J_POR_MOL_K("J/(mol·K)", "Joule por mol kelvin"),
    J_POR_K("J/K", "Joule por kelvin"),
    J_POR_KG("J/kg", "Joule por kilogramo"),
    J_POR_M3("J/m³", "Joule por metro cúbico"),
    J_POR_MOL("J/mol", "Joule por mol"),
    K("K", "Kelvin"),
    KAT("kat", "Katal"),
    KAT_POR_M3("kat/m³", "Katal por metro cúbico"),
    KG("kg", "Kilogramo"),
    KG_POR_M3("kg/m³", "Kilogramo por metro cúbico"),
    KM("Km", "Kilómetro"),
    KW("Kw", "Kilovatios"),
    L("L", "Litro"),
    LM("lm", "Lumen"),
    LN("ln", "Pulgada"),
    LX("lx", "Lux"),
    M("m", "Metro"),
    M_POR_S("m/s", "Metro por segundo"),
    M_POR_S2("m/s²", "Metro por segundo cuadrado"),
    M2("m²", "Metro cuadrado"),
    M3("m³", "Metro cúbico"),
    MIN("min", "Minuto"),
    ML("mL", "Mililitro"),
    MM("mm", "Milímetro"),
    MOL("mol", "Mol"),
    MOL_POR_M3("mol/m³", "Mol por metro cúbico"),
    N("N", "Newton"),
    N_POR_M("N/m", "Newton por metro"),
    NM("N·m", "Newton metro"),
    NP("Np", "Neper"),
    GRADO("º", "Grado"),
    OZ("Oz", "Onzas"),
    PA("Pa", "Pascal"),
    PA_S("Pa·s", "Pascal segundo"),
    RAD("rad", "Radián"),
    RAD_POR_S("rad/s", "Radián por segundo"),
    RAD_POR_S2("rad/s²", "Radián por segundo cuadrado"),
    S("s", "Segundo"),
    SIE("S", "Siemens"),
    SR("sr", "Estereorradián"),
    SV("Sv", "Sievert"),
    T("T", "Tesla"),
    TON("t", "Tonelada"),
    U("u", "Unidad de masa atómica unificada"),
    UA("ua", "Unidad astronómica"),
    UNID("Unid", "Unidad"),
    V("V", "Volt"),
    V_POR_M("V/m", "Volt por metro"),
    W("W", "Watt"),
    W_POR_M_K("W/(m·K)", "Watt por metro kelvin"),
    W_POR_M2_SR("W/(m²·sr)", "Watt por metro cuadrado estereorradián"),
    W_M2("W/m²", "Watt por metro cuadrado"),
    W_SR("W/sr", "Watt por estereorradián"),
    WB("Wb", "Weber"),
    OHM("Ω", "Ohm"),
    OTROS("Otros", "Se debe indicar la descripción de la medida a utilizar");

    private final String simbolo;
    private final String descripcion;

    Tipo_SimboloMedida(String simbolo, String descripcion) {
        this.simbolo = simbolo;
        this.descripcion = descripcion;
    }

    @Nonnull
    public String getSimbolo() {
        return simbolo;
    }

    @Nonnull
    public String getDescripcion() {
        return descripcion;
    }

    @Nonnull
    public static Tipo_SimboloMedida fromSimbolo(@Nonnull String simbolo) {
        for (Tipo_SimboloMedida medida : Tipo_SimboloMedida.values()) {
            if (medida.getSimbolo().equals(simbolo)) {
                return medida;
            }
        }
        throw new IllegalArgumentException("Símbolo de medida no válido: " + simbolo);
    }
}
