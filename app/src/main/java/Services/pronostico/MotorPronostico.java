package Services.pronostico;

import org.jboss.logging.Logger;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Nucleo matematico de pronostico de demanda de Mercurius.
 *
 * <p>Reemplaza la heuristica de {@code Services.StockForecastService} (media simple de 90 dias con
 * un factor de tendencia multiplicativo que crece de forma geometrica y explota en horizontes
 * largos, mas un factor estacional que se calculaba pero nunca se aplicaba, y sin tratamiento de
 * la demanda intermitente).
 *
 * <p><b>Principios de diseno</b>
 * <ul>
 *   <li>Pureza numerica: sin CDI, sin acceso a base de datos, sin estado. Toda la entrada es
 *       {@link SerieDiaria}. La capa de persistencia (agregacion de movimientos de Inventario con
 *       {@code tipoMovimiento = 'Venta'}) vive fuera de esta clase.</li>
 *   <li>Rejilla diaria regular: antes de aplicar cualquier modelo, la historia se colapsa sobre una
 *       rejilla diaria continua (las fechas repetidas se suman y los dias sin movimiento se
 *       completan con 0 entre la primera y la ultima observacion). Sin esto, SES / Holt / SBA
 *       operating sobre observaciones irregulares darian resultados sin sentido.</li>
 *   <li>Acotamiento: el pronostico de demanda se acota a 0 en la salida (la demanda no puede ser
 *       negativa; un nivel con tendencia negativa sobre una serie que se extingue no debe generar
 *       reposiciones negativas).</li>
 *   <li>Robustez: historias vacias o de un solo punto se resuelven sin excepcion. La unica
 *       excepcion documentada es {@link IllegalArgumentException} de
 *       {@link MetodoPronostico#HOLT_WINTERS} con menos de 28 observaciones, porque el modelo exige
 *       dos ciclos semanales completos para inicializar el componente estacional.</li>
 *   <li>Escalado de hiperparametros: un valor fuera de [0, 1] (o NaN) se sustituye por el valor
 *       por defecto del dominio y se registra por log; no se propaga una tasa imposible.</li>
 * </ul>
 */
public final class MotorPronostico {

    private static final Logger LOG = Logger.getLogger(MotorPronostico.class);

    /** Periodos por ciclo estacional semanal. */
    private static final int CICLO_SEMANAL = 7;

    /** Ventana maxima de la media movil. */
    private static final int VENTANA_MEDIA_MOVIL = 28;

    /** Observaciones minimas para inicializar Holt-Winters (dos ciclos completos y holgura). */
    private static final int MIN_OBS_HOLT_WINTERS = 28;

    /** Observaciones minimas para SES, Holt amortiguado y media movil. */
    private static final int MIN_OBS_SUAVIZADO = 2;

    /** Demandas no nulas minimas para que SBA tenga sentido. */
    private static final int MIN_NO_NULOS_SBA = 3;

    /** Corte de ADI del cuadrante Syntetos-Boylan. */
    private static final double CORTE_ADI = 1.32;

    /** Corte de CV^2 del cuadrante Syntetos-Boylan. */
    private static final double CORTE_CV2 = 0.49;

    /**
     * Tope defensivo de periodos de la rejilla diaria (~10 anos). Evita que un rango de fechas
     * erroneo agote la memoria. Si se supera, se conservan los periodos mas recientes y se avisa.
     */
    private static final int MAX_PERIODOS_REJILLA = 3660;

    /** Guarda contra division por cero en SBA. */
    private static final double EPSILON_INTERVALO = 1e-9;

    private MotorPronostico() {
        // Clase de utilidad: no se instancia.
    }

    /**
     * Pronostica {@code horizonteDias} periodos futuros a partir de la historia.
     *
     * <p>Convenciones de la salida:
     * <ul>
     *   <li>Siempre devuelve exactamente {@code horizonteDias} valores (lista inmutable), indexados
     *       por {@code h = 1..horizonteDias} (h = 1 es el dia siguiente a la ultima observacion).</li>
     *   <li>Los valores negativos se acotan a {@code 0.0}: la demanda no puede ser negativa.</li>
     *   <li>Historia vacia o nula: devuelve {@code horizonteDias} ceros y registra un aviso.</li>
     *   <li>{@code horizonteDias <= 0}: devuelve lista vacia.</li>
     *   <li>{@code p} nulo: se usan {@link Hiperparametros#defecto()}.</li>
     *   <li>Devuelve {@code 0.0} tambien cuando el modelo no puede estimarse (p. ej. SBA sobre una
     *       historia sin ninguna demanda no nula).</li>
     * </ul>
     *
     * @param metodo        metodo de pronostico a aplicar; no puede ser nulo
     * @param historia      historia diaria de demanda; puede estar vacia o ser nula
     * @param horizonteDias numero de periodos a pronosticar
     * @param p             hiperparametros; si es nulo se usan los valores por defecto
     * @return lista inmutable de {@code horizonteDias} pronosticos no negativos
     * @throws IllegalArgumentException si {@code metodo} es nulo, o si se pide
     *                                  {@link MetodoPronostico#HOLT_WINTERS} con menos de
     *                                  {@value #MIN_OBS_HOLT_WINTERS} observaciones
     *                                  (use {@link #elegible(MetodoPronostico, List)} antes de
     *                                  invocar este metodo)
     */
    public static List<Double> pronosticar(MetodoPronostico metodo,
                                           List<SerieDiaria> historia,
                                           int horizonteDias,
                                           Hiperparametros p) {
        if (metodo == null) {
            throw new IllegalArgumentException("El metodo de pronostico no puede ser nulo");
        }
        if (horizonteDias <= 0) {
            return List.of();
        }

        Hiperparametros hiper = (p == null) ? Hiperparametros.defecto() : p;
        Rejilla rejilla = rejillaDiaria(historia);
        double[] datos = rejilla.valores;

        if (datos.length == 0) {
            LOG.warnf("Pronostico %s sin historia utilizable: se devuelve un horizonte de ceros (%d dias)",
                    metodo, horizonteDias);
            return ceros(horizonteDias);
        }

        List<Double> salida;
        switch (metodo) {
            case INGENUO -> salida = ingenuo(datos, horizonteDias);
            case ESTACIONAL_INGENUO -> salida = estacionalIngenuo(datos, rejilla, horizonteDias);
            case MEDIA_MOVIL -> salida = mediaMovil(datos, horizonteDias);
            case SES -> salida = ses(datos, horizonteDias, validarAlfa(hiper));
            case HOLT_AMORTIGUADO -> salida = holtAmortiguado(datos, horizonteDias, hiper);
            case HOLT_WINTERS -> salida = holtWinters(datos, horizonteDias, hiper);
            case SBA -> salida = sba(datos, horizonteDias, validarAlfa(hiper));
            default -> {
                // Inalcanzable mientras el switch cubra todos los valores del enum; se evita el
                // error de compilacion "falta sentencia de retorno" sin silenciar el caso.
                LOG.errorf("Metodo de pronostico no contemplado por el motor: %s", metodo);
                return ceros(horizonteDias);
            }
        }
        return Collections.unmodifiableList(salida);
    }

    /**
     * Mean Absolute Scaled Error.
     *
     * <p>{@code MASE = MAE(pronostico) / MAE(ingenuo dentro de la muestra)}. Un valor inferior a 1
     * significa que el modelo bate al ingenuo; 1 indica empate; superior a 1, que es peor que no
     * hacer nada. Es la metrica de eleccion para series con demanda intermitente, donde el RMSE
     * penaliza de forma distorsionada los picos.
     *
     * <p>Si el denominador es 0 (ingenuo perfecto en la muestra, tipicamente serie constante)
     * devuelve {@link Double#NaN}: es indefinido y la decision corresponde a quien llama
     * (normalmente se compara el MAE absoluto en ese caso).
     *
     * @param reales             valores reales
     * @param pronostico         valores pronosticados, alineados por indice
     * @param maeIngenuoInSample MAE del pronostico ingenuo dentro de la muestra
     * @return MASE, o {@link Double#NaN} si no hay pares comparables o el denominador es 0
     */
    public static double mase(List<Double> reales, List<Double> pronostico, double maeIngenuoInSample) {
        if (maeIngenuoInSample == 0.0 || Double.isNaN(maeIngenuoInSample)) {
            LOG.debug("MASE indefinido: el MAE del ingenuo en la muestra es 0");
            return Double.NaN;
        }
        double suma = 0.0;
        int n = 0;
        for (double[] par : paresAlineados(reales, pronostico)) {
            suma += Math.abs(par[0] - par[1]);
            n++;
        }
        if (n == 0) {
            LOG.debug("MASE indefinido: no hay pares real/pronostico comparables");
            return Double.NaN;
        }
        return (suma / n) / maeIngenuoInSample;
    }

    /**
     * Sesgo del pronostico escalado por el MAE del ingenuo en la muestra.
     *
     * <p>{@code sesgoEscalado = MEDIA(real - pronostico) / MAE(ingenuo en la muestra)}.
     *
     * <p>La convencion de signo es {@code real - pronostico}: un valor positivo indica
     * subpronostico (se pide de mas por falta de reposicion), un valor negativo indica
     * sobrepronostico (exceso de stock inmovilizado). Como el MASE, el valor 0 es el objetivo y
     * el intervalo (-1, 1) se considera calibrado; devuelve {@link Double#NaN} si el denominador
     * es 0 o no hay pares comparables.
     *
     * @param reales             valores reales
     * @param pronostico         valores pronosticados, alineados por indice
     * @param maeIngenuoInSample MAE del pronostico ingenuo dentro de la muestra
     * @return sesgo escalado, o {@link Double#NaN} si no es calculable
     */
    public static double sesgoEscalado(List<Double> reales, List<Double> pronostico, double maeIngenuoInSample) {
        if (maeIngenuoInSample == 0.0 || Double.isNaN(maeIngenuoInSample)) {
            LOG.debug("Sesgo escalado indefinido: el MAE del ingenuo en la muestra es 0");
            return Double.NaN;
        }
        double suma = 0.0;
        int n = 0;
        for (double[] par : paresAlineados(reales, pronostico)) {
            suma += par[0] - par[1];
            n++;
        }
        if (n == 0) {
            LOG.debug("Sesgo escalado indefinido: no hay pares real/pronostico comparables");
            return Double.NaN;
        }
        return (suma / n) / maeIngenuoInSample;
    }

    /**
     * Clasifica la serie en el cuadrante ADI / CV^2 de Syntetos-Boylan.
     *
     * <p>Trabaja sobre la rejilla diaria completa (dias ausentes = 0 entre la primera y la ultima
     * observacion):
     * <ul>
     *   <li>{@code ADI = periodos / periodos no nulos} — densidad de la demanda.</li>
     *   <li>{@code CV^2 = (desviacion / media)^2} de las magnitudes no nulas, con desviacion
     *       muestral (n - 1). Si hay menos de 2 magnitudes no nulas, {@code CV^2 = 0}.</li>
     * </ul>
     *
     * <p>Cuadrantes con cortes ADI = 1.32 y CV^2 = 0.49:
     * {@code ADI < 1.32 & CV^2 < 0.49 -> SUAVE},
     * {@code ADI < 1.32 & CV^2 >= 0.49 -> ERRATICO},
     * {@code ADI >= 1.32 & CV^2 < 0.49 -> INTERMITENTE},
     * resto {@code -> GRUMOSO}.
     *
     * <p>Historia vacia, nula o sin ninguna demanda no nula devuelve {@link Regimen#SIN_DATOS}.
     *
     * @param historia historia diaria
     * @return regimen de la serie
     */
    public static Regimen clasificar(List<SerieDiaria> historia) {
        double[] datos = rejillaDiaria(historia).valores;
        if (datos.length == 0) {
            return Regimen.SIN_DATOS;
        }

        int noNulos = contarNoNulos(datos);
        if (noNulos == 0) {
            return Regimen.SIN_DATOS;
        }

        double adi = (double) datos.length / noNulos;
        double cv2 = cvCuadrado(datos, noNulos);

        Regimen regimen;
        if (adi < CORTE_ADI) {
            regimen = cv2 < CORTE_CV2 ? Regimen.SUAVE : Regimen.ERRATICO;
        } else {
            regimen = cv2 < CORTE_CV2 ? Regimen.INTERMITENTE : Regimen.GRUMOSO;
        }
        LOG.debugf("Clasificacion de regimen: ADI=%.3f CV2=%.3f -> %s", adi, cv2, regimen);
        return regimen;
    }

    /**
     * Metodos candidatos a comparar para un regimen dado.
     *
     * <p>El llamador deberia ejecutar {@link #mase(List, List, double)} sobre cada candidato y
     * quedarse con el mejor (MASE menor), siempre que {@link #elegible(MetodoPronostico, List)} lo
     * permita.
     *
     * @param r regimen de la serie
     * @return lista inmutable de 1 a 4 metodos recomendados para ese regimen
     */
    public static List<MetodoPronostico> candidatos(Regimen r) {
        if (r == null) {
            LOG.warn("Regimen nulo en candidatos(): se devuelve INGENUO por defecto");
            return List.of(MetodoPronostico.INGENUO);
        }
        return switch (r) {
            case SUAVE -> List.of(MetodoPronostico.SES,
                    MetodoPronostico.HOLT_AMORTIGUADO,
                    MetodoPronostico.MEDIA_MOVIL);
            case ERRATICO -> List.of(MetodoPronostico.SES, MetodoPronostico.MEDIA_MOVIL);
            case INTERMITENTE -> List.of(MetodoPronostico.SBA,
                    MetodoPronostico.SES,
                    MetodoPronostico.ESTACIONAL_INGENUO);
            case GRUMOSO -> List.of(MetodoPronostico.SBA, MetodoPronostico.SES);
            case SIN_DATOS -> List.of(MetodoPronostico.INGENUO);
        };
    }

    /**
     * Indica si hay historia suficiente para ejecutar un metodo sin fallar o sin producir un
     * pronostico degenerado. El conteo se hace sobre la rejilla diaria completa, que es lo que
     * usan internamente los metodos.
     *
     * <ul>
     *   <li>{@link MetodoPronostico#HOLT_WINTERS}: &gt;= 28 observaciones.</li>
     *   <li>{@link MetodoPronostico#SBA}: &gt;= 3 demandas no nulas.</li>
     *   <li>{@link MetodoPronostico#HOLT_AMORTIGUADO}, {@link MetodoPronostico#SES},
     *       {@link MetodoPronostico#MEDIA_MOVIL}: &gt;= 2 observaciones.</li>
     *   <li>{@link MetodoPronostico#INGENUO}, {@link MetodoPronostico#ESTACIONAL_INGENUO}:
     *       &gt;= 1 observacion.</li>
     * </ul>
     *
     * @param metodo  metodo a evaluar
     * @param historia historia diaria
     * @return {@code true} si el metodo es ejecutable con esa historia
     */
    public static boolean elegible(MetodoPronostico metodo, List<SerieDiaria> historia) {
        if (metodo == null) {
            return false;
        }
        double[] datos = rejillaDiaria(historia).valores;
        return switch (metodo) {
            case INGENUO, ESTACIONAL_INGENUO -> datos.length >= 1;
            case SES, HOLT_AMORTIGUADO, MEDIA_MOVIL -> datos.length >= MIN_OBS_SUAVIZADO;
            case SBA -> contarNoNulos(datos) >= MIN_NO_NULOS_SBA;
            case HOLT_WINTERS -> datos.length >= MIN_OBS_HOLT_WINTERS;
        };
    }

    // ------------------------------------------------------------------
    // Metodos de pronostico
    // ------------------------------------------------------------------

    /** INGENUO: repite la ultima observacion. */
    private static List<Double> ingenuo(double[] datos, int horizonte) {
        double ultimo = datos[datos.length - 1];
        return repetir(ultimo, horizonte);
    }

    /**
     * ESTACIONAL_INGENUO: {@code F(t+h) = D(t+h-7)}. Si el indice cae antes del inicio de la
     * historia, se recurre a la ultima observacion disponible del mismo dia de semana; si tampoco
     * existe ese dia de semana, a la ultima observacion.
     */
    private static List<Double> estacionalIngenuo(double[] datos, Rejilla rejilla, int horizonte) {
        int n = datos.length;
        LocalDate ultimaFecha = rejilla.inicio.plusDays(n - 1L);

        Map<DayOfWeek, Double> porDiaSemana = new EnumMap<>(DayOfWeek.class);
        for (int i = 0; i < n; i++) {
            porDiaSemana.put(ultimaFecha.minusDays(n - 1L - i).getDayOfWeek(), datos[i]);
        }

        List<Double> salida = new ArrayList<>(horizonte);
        for (int h = 1; h <= horizonte; h++) {
            int indice = n - 1 + h - CICLO_SEMANAL;
            double valor;
            if (indice >= 0 && indice < n) {
                valor = datos[indice];
            } else {
                DayOfWeek dia = ultimaFecha.plusDays(h).getDayOfWeek();
                Double alternativo = porDiaSemana.get(dia);
                valor = (alternativo != null) ? alternativo : datos[n - 1];
            }
            salida.add(noNegativo(valor));
        }
        return salida;
    }

    /** MEDIA_MOVIL: media de las ultimas min(28, n) observaciones, plana. */
    private static List<Double> mediaMovil(double[] datos, int horizonte) {
        int ventana = Math.min(VENTANA_MEDIA_MOVIL, datos.length);
        double suma = 0.0;
        for (int i = datos.length - ventana; i < datos.length; i++) {
            suma += datos[i];
        }
        return repetir(suma / ventana, horizonte);
    }

    /**
     * SES: {@code F(t+1) = alfa * D(t) + (1 - alfa) * F(t)} con {@code F(1) = D(1)}.
     * El pronostico es plano (nivel sin tendencia).
     */
    private static List<Double> ses(double[] datos, int horizonte, double alfa) {
        double f = datos[0];
        for (int t = 1; t < datos.length; t++) {
            f = alfa * datos[t] + (1.0 - alfa) * f;
        }
        return repetir(f, horizonte);
    }

    /**
     * Holt con tendencia amortiguada:
     * <pre>
     * L(t) = alfa * D(t)      + (1 - alfa) * (L(t-1) + phi * T(t-1))
     * T(t) = beta * (L(t)-L(t-1)) + (1 - beta) * phi * T(t-1)
     * F(t+h) = L + (phi + phi^2 + ... + phi^h) * T
     * </pre>
     * Con inicializacion {@code L(1) = D(1)} y {@code T(1) = D(2) - D(1)} (0 si n &lt; 2).
     *
     * <p>El amortiguamiento es la clave anti-explosion: con {@code phi < 1} la suma de potencias
     * converge a {@code phi / (1 - phi)} (9 con el phi = 0.9 por defecto), de modo que un horizonte
     * de 30 dias no multiplica la pendiente inicial. La variante sin amortiguar
     * ({@code F = L + h * T}) es precisamente lo que producia pronosticos absurdos en
     * {@code StockForecastService}.
     */
    private static List<Double> holtAmortiguado(double[] datos, int horizonte, Hiperparametros hiper) {
        double alfa = validarAlfa(hiper);
        double beta = validar(hiper.beta(), 0.0, 1.0, Hiperparametros.defecto().beta(), "beta");
        double phi = validar(hiper.phi(), 0.0, 1.0, Hiperparametros.defecto().phi(), "phi");

        double nivel = datos[0];
        double tendencia = (datos.length >= 2) ? (datos[1] - datos[0]) : 0.0;

        for (int t = 1; t < datos.length; t++) {
            double nivelPrevio = nivel;
            nivel = alfa * datos[t] + (1.0 - alfa) * (nivel + phi * tendencia);
            tendencia = beta * (nivel - nivelPrevio) + (1.0 - beta) * phi * tendencia;
        }

        List<Double> salida = new ArrayList<>(horizonte);
        double potencia = 1.0;
        double sumaPotencias = 0.0;
        for (int h = 1; h <= horizonte; h++) {
            potencia *= phi;
            sumaPotencias += potencia;
            salida.add(noNegativo(nivel + sumaPotencias * tendencia));
        }
        return salida;
    }

    /**
     * Holt-Winters aditivo con estacionalidad semanal (m = 7).
     *
     * <p>Inicializacion con dos ciclos completos:
     * <pre>
     * L = media(ciclo 1);  T = (media(ciclo 2) - media(ciclo 1)) / m
     * S[j] = ((y[j]      - L(ciclo 1)) + (y[j + m] - L(ciclo 2))) / 2
     * </pre>
     * y a partir de {@code t = 2m}:
     * <pre>
     * L(t) = alfa * (D(t) - S(t-m))          + (1 - alfa) * (L(t-1) + T(t-1))
     * T(t) = beta * (L(t) - L(t-1))          + (1 - beta) * T(t-1)
     * S(t) = gamma * (D(t) - L(t))           + (1 - gamma) * S(t-m)
     * F(t+h) = L + h * T + S(t+h)
     * </pre>
     *
     * <p>Es el modelo aditivo estandar: no usa {@code phi} (la amortiguacion se aplica en
     * {@link MetodoPronostico#HOLT_AMORTIGUADO}, cuyo es el horizonte largo).
     *
     * @throws IllegalArgumentException si hay menos de 28 observaciones
     */
    private static List<Double> holtWinters(double[] datos, int horizonte, Hiperparametros hiper) {
        if (datos.length < MIN_OBS_HOLT_WINTERS) {
            throw new IllegalArgumentException(
                    "HOLT_WINTERS requiere al menos " + MIN_OBS_HOLT_WINTERS
                            + " observaciones y la historia aporta " + datos.length
                            + "; use elegible() antes de invocar");
        }
        double alfa = validarAlfa(hiper);
        double beta = validar(hiper.beta(), 0.0, 1.0, Hiperparametros.defecto().beta(), "beta");
        double gamma = validar(hiper.gamma(), 0.0, 1.0, Hiperparametros.defecto().gamma(), "gamma");

        int m = CICLO_SEMANAL;
        double mediaCiclo1 = media(datos, 0, m);
        double mediaCiclo2 = media(datos, m, 2 * m);
        double nivel = mediaCiclo1;
        double tendencia = (mediaCiclo2 - mediaCiclo1) / m;

        double[] estacional = new double[m];
        for (int j = 0; j < m; j++) {
            estacional[j] = ((datos[j] - mediaCiclo1) + (datos[j + m] - mediaCiclo2)) / 2.0;
        }

        for (int t = 2 * m; t < datos.length; t++) {
            int ranura = t % m;
            double estacionalPrevio = estacional[ranura];
            double nivelPrevio = nivel;
            nivel = alfa * (datos[t] - estacionalPrevio) + (1.0 - alfa) * (nivel + tendencia);
            tendencia = beta * (nivel - nivelPrevio) + (1.0 - beta) * tendencia;
            estacional[ranura] = gamma * (datos[t] - nivel) + (1.0 - gamma) * estacionalPrevio;
        }

        int ultimo = datos.length - 1;
        List<Double> salida = new ArrayList<>(horizonte);
        for (int h = 1; h <= horizonte; h++) {
            double valor = nivel + h * tendencia + estacional[(ultimo + h) % m];
            salida.add(noNegativo(valor));
        }
        return salida;
    }

    /**
     * SBA (Syntetic Bass / Croston simple ajustado) para demanda intermitente.
     *
     * <p>Se actualiza unicamente sobre las observaciones no nulas:
     * <pre>
     * Z(t) = alfa * D(t) + (1 - alfa) * Z(t-1)
     * P(t) = alfa * 1    + (1 - alfa) * P(t-1)      (intervalo entre clientes, en periodos)
     * F(t+h) = (1 - alfa/2) * Z / P                 (plano)
     * </pre>
     * con {@code Z(0)} = media de las magnitudes no nulas y {@code P(0)} = intervalo medio entre
     * eventos no nulos de la historia.
     */
    private static List<Double> sba(double[] datos, int horizonte, double alfa) {
        List<Integer> noNulos = indicesNoNulos(datos);
        if (noNulos.isEmpty()) {
            return ceros(horizonte);
        }

        double suma = 0.0;
        for (int i : noNulos) {
            suma += datos[i];
        }
        double z = suma / noNulos.size();
        double p = intervaloMedio(noNulos);

        for (int i : noNulos) {
            z = alfa * datos[i] + (1.0 - alfa) * z;
            p = alfa * 1.0 + (1.0 - alfa) * p;
        }
        if (p <= EPSILON_INTERVALO) {
            LOG.debugf("SBA: intervalo medio no positivo tras suavizar; se acota a %f", EPSILON_INTERVALO);
            p = EPSILON_INTERVALO;
        }
        return repetir((1.0 - alfa / 2.0) * z / p, horizonte);
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** Rejilla diaria regular: inicio mas valores (0 en los dias sin movimiento). */
    private record Rejilla(LocalDate inicio, double[] valores) {
    }

    /**
     * Colapsa la historia sobre una rejilla diaria continua entre la primera y la ultima
     * observacion: agrega fechas repetidas y completa con 0 los dias sin movimiento de venta.
     */
    private static Rejilla rejillaDiaria(List<SerieDiaria> historia) {
        if (historia == null || historia.isEmpty()) {
            return new Rejilla(null, new double[0]);
        }
        TreeMap<LocalDate, Long> acumulado = new TreeMap<>();
        int descartadas = 0;
        for (SerieDiaria punto : historia) {
            if (punto == null || punto.fecha() == null) {
                descartadas++;
                continue;
            }
            acumulado.merge(punto.fecha(), punto.cantidad(), Long::sum);
        }
        if (descartadas > 0) {
            LOG.warnf("Se descartaron %d puntos de historia sin fecha utilizable", descartadas);
        }
        if (acumulado.isEmpty()) {
            return new Rejilla(null, new double[0]);
        }

        LocalDate inicio = acumulado.firstKey();
        LocalDate fin = acumulado.lastKey();
        int periodos = (int) (ChronoUnit.DAYS.between(inicio, fin) + 1L);

        if (periodos > MAX_PERIODOS_REJILLA) {
            int descartados = periodos - MAX_PERIODOS_REJILLA;
            LOG.warnf("Historia de %d periodos diarios superior al tope de %d: se conservan los mas recientes",
                    periodos, MAX_PERIODOS_REJILLA);
            inicio = fin.minusDays(MAX_PERIODOS_REJILLA - 1L);
            periodos = MAX_PERIODOS_REJILLA;
            LOG.debugf("Descartados los %d periodos diarios mas antiguos", descartados);
        }

        double[] valores = new double[periodos];
        for (Map.Entry<LocalDate, Long> punto : acumulado.entrySet()) {
            long desplazamiento = ChronoUnit.DAYS.between(inicio, punto.getKey());
            if (desplazamiento >= 0 && desplazamiento < periodos) {
                valores[(int) desplazamiento] += punto.getValue();
            }
        }
        return new Rejilla(inicio, valores);
    }

    private static int contarNoNulos(double[] datos) {
        int total = 0;
        for (double v : datos) {
            if (v != 0.0) {
                total++;
            }
        }
        return total;
    }

    private static List<Integer> indicesNoNulos(double[] datos) {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < datos.length; i++) {
            if (datos[i] != 0.0) {
                indices.add(i);
            }
        }
        return indices;
    }

    /** CV^2 de Syntetos-Boylan sobre las magnitudes no nulas, con desviacion muestral. */
    private static double cvCuadrado(double[] datos, int noNulos) {
        if (noNulos < 2) {
            return 0.0;
        }
        double suma = 0.0;
        for (double v : datos) {
            if (v != 0.0) {
                suma += v;
            }
        }
        double media = suma / noNulos;

        double sumaCuadrados = 0.0;
        for (double v : datos) {
            if (v != 0.0) {
                double d = v - media;
                sumaCuadrados += d * d;
            }
        }
        double desviacion = Math.sqrt(sumaCuadrados / (noNulos - 1));

        if (media == 0.0) {
            // Solo alcanzable con devoluciones que cancelan exactamente las ventas; la dispersion
            // relativa no tiene sentido y se trata como serie suave.
            LOG.debug("CV^2 no definido (media de magnitudes no nulas igual a 0); se toma 0");
            return 0.0;
        }
        double cociente = desviacion / media;
        return cociente * cociente;
    }

    /** Intervalo medio, en periodos, entre eventos no nulos consecutivos. */
    private static double intervaloMedio(List<Integer> indicesNoNulos) {
        if (indicesNoNulos.size() < 2) {
            return 1.0;
        }
        int primero = indicesNoNulos.get(0);
        int ultimo = indicesNoNulos.get(indicesNoNulos.size() - 1);
        return (double) (ultimo - primero) / (indicesNoNulos.size() - 1);
    }

    private static double media(double[] datos, int desde, int hasta) {
        double suma = 0.0;
        for (int i = desde; i < hasta; i++) {
            suma += datos[i];
        }
        return suma / (hasta - desde);
    }

    private static List<Double> repetir(double valor, int horizonte) {
        double acotado = noNegativo(valor);
        List<Double> salida = new ArrayList<>(horizonte);
        for (int h = 0; h < horizonte; h++) {
            salida.add(acotado);
        }
        return salida;
    }

    private static List<Double> ceros(int horizonte) {
        List<Double> salida = new ArrayList<>(horizonte);
        for (int h = 0; h < horizonte; h++) {
            salida.add(0.0);
        }
        return salida;
    }

    /** Acota a 0: la demanda no puede ser negativa. */
    private static double noNegativo(double valor) {
        return (Double.isNaN(valor) || valor < 0.0) ? 0.0 : valor;
    }

    private static double validarAlfa(Hiperparametros hiper) {
        return validar(hiper.alfa(), 0.0, 1.0, Hiperparametros.defecto().alfa(), "alfa");
    }

    /** Sustituye por el valor por defecto del dominio cualquier tasa fuera de rango o NaN. */
    private static double validar(double valor, double min, double max, double porDefecto, String nombre) {
        if (Double.isNaN(valor) || valor < min || valor > max) {
            LOG.warnf("Hiperparametro '%s' fuera de rango [%s, %s] (valor=%s): se usa el valor por defecto %s",
                    nombre, min, max, valor, porDefecto);
            return porDefecto;
        }
        return valor;
    }

    /**
     * Emite los pares alineados {@code [real, pronostico]} del prefijo comun, descartando valores
     * nulos o no finitos (un pronostico invalido no debe contaminar la metrica).
     */
    private static List<double[]> paresAlineados(List<Double> reales, List<Double> pronostico) {
        List<double[]> pares = new ArrayList<>();
        if (reales == null || pronostico == null) {
            return pares;
        }
        int comunes = Math.min(reales.size(), pronostico.size());
        for (int i = 0; i < comunes; i++) {
            Double real = reales.get(i);
            Double prev = pronostico.get(i);
            if (real == null || prev == null || real.isNaN() || real.isInfinite()
                    || prev.isNaN() || prev.isInfinite()) {
                continue;
            }
            pares.add(new double[]{real, prev});
        }
        return pares;
    }
}
