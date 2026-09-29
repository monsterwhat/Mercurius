package Services.pronostico;

/**
 * Hiperparametros de los metodos de suavizado.
 *
 * <p>Convenciones:
 * <ul>
 *   <li>{@code alfa} — peso del nivel (0, 1].</li>
 *   <li>{@code beta} — peso de la tendencia (0, 1].</li>
 *   <li>{@code gamma} — peso del componente estacional (0, 1].</li>
 *   <li>{@code phi} — factor de amortiguacion de la tendencia (0, 1]; con phi &lt; 1 la suma
 *       phi + phi^2 + ... + phi^h converge y el pronostico de Holt queda acotado. Lo usa
 *       {@link MetodoPronostico#HOLT_AMORTIGUADO}; {@link MetodoPronostico#HOLT_WINTERS} sigue el
 *       modelo aditivo estandar (no amortiguado) y por tanto ignora {@code phi}.</li>
 * </ul>
 *
 * <p>Los valores fuera de rango se acotan (no se rechaza la peticion) y se registran por log; ver
 * {@link MotorPronostico}.
 *
 * @param alfa  suavizado del nivel
 * @param beta  suavizado de la tendencia
 * @param gamma suavizado estacional
 * @param phi   amortiguacion de la tendencia
 */
public record Hiperparametros(double alfa, double beta, double gamma, double phi) {

    /**
     * Valores por defecto del dominio: suavizado suave, amortiguacion fuerte (phi = 0.9) que
     * mantiene acotado el pronostico de tendencia.
     *
     * @return hiperparametros por defecto
     */
    public static Hiperparametros defecto() {
        return new Hiperparametros(0.1, 0.05, 0.1, 0.9);
    }
}
