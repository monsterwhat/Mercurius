package Services.pronostico;

/**
 * Regimen de demanda segun el cuadrante ADI / CV^2 de Syntetos-Boylan.
 *
 * <p>Los cortes son ADI = 1.32 y CV^2 = 0.49 (implementados en {@link MotorPronostico#clasificar}).
 */
public enum Regimen {

    /** ADI bajo y CV^2 bajo: demanda estable, metodos de nivel. */
    SUAVE,

    /** ADI bajo y CV^2 alto: demanda variable sin ceros, metodos robustos. */
    ERRATICO,

    /** ADI alto y CV^2 bajo: ceros frecuentes con magnitudes estables. */
    INTERMITENTE,

    /** ADI alto y CV^2 alto: ceros frecuentes y magnitudes volatiles. */
    GRUMOSO,

    /** Historia vacia o completamente nula. */
    SIN_DATOS
}
