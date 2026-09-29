package Services;

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
 *   <li>decidir el ganador y dejar una fila por metodo candidato para que el desempate sea
 *       auditable.</li>
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
 * seleccion de los demas. El coste (una consulta de movimientos y hasta 4 pronosticos por origen
 * y articulo) es aceptable para el tamano de catalogo actual; si creciera, el siguiente paso es
 * es laminar la lista de articulos candidatos por un filtro de rotacion.</p>
 *
 * @author Mercurius
 */
@ApplicationScoped
@Named("seleccionMetodoService")
public class SeleccionMetodoService {

    private static final Logger LOG = Logger.getLogger(SeleccionMetodoService.class);

    /**
     * Ventana de historia considerada, en dias. Cubre varios ciclos semanales ( HOLT_WINTERS
     * exige 28 observaciones) con holgada para un horizonte de dos semanas.
     */
    public static final int DIAS_HISTORIA = 120;

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

    /** Horizonte por defecto de la reevaluacion semanal, en dias. */
    @ConfigProperty(name = "mercurius.pronostico.horizonte-dias", defaultValue = "14")
    int horizontePorDefecto;

    /** Numero de origenes moviles por defecto de la reevaluacion semanal. */
    @ConfigProperty(name = "mercurius.pronostico.origenes", defaultValue = "3")
    int origenesPorDefecto;

    @Inject
    EntityManager entityManager;

    @Inject
    UserTransaction userTransaction;

    // ------------------------------------------------------------------
    // Evaluacion
    // ------------------------------------------------------------------

    /**
     * Reevalua un articulo: mide cada metodo candidato de su regimen con origenes moviles y
     * reemplaza por completo las filas de precision anteriores de ese articulo.
     *
     * <p>El borrado es previo y total (una fila por metodo candidato elegible, nunca mas), de
     * modo que repetir la evaluacion no duplica resultados: es idempotente.</p>
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
            borrarPrecision(codigoArticulo);
            return 0;
        }

        List<Integer> indicesOrigen = origenesMoviles(serie, horizonte, origenesPedidos);
        if (indicesOrigen.isEmpty()) {
            LOG.warn("evaluarArticulo: el articulo " + codigoArticulo + " tiene " + serie.size()
                    + " dia(s) de historia y no admite ningun origen con horizonte de " + horizonte
                    + " dias; se borran sus filas de precision por no ser evaluable");
            borrarPrecision(codigoArticulo);
            return 0;
        }

        Regimen regimen = MotorPronostico.clasificar(serie);
        List<MetodoPronostico> candidatos = MotorPronostico.candidatos(regimen);

        Map<MetodoPronostico, Acumulador> metricas = new EnumMap<>(MetodoPronostico.class);
        for (MetodoPronostico metodo : candidatos) {
            metricas.put(metodo, new Acumulador());
        }

        for (int indice : indicesOrigen) {
            // El entrenamiento cierra en el propio dia del origen; el horizonte son los
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

        borrarPrecision(codigoArticulo);

        LocalDateTime ahora = LocalDateTime.now();
        int guardadas = 0;
        for (Map.Entry<MetodoPronostico, Acumulador> entrada : metricas.entrySet()) {
            Acumulador acumulado = entrada.getValue();
            if (!acumulado.hayObservaciones()) {
                continue;
            }
            PrecisionPronostico fila = new PrecisionPronostico();
            fila.setArticulo(articulo);
            fila.setMetodo(entrada.getKey().name());
            fila.setMase(acumulado.mase());
            fila.setSesgo(acumulado.sesgo());
            fila.setHorizonteDias(horizonte);
            fila.setFechaCalculo(ahora);
            entityManager.persist(fila);
            guardadas++;
        }

        LOG.info("evaluarArticulo: articulo " + codigoArticulo + " regimen=" + regimen
                + " origenes=" + indicesOrigen.size() + " horizon=" + horizonte
                + " candidatos=" + candidatos.size() + " filas=" + guardadas);
        return guardadas;
    }

    // ------------------------------------------------------------------
    // Seleccion
    // ------------------------------------------------------------------

    /**
     * Metodo con mejor MASE de los almacenados para el articulo.
     *
     * <p>Solo se consideran las filas con MASE no nulo (una metrica indefinida no es comparable) y
     * el empate se resuelve por el orden de simplicidad de {@link #ORDEN_SIMPLICIDAD}.</p>
     *
     * @param codigoArticulo articulo consultado
     * @return nombre del metodo elegido, o {@link Optional#empty()} si no hay ninguna fila con
     *         MASE definido
     */
    @Transactional(TxType.SUPPORTS)
    public Optional<String> metodoElegido(@Nonnull Long codigoArticulo) {
        List<PrecisionPronostico> filas = entityManager.createQuery(
                        "SELECT p FROM PrecisionPronostico p "
                                + "WHERE p.articulo.codigo = :codigo AND p.mase IS NOT NULL",
                        PrecisionPronostico.class)
                .setParameter("codigo", codigoArticulo)
                .getResultList();

        if (filas.isEmpty()) {
            return Optional.empty();
        }
        return filas.stream()
                .min(Comparator.comparingDouble(PrecisionPronostico::getMase)
                        .thenComparingInt(fila -> posicionSimplicidad(fila.getMetodo()))
                        .thenComparing(PrecisionPronostico::getMetodo))
                .map(PrecisionPronostico::getMetodo);
    }

    /**
     * Reevalua todos los articulos activos con el horizonte y el numero de origenes configurados.
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
        int horizonte = Math.max(1, horizontePorDefecto);
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
                    return evaluarArticulo(codigo, horizonte, origenesPedidos);
                });
                if (filas > 0) {
                    reevaluados++;
                }
            } catch (RuntimeException e) {
                LOG.warn("reelegirTodo: el articulo " + codigo
                        + " no pudo reevaluarse (" + e.getMessage() + "); se continua con el resto del catalogo", e);
            }
        }

        LOG.info("reelegirTodo: " + reevaluados + " de " + codigos.size()
                + " articulos activos quedaron con metodo elegido (horizonte=" + horizonte
                + ", origenes=" + origenesPedidos + ")");
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
     * @param dias           ventana retrospectiva en dias
     * @return serie diaria ordenada, o lista vacia si no hay ventas en la ventana
     */
    @Transactional(TxType.SUPPORTS)
    public List<SerieDiaria> serieDiaria(@Nonnull Long codigoArticulo, int dias) {
        LocalDate hoy = LocalDate.now();
        LocalDate inicio = hoy.minusDays(Math.max(1, dias));
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

        TreeMap<LocalDate, Long> porDia = new TreeMap<>();
        for (Object[] movimiento : movimientos) {
            LocalDate dia = aFecha(movimiento[0]);
            if (dia == null || dia.isBefore(inicio) || dia.isAfter(hoy)) {
                continue;
            }
            long unidades = Math.abs(((Number) movimiento[1]).longValue());
            porDia.merge(dia, unidades, Long::sum);
        }

        if (porDia.isEmpty()) {
            return List.of();
        }

        List<SerieDiaria> serie = new ArrayList<>(porDia.size());
        for (LocalDate dia = porDia.firstKey(); !dia.isAfter(porDia.lastKey()); dia = dia.plusDays(1)) {
            serie.add(new SerieDiaria(dia, porDia.getOrDefault(dia, 0L)));
        }
        return serie;
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

        /**
         * PostgreSQL representaria {@code NaN} como el texto {@code 'NaN'}, que no es comparable
         * con los numeros al ordenar; la entidad exige {@code null} para lo indefinido.
         */
        private static Double aNullable(double valor) {
            return Double.isFinite(valor) ? valor : null;
        }
    }
}
