package Services;

import Services.pronostico.CalculadoraPronostico;
import Services.pronostico.FlagCalidad;
import Services.pronostico.Hiperparametros;
import Services.pronostico.MetodoPronostico;
import Services.pronostico.MotorPronostico;
import Services.pronostico.Regimen;
import Services.pronostico.SerieDiaria;
import Models.Articulos.Articulos;
import Models.PrecisionPronostico;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Status;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import jakarta.transaction.UserTransaction;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Seleccion del metodo de pronostico por articulo (backtesting con origenes
 * moviles) y persistencia del resultado en {@link PrecisionPronostico}.
 *
 * <p>Es la capa de <b>persistencia y decision</b>; el nucleo matematico vive en
 * {@link MotorPronostico}, que es puro (sin CDI ni base de datos). Este servicio
 * solo aporta lo que el motor no puede saber:</p>
 * <ul>
 *   <li>traducir movimientos de {@code Inventario} ({@code tipoMovimiento = 'Venta'}) a una
 *       {@link SerieDiaria} diaria, con la misma agregacion por articulo y dia que usa
 *       {@link StockForecastService} pero sobre el inventario (la facturacion no tiene el
 *       detalle por articulo de forma fiable);</li>
 *   <li>recorrer <em>rolling origins</em> —entrenar en el prefijo y comparar el horizonte con
 *       lo que realmente ocurrio— para medir precision en vez de supuesto;</li>
 *   <li>decidir el ganador por horizonte y dejar una fila por metodo candidato
 *       para que el desempate sea auditable.</li>
 * </ul>
 *
 * <p><b>Metrica.</b> Se usa el MASE de {@link MotorPronostico#mase}: error absoluto medio del
 * metodo dividido por el MAE del pronostico ingenuo <em>dentro de la muestra de
 * entrenamiento</em> de cada origen. Con varios origenes la escala se promedia (las ventanas de
 * entrenamiento son prefijos anidados, de modo que la media de sus MAEs ingenuos es un estimador
 * estable) y los pares real/pronostico de todos los origenes se acumulan en una sola llamada, que
 * es equivalente a la media por origen. El sesgo se obtiene igual con
 * {@link MotorPronostico#sesgoEscalado}. Cuando la escala es cero (serie plana) ambas metricas
 * son indefinidas: se guarda {@code null}, nunca {@code NaN} —PostgreSQL lo representa como el
 * texto {@code 'NaN'} y contaminaria el {@code ORDER BY} de la seleccion—.</p>
 *
 * <p><b>Por que origins en domingo.</b> El domingo es el cierre natural de la semana operativa
 * crista y, sobre todo, el unico dia en que el horizonte de 7 dias cae entero dentro de la
 * misma semana: evita que una corrida mida el error de un cambio de patron a mitad de semana. Si
 * la historia es tan corta que no entra ningun domingo con horizonte completo, se recurre a los
 * ultimos indices utilizables.</p>
 *
 * <p><b>Alcance de la reevaluacion.</b> {@link #reelegirTodo()} abre una transaccion por
 * articulo, no una para todo el catalogo: un articulo problematico no puede tirar abajo la
 * seleccion de los demas. El coste (una consulta de movimientos y hasta 7 pronosticos por origen,
 * por cada uno de los horizontes 7/14/30/60) es aceptable mientras el catalogo con rotacion sea
 * manejable; si creciera, el siguiente paso es laminar la lista de articulos candidatos por un
 * filtro de rotacion.</p>
 *
 * @author Mercurius
 */
@ApplicationScoped
@Named("seleccionMetodoService")
public class SeleccionMetodoService {

    private static final Logger LOG = Logger.getLogger(SeleccionMetodoService.class);

    /**
     * Ventana de historia considerada, en dias. {@code 0} = historia completa
     * (desde el primer movimiento del articulo): con anos de ventas importadas
     * el backtesting y la seleccion de metodo usan todo lo disponible en vez
     * de solo los ultimos meses. HOLT_WINTERS exige 28 observaciones; el tope
     * real lo pone {@code MAX_PERIODOS_REJILLA} del motor (~10 anos).
     */
    public static final int DIAS_HISTORIA = 0;

    /**
     * Orden de simplicidad para desempatar dos metodos con el mismo MASE: gana el mas simple.
     *
     * <p>No es el orden del enum: se busca del metodo mas trivial al mas parametrizado, de modo
     * que ante empate se prefiera el que exige menos historia y menos hipers. Los metodos que no
     * figuren aqui (un enum nuevo, por ejemplo) se ordenan al final y entre si por nombre, para
     * que la seleccion siga siendo determinista.</p>
     */
    private static final List<String> ORDEN_SIMPLICIDAD = List.of(
            "INGENUO",
            "ESTACIONAL_INGENUO",
            "MEDIA_MOVIL",
            "SES",
            "SBA",
            "HOLT_AMORTIGUADO",
            "HOLT_WINTERS");

    /**
     * Horizontes que se evaluan y persisten por separado. Un ganador medido a
     * 14 dias no se reutiliza a 60: el horizonte cambia el error del modelo y
     * la planificacion de inventario tiene que ver el ganador que corresponde.
     */
    public static final List<Integer> HORIZONTES_SOPORTADOS = List.of(7, 14, 30, 60);

    /**
     * Lineas base obligatorias. Un modelo complejo solo se elige si mejora a
     * la mejor de estas por al menos {@link #UMBRAL_MEJORA_BASELINE}.
     */
    private static final List<MetodoPronostico> BASELINES = List.of(
            MetodoPronostico.INGENUO,
            MetodoPronostico.ESTACIONAL_INGENUO,
            MetodoPronostico.MEDIA_MOVIL);

    /** Mejora minima (5 %) sobre la mejor linea base para preferir un modelo complejo. */
    private static final double UMBRAL_MEJORA_BASELINE = 0.05;

    /** Si el ganador y el segundo quedan a menos de este margen, la eleccion es inestable. */
    private static final double UMBRAL_ESTABILIDAD = 0.02;

    /** Version del pipeline de seleccion, persistida en cada fila. */
    public static final String MODELO_VERSION = CalculadoraPronostico.MODELO_VERSION;

    /** Horizonte por defecto de la reevaluacion semanal, en dias. */
    @ConfigProperty(name = "mercurius.pronostico.horizonte-dias", defaultValue = "14")
    int horizontePorDefecto;

    /**
     * Numero de origenes moviles por defecto. Ocho es el minimo con el que el
     * MASE deja de moverse solo por el ruido de un origen; con historia
     * suficiente se usan mas.
     */
    @ConfigProperty(name = "mercurius.pronostico.origenes", defaultValue = "8")
    int origenesPorDefecto;

    @Inject
    EntityManager entityManager;

    @Inject
    UserTransaction userTransaction;

    // ------------------------------------------------------------------
    // Evaluacion
    // ------------------------------------------------------------------

    /**
     * Reevalua un articulo para un horizonte: mide cada candidato del regimen
     * mas las tres lineas base obligatorias con origenes moviles y reemplaza
     * las filas de precision de ese (articulo, horizonte).
     *
     * <p><b>Politica de seleccion.</b> Un modelo complejo solo gana si mejora
     * a la mejor linea base (ingenuo, estacional ingenuo o media movil) por al
     * menos un 5 %. Si no, se queda la linea base. Ante una diferencia menor al
     * 2 % entre el ganador y el segundo se marca {@link FlagCalidad#MODEL_UNSTABLE}
     * y se prefiere el mas simple.</p>
     *
     * <p>El borrado es por horizonte, no total: evaluar 14 dias no borra la
     * seleccion de 60. Repetir la misma corrida sigue siendo idempotente.</p>
     *
     * @param codigoArticulo articulo a evaluar
     * @param horizonteDias  dias hacia adelante comparados en cada origen
     * @param origenes       cuantos origenes moviles (domingos) se toman, los mas recientes
     * @return numero de filas de precision guardadas (0 si no habria datos suficientes)
     */
    @Transactional
    public int evaluarArticulo(@Nonnull Long codigoArticulo, int horizonteDias, int origenes) {
        int horizonte = Math.max(1, horizonteDias);
        int origenesPedidos = Math.max(1, origenes);

        Articulos articulo = entityManager.find(Articulos.class, codigoArticulo);
        if (articulo == null) {
            LOG.warn("evaluarArticulo: el articulo " + codigoArticulo
                    + " no existe; se conservan las filas de precision previas porque no hay nada que recalcular");
            return 0;
        }

        List<SerieDiaria> serie = serieDiaria(codigoArticulo, DIAS_HISTORIA);
        if (serie.size() < 2) {
            LOG.warn("evaluarArticulo: el articulo " + codigoArticulo + " tiene "
                    + serie.size() + " dia(s) de historia; se borran sus filas de precision por no ser evaluable");
            borrarPrecision(codigoArticulo, horizonte);
            return 0;
        }

        List<Integer> indicesOrigen = origenesMoviles(serie, horizonte, origenesPedidos);
        if (indicesOrigen.isEmpty()) {
            LOG.warn("evaluarArticulo: el articulo " + codigoArticulo + " tiene " + serie.size()
                    + " dia(s) de historia y no admite ningun origen con horizonte de " + horizonte
                    + " dias; se borran sus filas de precision por no ser evaluable");
            borrarPrecision(codigoArticulo, horizonte);
            return 0;
        }

        Regimen regimen = MotorPronostico.clasificar(serie);
        List<MetodoPronostico> candidatos = candidatosConBaselines(regimen);

        Map<MetodoPronostico, Acumulador> metricas = new EnumMap<>(MetodoPronostico.class);
        for (MetodoPronostico metodo : candidatos) {
            metricas.put(metodo, new Acumulador());
        }

        for (int indice : indicesOrigen) {            // El entrenamiento cierra en el propio dia del origen; el horizonte son los
            // horizonte dias siguientes, que son los que se comparan con lo real.
            List<SerieDiaria> entrenamiento = List.copyOf(serie.subList(0, indice + 1));
            List<Double> reales = new ArrayList<>(horizonte);
            for (int h = 1; h <= horizonte; h++) {
                reales.add((double) serie.get(indice + h).cantidad());
            }

            double escalaIngenua = maeIngenuoEnMuestra(entrenamiento);

            for (MetodoPronostico metodo : candidatos) {
                if (!MotorPronostico.elegible(metodo, entrenamiento)) {
                    continue;
                }
                List<Double> pronostico = MotorPronostico.pronosticar(
                        metodo, entrenamiento, horizonte, Hiperparametros.defecto());
                if (pronostico == null || pronostico.size() < horizonte) {
                    // Pronostico truncado: el origen no aporta para este metodo. Se omite
                    // porque elegible() ya cubre los minimos de historia conocidos; con un
                    // metodo elegible el motor devuelve exactamente `horizonte` valores.
                    continue;
                }
                metricas.get(metodo).registrar(reales, pronostico, escalaIngenua);
            }
        }

        borrarPrecision(codigoArticulo, horizonte);

        List<FilaEvaluada> evaluadas = new ArrayList<>();
        for (Map.Entry<MetodoPronostico, Acumulador> entrada : metricas.entrySet()) {
            Acumulador acumulado = entrada.getValue();
            if (!acumulado.hayObservaciones()) {
                continue;
            }
            evaluadas.add(new FilaEvaluada(entrada.getKey(), acumulado));
        }
        Seleccion seleccion = elegirGanador(evaluadas);

        LocalDateTime ahora = LocalDateTime.now();
        java.time.LocalDate finHistoria = serie.get(serie.size() - 1).fecha();
        int guardadas = 0;
        for (FilaEvaluada evaluada : evaluadas) {
            PrecisionPronostico fila = new PrecisionPronostico();
            fila.setArticulo(articulo);
            fila.setMetodo(evaluada.metodo().name());
            fila.setMase(evaluada.acumulador().mase());
            fila.setSesgo(evaluada.acumulador().sesgo());
            fila.setMae(evaluada.acumulador().mae());
            fila.setHorizonteDias(horizonte);
            fila.setOrigenes(indicesOrigen.size());
            fila.setObservaciones(evaluada.acumulador().observaciones());
            fila.setFechaFinHistoria(finHistoria);
            fila.setModeloVersion(MODELO_VERSION);
            fila.setSeleccionado(evaluada.metodo() == seleccion.ganador());
            fila.setMejoraBaseline(evaluada.metodo() == seleccion.ganador()
                    ? seleccion.mejoraBaseline() : null);
            fila.setMetodoBaseline(seleccion.metodoBaseline() == null
                    ? null : seleccion.metodoBaseline().name());
            fila.setCalidadFlags(seleccion.inestable() ? FlagCalidad.MODEL_UNSTABLE.name() : null);
            fila.setFechaCalculo(ahora);
            entityManager.persist(fila);
            guardadas++;
        }

        LOG.infof("evaluarArticulo: articulo=%d regimen=%s horizonte=%d origenes=%d "
                        + "candidatos=%d filas=%d ganador=%s mase=%s baseline=%s mejora=%s inestable=%s",
                codigoArticulo, regimen, horizonte, indicesOrigen.size(), candidatos.size(),
                guardadas, seleccion.ganador(), seleccion.mase(),
                seleccion.metodoBaseline(), seleccion.mejoraBaseline(), seleccion.inestable());
        return guardadas;
    }

    /**
     * Reevalua un articulo en todos los horizontes soportados. Cada horizonte
     * conserva su propio ganador; el coste es proporcional al numero de
     * horizontes y por eso la corrida semanal lo usa en lugar de reutilizar el
     * ganador de 14 dias.
     *
     * @return total de filas guardadas en todos los horizontes
     */
    @Transactional
    public int evaluarTodosHorizontes(@Nonnull Long codigoArticulo, int origenes) {
        int total = 0;
        for (int horizonte : HORIZONTES_SOPORTADOS) {
            total += evaluarArticulo(codigoArticulo, horizonte, origenes);
        }
        return total;
    }

    /** Candidatos del regimen mas las tres lineas base, sin duplicados y con las bases primero. */
    private static List<MetodoPronostico> candidatosConBaselines(Regimen regimen) {
        List<MetodoPronostico> lista = new ArrayList<>(BASELINES);
        for (MetodoPronostico metodo : MotorPronostico.candidatos(regimen)) {
            if (!lista.contains(metodo)) {
                lista.add(metodo);
            }
        }
        return List.copyOf(lista);
    }

    // ------------------------------------------------------------------
    // Seleccion
    // ------------------------------------------------------------------

    /** Par metodo/acumulador de una corrida. */
    private record FilaEvaluada(MetodoPronostico metodo, Acumulador acumulador) {
    }

    /** Resultado de aplicar la politica de seleccion a un conjunto de filas. */
    private record Seleccion(@Nonnull MetodoPronostico ganador,
                             @Nullable Double mase,
                             @Nullable MetodoPronostico metodoBaseline,
                             @Nullable Double mejoraBaseline,
                             boolean inestable) {
    }

    /**
     * Aplica la politica: linea base salvo mejora demostrada, y el metodo mas
     * simple ante empate. Devuelve tambien la mejora sobre la base y si el
     * resultado es inestable.
     */
    private static Seleccion elegirGanador(List<FilaEvaluada> filas) {
        if (filas.isEmpty()) {
            // No deberia ocurrir: el llamador solo invoca con filas acumuladas.
            return new Seleccion(MetodoPronostico.INGENUO, null, null, null, false);
        }

        List<FilaEvaluada> conMase = filas.stream()
                .filter(fila -> fila.acumulador().mase() != null)
                .sorted(Comparator
                        .comparingDouble((FilaEvaluada fila) -> fila.acumulador().mase())
                        .thenComparingInt(fila -> posicionSimplicidad(fila.metodo().name())))
                .toList();

        if (conMase.isEmpty()) {
            FilaEvaluada simple = filas.stream()
                    .min(Comparator.comparingInt(fila -> posicionSimplicidad(fila.metodo().name())))
                    .orElse(filas.get(0));
            return new Seleccion(simple.metodo(), null, null, null, false);
        }

        FilaEvaluada mejorBase = conMase.stream()
                .filter(fila -> BASELINES.contains(fila.metodo()))
                .findFirst()
                .orElse(null);
        FilaEvaluada mejorGlobal = conMase.get(0);

        FilaEvaluada ganador;
        if (mejorBase == null) {
            ganador = mejorGlobal;
        } else if (mejorGlobal == mejorBase || BASELINES.contains(mejorGlobal.metodo())) {
            ganador = mejorBase;
        } else {
            double maseBase = mejorBase.acumulador().mase();
            double maseComplejo = mejorGlobal.acumulador().mase();
            boolean mejoraSuficiente = maseBase > 0.0
                    && maseComplejo <= maseBase * (1.0 - UMBRAL_MEJORA_BASELINE);
            ganador = mejoraSuficiente ? mejorGlobal : mejorBase;
        }

        Double maseGanador = ganador.acumulador().mase();
        Double mejora = null;
        MetodoPronostico nombreBase = null;
        if (mejorBase != null) {
            nombreBase = mejorBase.metodo();
            double maseBase = mejorBase.acumulador().mase();
            if (maseBase > 0.0 && maseGanador != null) {
                mejora = (maseBase - maseGanador) / maseBase;
            }
        }

        boolean inestable = false;
        if (conMase.size() >= 2) {
            FilaEvaluada segundo = conMase.get(1);
            double diferencia = Math.abs(segundo.acumulador().mase() - maseGanador);
            double escala = Math.max(Math.abs(maseGanador), 1e-9);
            inestable = diferencia / escala <= UMBRAL_ESTABILIDAD;
        }

        return new Seleccion(ganador.metodo(), maseGanador, nombreBase, mejora, inestable);
    }

    /**
     * Metodo con mejor MASE de los almacenados para el articulo en el horizonte
     * por defecto. Se conserva por compatibilidad; la planificacion real debe
     * pedir el horizonte que va a usar con {@link #metodoElegido(Long, int)}.
     */
    @Transactional(TxType.SUPPORTS)
    public Optional<String> metodoElegido(@Nonnull Long codigoArticulo) {
        return metodoElegido(codigoArticulo, Math.max(1, horizontePorDefecto));
    }

    /**
     * Metodo ganador para un (articulo, horizonte). Solo se consideran filas
     * del horizonte exacto: reutilizar el ganador de 14 dias para 60 seria
     * exactamente el error que esta capa tiene que evitar.
     */
    @Transactional(TxType.SUPPORTS)
    public Optional<String> metodoElegido(@Nonnull Long codigoArticulo, int horizonteDias) {
        List<PrecisionPronostico> filas = entityManager.createQuery(
                        "SELECT p FROM PrecisionPronostico p "
                                + "WHERE p.articulo.codigo = :codigo AND p.horizonteDias = :horizonte",
                        PrecisionPronostico.class)
                .setParameter("codigo", codigoArticulo)
                .setParameter("horizonte", Math.max(1, horizonteDias))
                .getResultList();

        return mejorFila(filas, horizonteDias).map(PrecisionPronostico::getMetodo);
    }

    /** Fila ganadora de un articulo y horizonte, o vacio si no hay evaluacion. */
    @Transactional(TxType.SUPPORTS)
    public Optional<PrecisionPronostico> seleccionPara(@Nonnull Long codigoArticulo, int horizonteDias) {
        List<PrecisionPronostico> filas = entityManager.createQuery(
                        "SELECT p FROM PrecisionPronostico p "
                                + "WHERE p.articulo.codigo = :codigo AND p.horizonteDias = :horizonte",
                        PrecisionPronostico.class)
                .setParameter("codigo", codigoArticulo)
                .setParameter("horizonte", Math.max(1, horizonteDias))
                .getResultList();
        return mejorFila(filas, horizonteDias);
    }

    /**
     * Selecciones ganadoras de todo el catalogo para un horizonte, en UNA
     * consulta. Se usa en el reporte masivo: preguntar articulo por articulo
     * convertiria una pagina en 250.000 consultas.
     */
    @Transactional(TxType.SUPPORTS)
    @Nonnull
    public Map<Long, PrecisionPronostico> selecciones(int horizonteDias) {
        int horizonte = Math.max(1, horizonteDias);
        List<PrecisionPronostico> filas = entityManager.createQuery(
                        "SELECT p FROM PrecisionPronostico p "
                                + "WHERE p.horizonteDias = :horizonte "
                                + "AND (p.seleccionado = true OR p.seleccionado IS NULL)",
                        PrecisionPronostico.class)
                .setParameter("horizonte", horizonte)
                .getResultList();

        Map<Long, List<PrecisionPronostico>> porArticulo = new java.util.HashMap<>();
        for (PrecisionPronostico fila : filas) {
            if (fila.getArticulo() != null && fila.getArticulo().getCodigo() != null) {
                porArticulo.computeIfAbsent(fila.getArticulo().getCodigo(),
                        clave -> new ArrayList<>()).add(fila);
            }
        }
        Map<Long, PrecisionPronostico> resultado = new java.util.HashMap<>();
        for (Map.Entry<Long, List<PrecisionPronostico>> entrada : porArticulo.entrySet()) {
            mejorFila(entrada.getValue(), horizonte)
                    .ifPresent(fila -> resultado.put(entrada.getKey(), fila));
        }
        return Map.copyOf(resultado);
    }

    /**
     * Fila ganadora entre las precisiones ya cargadas de un artículo: la
     * marcada como {@code seleccionado} por la politica; si no hay marca
     * (filas antiguas o sembradas a mano), menor MASE con desempate por
     * simplicidad.
     *
     * <p>Existe para que una vista que ya trajo todas las filas de varios
     * artículos en una sola consulta pueda elegir sin volver a preguntar por
     * cada uno. La regla de desempate vive aquí y no en el llamador: si cambia,
     * la página y el servicio cambian juntos.</p>
     *
     * @param filas filas de precisión de UN artículo (pueden incluir MASE nulo)
     * @return la fila elegida, o vacío si ninguna tiene MASE definido
     */
    @Nonnull
    public static Optional<PrecisionPronostico> mejorFila(
            @Nullable List<PrecisionPronostico> filas) {
        return mejorFila(filas, 0);
    }

    /**
     * Igual que {@link #mejorFila(List)} pero restringida a un horizonte.
     * {@code horizonte <= 0} significa "cualquiera" (compatibilidad).
     */
    @Nonnull
    public static Optional<PrecisionPronostico> mejorFila(
            @Nullable List<PrecisionPronostico> filas, int horizonte) {
        if (filas == null || filas.isEmpty()) {
            return Optional.empty();
        }
        List<PrecisionPronostico> delHorizonte = horizonte <= 0 ? filas : filas.stream()
                .filter(fila -> fila.getHorizonteDias() == horizonte)
                .toList();
        if (delHorizonte.isEmpty()) {
            return Optional.empty();
        }
        Optional<PrecisionPronostico> marcada = delHorizonte.stream()
                .filter(fila -> Boolean.TRUE.equals(fila.getSeleccionado()))
                .findFirst();
        if (marcada.isPresent()) {
            return marcada;
        }
        return delHorizonte.stream()
                .filter(fila -> fila.getMase() != null)
                .min(Comparator.comparingDouble(PrecisionPronostico::getMase)
                        .thenComparingInt(fila -> posicionSimplicidad(fila.getMetodo()))
                        .thenComparing(PrecisionPronostico::getMetodo));
    }

    /**
     * Reevalua todos los articulos activos en todos los horizontes soportados
     * con el numero de origenes configurado.
     *
     * <p>Cada articulo se procesa en su propia transaccion y los errores se registran y se
     * saltan: un articulo con datos raros no puede dejar la seleccion del resto del catalogo a
     * medias. Los articulos sin ningun movimiento de venta se saltan con una unica consulta
     * EXISTS (no se recorre su historia) y se les limpian las filas de precision, porque un
     * MASE guardado sobre una historia que ya no existe seria peor que no tener ninguno.</p>
     *
     * @return numero de articulos que quedaron con al menos una fila de precision
     */
    public int reelegirTodo() {
        int origenesPedidos = Math.max(1, origenesPorDefecto);

        List<Long> codigos = enTransaccion(() -> entityManager.createQuery(
                        "SELECT a.codigo FROM Articulos a WHERE a.status = true ORDER BY a.codigo",
                        Long.class)
                .getResultList());

        int reevaluados = 0;
        for (Long codigo : codigos) {
            try {
                int filas = enTransaccion(() -> {
                    if (!tieneVentas(codigo)) {
                        borrarPrecision(codigo);
                        return 0;
                    }
                    // Auto-invocacion: el interceptor de @Transactional no se aplica y la
                    // transaccion abierta por enTransaccion() es la que gobierna la unidad.
                    return evaluarTodosHorizontes(codigo, origenesPedidos);
                });
                if (filas > 0) {
                    reevaluados++;
                }
            } catch (RuntimeException e) {
                LOG.warn("reelegirTodo: el articulo " + codigo
                        + " no pudo reevaluarse (" + e.getMessage() + "); se continua con el resto del catalogo", e);
            }
        }

        LOG.infof("reelegirTodo: %d de %d articulos activos quedaron con metodo elegido "
                        + "(horizontes=%s, origenes=%d)",
                reevaluados, codigos.size(), HORIZONTES_SOPORTADOS, origenesPedidos);
        return reevaluados;
    }

    /**
     * Tick semanal de reevaluacion (domingo 03:00). Notese el {@code ?} en el dia del mes:
     * cron-utils —el parser que usa Quarkus— rechaza tanto el campo {@code ?} final de Quartz
     * ({@code "0 0 3 * * SUN ?"}: "Invalid expression: ?") como el par dia-del-mes + dia-de-la-
     * semana sin {@code ?} ({@code "0 0 3 * * SUN"}: "Both, a day-of-week AND a day-of-month
     * parameter, are not supported"). {@code "0 0 3 ? * SUN"} es la forma equivalente que
     * ambos parsers aceptan y significa todos los domingos a las 03:00.
     *
     * <p>Metodo minimo a proposito: toda la logica vive en {@link #reelegirTodo()}. Bajo
     * {@code %test} el planificador no corre ({@code %test.quarkus.scheduler.enabled=false},
     * ver el bloque de notas de application.properties) para que ningun job escriba filas
     * mientras los tests asertan sobre el contenido de las tablas; las pruebas que necesiten
     * el comportamiento llaman a {@code reelegirTodo()} directamente.</p>
     */
    @Scheduled(cron = "0 0 3 ? * SUN")
    void reevaluacionSemanal() {
        int reevaluados = reelegirTodo();
        LOG.info("Reevaluacion semanal de pronosticos: " + reevaluados + " articulo(s) con metodo elegido");
    }

    // ------------------------------------------------------------------
    // Serie diaria
    // ------------------------------------------------------------------

    /**
     * Serie diaria de unidades vendidas de un articulo en los ultimos {@code dias} dias.
     *
     * <p>Agrega los movimientos {@code tipoMovimiento = 'Venta'} por dia y completa la rejilla
     * diaria con 0 entre la primera y la ultima observacion, que es la convencion de
     * {@link SerieDiaria}: los dias sin venta son informacion (marcan el cero de la demanda), no
     * huecos. Los movimientos de venta se guardan con {@code cantidad} negativa
     * ({@code CarritoService} descuenta stock), de modo que se toma el valor absoluto.</p>
     *
     * <p>El filtro de estado admite {@code status IS NULL} ademas de {@code status = true}: la
     * columna es nullable y los movimientos anteriores a su introduccion quedaron con NULL, asi
     * que un {@code = true} a secas descartaria historia real de articulos antiguos.</p>
     *
     * @param codigoArticulo articulo consultado
     * @param dias           ventana retrospectiva en dias; {@code <= 0} = historia
     *                       completa (desde el primer movimiento del articulo)
     * @return serie diaria ordenada, o lista vacia si no hay ventas en la ventana
     */
    /**
     * Primer dia con movimiento 'Venta' del articulo. Sostiene la ventana
     * ilimitada ({@code dias <= 0}): sin historia, hoy (la serie saldra vacia
     * igual que antes).
     */
    private LocalDate primerMovimiento(@Nonnull Long codigoArticulo) {
        try {
            Date min = entityManager.createQuery(
                            "SELECT MIN(i.fechaMovimiento) FROM Inventario i "
                                    + "WHERE i.articulo.codigo = :codigo "
                                    + "AND i.tipoMovimiento = 'Venta' "
                                    + "AND (i.status IS NULL OR i.status = true)",
                            Date.class)
                    .setParameter("codigo", codigoArticulo)
                    .getSingleResult();
            if (min != null) {
                return min.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            }
        } catch (RuntimeException e) {
            LOG.errorf("sin fecha minima para %d; no se degrada a hoy", codigoArticulo);
            throw e;
        }
        return LocalDate.now();
    }

    @Transactional(TxType.SUPPORTS)
    public List<SerieDiaria> serieDiaria(@Nonnull Long codigoArticulo, int dias) {
        LocalDate hoy = LocalDate.now();
        LocalDate inicio = dias <= 0 ? primerMovimiento(codigoArticulo) : hoy.minusDays(Math.max(1, dias));
        ZoneId zona = ZoneId.systemDefault();
        Date desde = Date.from(inicio.atStartOfDay(zona).toInstant());
        Date hasta = Date.from(hoy.plusDays(1).atStartOfDay(zona).toInstant().minusMillis(1));

        List<Object[]> movimientos = entityManager.createQuery(
                        "SELECT i.fechaMovimiento, i.cantidad FROM Inventario i "
                                + "WHERE i.articulo.codigo = :codigo "
                                + "AND i.tipoMovimiento = 'Venta' "
                                + "AND (i.status IS NULL OR i.status = true) "
                                + "AND i.fechaMovimiento BETWEEN :desde AND :hasta "
                                + "ORDER BY i.fechaMovimiento ASC",
                        Object[].class)
                .setParameter("codigo", codigoArticulo)
                .setParameter("desde", desde)
                .setParameter("hasta", hasta)
                .getResultList();

        TreeMap<LocalDate, Double> porDia = new TreeMap<>();
        for (Object[] movimiento : movimientos) {
            LocalDate dia = aFecha(movimiento[0]);
            if (dia == null || dia.isBefore(inicio) || dia.isAfter(hoy)) {
                continue;
            }
            double unidades = Math.abs(((Number) movimiento[1]).doubleValue());
            porDia.merge(dia, unidades, Double::sum);
        }

        if (porDia.isEmpty()) {
            return List.of();
        }

        List<SerieDiaria> serie = new ArrayList<>(porDia.size());
        for (LocalDate dia = porDia.firstKey(); !dia.isAfter(hoy); dia = dia.plusDays(1)) {
            serie.add(new SerieDiaria(dia, porDia.getOrDefault(dia, 0.0)));
        }
        return serie;
    }

    /**
     * Series diarias de TODO el catalogo activo en una sola consulta, con la
     * misma definicion de demanda que {@link #serieDiaria(Long, int)}: solo
     * movimientos {@code 'Venta'} enlazados por la clave del articulo.
     *
     * <p>La rejilla empieza en el primer dia con venta dentro de la ventana y
     * termina hoy; los dias intermedios sin movimiento valen 0. Arrancar en el
     * primer dia con venta —y no en el inicio de la ventana— evita castigar a
     * un articulo nuevo con ceros de antes de existir, sin dejar de usar dias
     * naturales como denominador.</p>
     *
     * <p>Es la unica lectura masiva de demanda del reporte. El enlace es por
     * {@code articulo_codigo} (FK), nunca por nombre: dos articulos con el
     * mismo nombre no comparten historia.</p>
     *
     * @param dias ventana retrospectiva en dias; {@code <= 0} usa 180
     * @return mapa articulo -> serie diaria cero-rellenada (solo articulos con venta)
     */
    @Transactional(TxType.SUPPORTS)
    @Nonnull
    public Map<Long, List<SerieDiaria>> seriesDiariasActivas(int dias) {
        int ventana = dias <= 0 ? 180 : Math.max(1, dias);
        LocalDate hoy = LocalDate.now();
        LocalDate inicio = hoy.minusDays(ventana);
        ZoneId zona = ZoneId.systemDefault();
        Date desde = Date.from(inicio.atStartOfDay(zona).toInstant());
        Date hasta = Date.from(hoy.plusDays(1).atStartOfDay(zona).toInstant().minusMillis(1));

        List<Object[]> movimientos = entityManager.createQuery("""
                        SELECT i.articulo.codigo,
                               CAST(i.fechaMovimiento AS date),
                               SUM(ABS(i.cantidad))
                        FROM Inventario i
                        WHERE i.tipoMovimiento = 'Venta'
                          AND (i.status IS NULL OR i.status = true)
                          AND i.fechaMovimiento BETWEEN :desde AND :hasta
                          AND i.articulo.status = true
                        GROUP BY i.articulo.codigo, CAST(i.fechaMovimiento AS date)
                        ORDER BY i.articulo.codigo, CAST(i.fechaMovimiento AS date)
                        """, Object[].class)
                .setParameter("desde", desde)
                .setParameter("hasta", hasta)
                .getResultList();

        Map<Long, TreeMap<LocalDate, Double>> porArticulo = new java.util.HashMap<>();
        for (Object[] movimiento : movimientos) {
            if (movimiento[0] == null) {
                continue;
            }
            long codigo = ((Number) movimiento[0]).longValue();
            LocalDate dia = aFecha(movimiento[1]);
            if (dia == null || dia.isBefore(inicio) || dia.isAfter(hoy)) {
                continue;
            }
            double unidades = movimiento[2] instanceof Number numero
                    ? Math.abs(numero.doubleValue()) : 0.0;
            porArticulo.computeIfAbsent(codigo, clave -> new TreeMap<>())
                    .merge(dia, unidades, Double::sum);
        }

        Map<Long, List<SerieDiaria>> series = new java.util.HashMap<>(porArticulo.size());
        for (Map.Entry<Long, TreeMap<LocalDate, Double>> entrada : porArticulo.entrySet()) {
            TreeMap<LocalDate, Double> porDia = entrada.getValue();
            if (porDia.isEmpty()) {
                continue;
            }
            List<SerieDiaria> serie = new ArrayList<>();
            for (LocalDate dia = porDia.firstKey(); !dia.isAfter(hoy); dia = dia.plusDays(1)) {
                serie.add(new SerieDiaria(dia, porDia.getOrDefault(dia, 0.0)));
            }
            series.put(entrada.getKey(), List.copyOf(serie));
        }
        LOG.debugf("seriesDiariasActivas: %d articulo(s) con venta en %d dia(s) de ventana",
                series.size(), ventana);
        return Map.copyOf(series);
    }

    /**
     * Indices de origen: los ultimos {@code pedidos} domingos con horizonte completo.
     *
     * <p>Solo son validos los indices que dejan {@code horizonte} observaciones reales por
     * delante, porque comparar contra un horizonte truncado mediria un error artificial. Si la
     * historia no admite ningun domingo asi, se recurre a los ultimos indices utilizables: es
     * preferible una medida imperfecta sobre una serie corta a quedarse sin evaluacion.</p>
     */
    private List<Integer> origenesMoviles(List<SerieDiaria> serie, int horizonte, int pedidos) {
        int total = serie.size();
        List<Integer> domingos = new ArrayList<>();
        for (int i = 1; i + horizonte < total; i++) {
            if (serie.get(i).fecha().getDayOfWeek() == DayOfWeek.SUNDAY) {
                domingos.add(i);
            }
        }
        if (!domingos.isEmpty()) {
            return ultimos(domingos, pedidos);
        }

        List<Integer> cualquiera = new ArrayList<>();
        for (int i = 1; i + horizonte < total; i++) {
            cualquiera.add(i);
        }
        if (cualquiera.isEmpty()) {
            return cualquiera;
        }
        LOG.warn("origenesMoviles: la serie de " + total
                + " dias no admite ningun domingo con horizonte de " + horizonte
                + " dias; se usan los ultimos indices disponibles");
        return ultimos(cualquiera, pedidos);
    }

    private static List<Integer> ultimos(List<Integer> indices, int pedidos) {
        if (indices.size() <= pedidos) {
            return new ArrayList<>(indices);
        }
        return new ArrayList<>(indices.subList(indices.size() - pedidos, indices.size()));
    }

    /**
     * MAE del pronostico ingenuo dentro de la muestra de entrenamiento: media de
     * {@code |D(t) - D(t-1)|}. Es el denominador del MASE. Devuelve {@link Double#NaN} si no
     * hay al menos dos observaciones, porque la media no esta definida; el motor trata NaN
     * como escala no definida.
     */
    private static double maeIngenuoEnMuestra(List<SerieDiaria> entrenamiento) {
        if (entrenamiento.size() < 2) {
            return Double.NaN;
        }
        double suma = 0.0;
        for (int t = 1; t < entrenamiento.size(); t++) {
            suma += Math.abs(entrenamiento.get(t).cantidad() - entrenamiento.get(t - 1).cantidad());
        }
        return suma / (entrenamiento.size() - 1);
    }

    /** Convierte el valor de una columna temporal de JPQL a {@link LocalDate}. */
    private static LocalDate aFecha(@Nullable Object valor) {
        if (valor instanceof java.sql.Date sql) {
            return sql.toLocalDate();
        }
        if (valor instanceof Date util) {
            return util.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        }
        if (valor instanceof LocalDateTime instante) {
            return instante.toLocalDate();
        }
        if (valor instanceof LocalDate dia) {
            return dia;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Persistencia de la seleccion
    // ------------------------------------------------------------------

    /** Borra todas las filas de precision de un articulo (la tabla guarda una corrida, no un historico). */
    private void borrarPrecision(@Nonnull Long codigoArticulo) {
        entityManager.createQuery("DELETE FROM PrecisionPronostico p WHERE p.articulo.codigo = :codigo")
                .setParameter("codigo", codigoArticulo)
                .executeUpdate();
    }

    /** Borra solo las filas de un (articulo, horizonte): reevaluar 14 no borra 60. */
    private void borrarPrecision(@Nonnull Long codigoArticulo, int horizonteDias) {
        entityManager.createQuery("DELETE FROM PrecisionPronostico p "
                        + "WHERE p.articulo.codigo = :codigo AND p.horizonteDias = :horizonte")
                .setParameter("codigo", codigoArticulo)
                .setParameter("horizonte", Math.max(1, horizonteDias))
                .executeUpdate();
    }

    /** {@code true} si el articulo tiene al menos un movimiento de venta; guarda contra historia vacia. */
    private boolean tieneVentas(@Nonnull Long codigoArticulo) {
        return entityManager.createQuery(
                        "SELECT COUNT(i) FROM Inventario i "
                                + "WHERE i.articulo.codigo = :codigo AND i.tipoMovimiento = 'Venta'",
                        Long.class)
                .setParameter("codigo", codigoArticulo)
                .getSingleResult() > 0L;
    }

    private static int posicionSimplicidad(@Nullable String metodo) {
        int posicion = metodo == null ? -1 : ORDEN_SIMPLICIDAD.indexOf(metodo);
        return posicion < 0 ? ORDEN_SIMPLICIDAD.size() : posicion;
    }

    // ------------------------------------------------------------------
    // Transacciones
    // ------------------------------------------------------------------

    /** Accion que se ejecuta dentro de una transaccion iniciada y cerrada por {@link #enTransaccion}. */
    @FunctionalInterface
    private interface AccionTransaccional<T> {
        T ejecutar() throws Exception;
    }

    /**
     * Ejecuta una accion en su propia transaccion JTA.
     *
     * <p>Se usa desde {@link #reelegirTodo()} para que el fallo de un articulo no reviente la
     * corrida completa, y para que las consultas hechas fuera del contenedor
     * ({@code @Scheduled}) tengan un contexto de persistencia valido. El cuerpo se ejecuta
     * siempre dentro de una transaccion, incluso cuando el llamador ya abrio una.</p>
     */
    private <T> T enTransaccion(AccionTransaccional<T> accion) {
        try {
            userTransaction.begin();
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo iniciar la transaccion de seleccion de metodo", e);
        }
        try {
            T resultado = accion.ejecutar();
            userTransaction.commit();
            return resultado;
        } catch (Exception e) {
            revertir();
            throw new IllegalStateException("Fallo la transaccion de seleccion de metodo: " + e.getMessage(), e);
        }
    }

    private void revertir() {
        try {
            if (userTransaction.getStatus() != Status.STATUS_NO_TRANSACTION) {
                userTransaction.rollback();
            }
        } catch (Exception e) {
            LOG.warn("revertir: la transaccion JTA no pudo revertirse manualmente ("
                    + e.getMessage() + "); el contenedor la revierte al propagarse el error");
        }
    }

    // ------------------------------------------------------------------
    // Acumulador de metricas
    // ------------------------------------------------------------------

    /**
     * Acumula los pares real/pronostico de todos los origenes de un metodo y delega el calculo
     * del MASE y del sesgo en {@link MotorPronostico}.
     *
     * <p>La escala ingenua se promedia entre origenes (las ventanas de entrenamiento son
     * prefijos anidados, de modo que promediar sus MAEs es estable) mientras que los pares se
     * concatenan: como el motor divide la media de los errores absolutos por la escala, el
     * resultado coincide con la media de los MASE por origen.</p>
     */
    private static final class Acumulador {

        private final List<Double> reales = new ArrayList<>();
        private final List<Double> pronosticos = new ArrayList<>();
        private double sumaEscalas = 0.0;
        private int escalasDefinidas = 0;

        void registrar(List<Double> realesOrigen, List<Double> pronosticoOrigen, double escalaIngenua) {
            int comunes = Math.min(realesOrigen.size(), pronosticoOrigen.size());
            for (int i = 0; i < comunes; i++) {
                Double real = realesOrigen.get(i);
                Double predicho = pronosticoOrigen.get(i);
                if (real == null || predicho == null
                        || Double.isNaN(real) || Double.isNaN(predicho)
                        || Double.isInfinite(real) || Double.isInfinite(predicho)) {
                    continue;
                }
                reales.add(real);
                pronosticos.add(predicho);
            }
            if (Double.isFinite(escalaIngenua)) {
                sumaEscalas += escalaIngenua;
                escalasDefinidas++;
            }
        }

        boolean hayObservaciones() {
            return !reales.isEmpty();
        }

        /** Escala ingenua promediada entre los origenes; {@code 0.0} si ninguna la resolvio. */
        private double escalaMedia() {
            return escalasDefinidas == 0 ? 0.0 : sumaEscalas / escalasDefinidas;
        }

        /** MASE, o {@code null} si es indefinido (escala nula o sin pares comparables). */
        Double mase() {
            return aNullable(MotorPronostico.mase(reales, pronosticos, escalaMedia()));
        }

        /** Sesgo escalado, o {@code null} si es indefinido. */
        Double sesgo() {
            return aNullable(MotorPronostico.sesgoEscalado(reales, pronosticos, escalaMedia()));
        }

        /** MAE en unidades por periodo (media de |real - pronostico|), o {@code null} sin pares. */
        Double mae() {
            if (reales.isEmpty()) {
                return null;
            }
            double suma = 0.0;
            for (int i = 0; i < reales.size(); i++) {
                suma += Math.abs(reales.get(i) - pronosticos.get(i));
            }
            return suma / reales.size();
        }

        /** Observaciones real/pronostico acumuladas (origenes x horizonte menos huecos). */
        int observaciones() {
            return reales.size();
        }

        /**
         * PostgreSQL representaria {@code NaN} como el texto {@code 'NaN'}, que no es comparable
         * con los numeros al ordenar; la entidad exige {@code null} para lo indefinido.
         */
        private static Double aNullable(double valor) {
            return Double.isFinite(valor) ? valor : null;
        }
    }
}
