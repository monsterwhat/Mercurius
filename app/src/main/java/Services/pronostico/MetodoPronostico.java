package Services.pronostico;

/**
 * Metodos de pronostico de demanda supported por {@link MotorPronostico}.
 *
 * <p>Sustituye al motor ad-hoc de {@code Services.StockForecastService} (media simple de 90 dias
 * con factor de tendencia multiplicativo geométrico, que explode en horizontes largos y nunca aplica
 * la estacionalidad calculada).
 *
 * <p>Todos los metodos operan sobre una rejilla diaria regular construida a partir de la historia
 * (ver {@link SerieDiaria}); los dias sin movimiento de tipo {@code 'Venta'} se materializan con
 * cantidad 0 entre la primera y la ultima observacion.
 */
public enum MetodoPronostico {

    /** Ingenuo: repite la ultima observacion. */
    INGENUO,

    /** Estacional ingenuo: repite el valor de hace 7 dias (o el ultimo disponible del mismo dia de semana). */
    ESTACIONAL_INGENUO,

    /** Media movil: media de las ultimas min(28, n) observaciones, plana. */
    MEDIA_MOVIL,

    /** Suavizamiento exponencial simple (SES): sin tendencia, pronostico plano. */
    SES,

    /** Holt con tendencia amortiguada (damped trend): acotado por construccion. */
    HOLT_AMORTIGUADO,

    /** Holt-Winters aditivo con estacionalidad semanal (m = 7). Requiere >= 28 observaciones. */
    HOLT_WINTERS,

    /**
     * Syntetic Bass / Croston simple ajustado (SBA) para demanda intermitente.
     * Solo se actualiza sobre las observaciones no nulas.
     */
    SBA
}
