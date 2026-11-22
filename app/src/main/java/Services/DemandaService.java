package Services;

import Models.Articulos.Articulos;
import Models.Familia;
import Models.PrecisionPronostico;
import Services.pronostico.EconomiaReposicion;
import Services.pronostico.FlagCalidad;
import Services.pronostico.Hiperparametros;
import Services.pronostico.IntervalosPronostico;
import Services.pronostico.MetodoPronostico;
import Services.pronostico.MotorPronostico;
import Services.pronostico.Regimen;
import Services.pronostico.SerieDiaria;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Servicio de prediccion de demanda: convierte la historia de ventas en una cifra de demanda
 * futura con su intervalo de confianza y su punto de reorden.
 *
 * <p><b>Que aporta esta capa sobre el motor.</b> {@link MotorPronostico} es puro (sin CDI ni base
 * de datos) y {@link SeleccionMetodoService} es la capa de decision y persistencia. Este servicio
 * es la capa de <em>consumo</em>: elige el metodo que corresponde a cada llamada, proyecta el
 * horizonte pedido, cuantifica la incertidumbre y responde a las preguntas de reposicion. No
 * reimplementa ninguna consulta de ventas: la serie diaria se pide a
 * {@link SeleccionMetodoService#serieDiaria(Long, int)} para que la agregacion por articulo y dia
 * tenga una unica definicion en todo el sistema.</p>
 *
 * <p><b>Metodo usado.</b> Se respeta el metodo que gano el backtesting
 * ({@link SeleccionMetodoService#metodoElegido(Long)}) siempre que siga siendo elegible sobre la
 * historia actual. Si no hay metodo elegido —un articulo recien dado de alta, o cuya seleccion
 * quedo sin filas— se recurre, sin persistir nada, al primer candidato elegible del regimen que
 * clasifique su serie. En el peor caso se pronostica con
 * {@link MetodoPronostico#INGENUO}, que es el unico metodo que nunca falla.</p>
 *
 * <p><b>Intervalo de prediccion.</b> La sigma de respaldo se recalcula en cada
 * llamada con el mismo procedimiento, sobre el metodo efectivamente elegido, y
 * por eso es coherente con el pronostico que acompana: desviacion tipica
 * muestral de los residuos <em>un paso adelante</em> de los ultimos
 * {@value #VENTANA_RESIDUOS} dias. Cuando hay suficientes origenes, el
 * intervalo usa los cuantiles empiricos del error <em>a ese horizonte</em>
 * ({@link IntervalosPronostico}); si no, cae en
 * {@code z * sigma * sqrt(horizonte)} y se marca como aproximado.</p>
 *
 * <p><b>z.</b> Se obtiene con la inversa normal de Acklam en
 * {@link EconomiaReposicion#z(double)}, porque el nivel efectivo puede subir
 * por la razon critica de costos y no se limita a cuatro valores tabulados.</p>
 *
 * <p><b>Horizonte.</b> El motor indexa el horizonte desde h = 1 (el dia siguiente a la ultima
 * observacion), asi que el dia solicitado se resuelve como {@code h = dias(hoy, dia) + 1} y el
 * horizonte total que se pide al motor es la distancia a {@code hasta} mas uno. Ese horizonte se
 * acota a {@code [1, horizonteMaximo]} ({@code mercurius.pronostico.horizonte-maximo}, 90 dias por
 * defecto): mas alla de un trimestre la prediccion deja de ser accionable y, en un regimen
 * estacional, un horizonte mas largo que la historia no aporta informacion nueva. Los dias del
 * rango pedido que caen fuera del horizonte se resuelven contra el ultimo valor disponible, que
 * es lo coherente con un pronostico plano por construccion (INGENUO, MEDIA_MOVIL, SES).</p>
 *
 * <p><b>Casos limite.</b> Documentados aqui porque son contrato, no accidente:</p>
 * <ul>
 *   <li>articulo inexistente o {@code codigoArticulo} nulo: {@link #predecirDemanda} devuelve
 *       {@code null} (no lanza) y {@link #puntoReorden} devuelve {@code 0.0}. Es la misma
 *       convencion que {@link StockForecastService} para un id desconocido, y evita que una
 *       pantalla de reposicion reviente por una fila de catalogo borrada.</li>
 *   <li>historia vacia: total {@code 0.0}, intervalo {@code [0, 0]}, metodo
 *       {@link MetodoPronostico#INGENUO} y MASE {@code null}. No hay nada que pronosticar ni que
 *       medir, y no es un error.</li>
 *   <li>{@code hasta} anterior a {@code desde} (o cualquiera de los dos nulo):
 *       {@link IllegalArgumentException}; un rango invertido no tiene lectura posible y
 *       devolveria silenciosamente una prediccion sin sentido.</li>
 *   <li>familia inexistente o sin articulos: {@code null} y un pronostico vacio respectivamente,
 *       por simetria con el articulo.</li>
 * </ul>
 *
 * <p><b>Sin escrituras.</b> Ninguno de los tres metodos publicos persiste nada: leer una
 * prediccion no puede dejar rastro en la base. La unica escritura del sistema de pronosticos es
 * la corrida de backtesting de {@link SeleccionMetodoService}.</p>
 *
 * @author Mercurius
 */
@ApplicationScoped
@Named("demandaService")
public class DemandaService {

    private static final Logger LOG = Logger.getLogger(DemandaService.class);

    /** Ventana de historia en dias, la misma que usa el backtesting de metodos. */
    public static final int DIAS_HISTORIA = SeleccionMetodoService.DIAS_HISTORIA;

    /** Dias de residuos que entran en la sigma del intervalo. */
    private static final int VENTANA_RESIDUOS = 60;

    /** Ventana, en dias, de la participacion de cada articulo en el reparto de su familia. */
    private static final int DIAS_PARTICIPACION = 30;

    /** Prefijo del metodo en un pronostico top-down de familia. */
    private static final String PREFIJO_TOP_DOWN = "TOP_DOWN:";

    /**
     * Tope del horizonte en dias. Acota tanto la prediccion de articulo como la de familia; un
     * horizonte de mas de un trimestre deja de ser accionable para reponer.
     */
    @ConfigProperty(name = "mercurius.pronostico.horizonte-maximo", defaultValue = "90")
    int horizonteMaximoConfigurado;

    @Inject
    EntityManager entityManager;

    @Inject
    SeleccionMetodoService seleccionMetodoService;

    @Inject
    ConfiguracionPronosticoService configuracionPronosticoService;

    // ------------------------------------------------------------------
    // Prediccion por articulo
    // ------------------------------------------------------------------

    /**
     * Pronostico de demanda de un articulo con su intervalo de prediccion.
     *
     * <p>El total es la suma de los valores diarios del pronostico para las fechas del rango
     * {@code [desde, hasta]} (ambos inclusive). El intervalo se construye con
     * {@link IntervalosPronostico}: cuantiles empiricos del error a ese horizonte
     * cuando hay muestra, o la aproximacion normal marcada como tal. El limite
     * inferior se acota a 0: una demanda negativa no existe.</p>
     *
     * @param codigoArticulo articulo a pronosticar
     * @param desde          primer dia del rango (inclusive)
     * @param hasta          ultimo dia del rango (inclusive)
     * @return prediccion con su intervalo, o {@code null} si el articulo no existe
     * @throws IllegalArgumentException si {@code desde} o {@code hasta} son nulos, o si
     *                                  {@code hasta} es anterior a {@code desde}
     */
    @Transactional(TxType.SUPPORTS)
    @Nullable
    public PrediccionDemanda predecirDemanda(Long codigoArticulo, LocalDate desde, LocalDate hasta) {
        validarRango(desde, hasta);

        if (!existeArticulo(codigoArticulo, "predecirDemanda")) {
            return null;
        }

        List<SerieDiaria> serie = serieDiaria(codigoArticulo);
        if (serie.isEmpty()) {
            LOG.debugf("predecirDemanda: el articulo %d no tiene ventas; se pronostica 0 con INGENUO", codigoArticulo);
            return new PrediccionDemanda(desde, hasta, 0.0, 0.0, 0.0,
                    MetodoPronostico.INGENUO.name(), null);
        }

        LocalDate hoy = LocalDate.now();
        int horizonte = horizonte(hasta, hoy);
        MetodoPronostico metodo = metodoEfectivo(codigoArticulo, serie, horizonte);
        List<Double> diario = MotorPronostico.pronosticar(
                metodo, serie, horizonte, Hiperparametros.defecto());

        double total = sumarRango(diario, desde, hasta, hoy, horizonte);
        double sigma = IntervalosPronostico.sigmaUnPaso(metodo, serie);
        IntervalosPronostico.Intervalo intervalo = IntervalosPronostico.intervalo(
                metodo, serie, horizonte, total, nivelServicioEfectivo(), sigma);
        Double mase = maseAlmacenado(codigoArticulo, metodo, horizonte);

        LOG.debugf("predecirDemanda: articulo=%d metodo=%s horizonte=%d total=%.2f "
                        + "intervalo=[%.2f,%.2f] tipo=%s mase=%s",
                codigoArticulo, metodo, horizonte, total,
                intervalo.limiteInferior(), intervalo.limiteSuperior(),
                intervalo.metodo(), mase);

        return new PrediccionDemanda(desde, hasta, total,
                intervalo.limiteInferior(), intervalo.limiteSuperior(),
                metodo.name(), mase, intervalo.metodo(), "");
    }

    /**
     * Punto de reorden: unidades que hay que tener en stock para cubrir el plazo de reposicion con
     * la cobertura de seguridad del nivel de servicio pedido.
     *
     * <p>Es la demanda pronosticada de los proximos {@code diasPlazo} dias mas el stock de
     * seguridad {@code z * sigma * sqrt(diasPlazo)}, con la misma sigma de residuos y la misma
     * lectura de metodo que {@link #predecirDemanda}; se recalcula en cada llamada porque el
     * servicio es de solo lectura y no tiene (ni quiere) una tabla de resultados caducables.</p>
     *
     * <p>Un plazo no positivo se acota a 1 dia en lugar de rechazarse: el dato viene de la orden de
     * compra, y un plazo de 0 debe devolver la demanda de manana mas un dia de colchon en vez de
     * tumbar la pantalla de reposicion. Un {@code nivelServicio} no representativo (NaN, <= 0 o
     * > 1) se registra y se resuelve con la tabla de z.</p>
     *
     * @param codigoArticulo articulo cuyo punto de reorden se pide
     * @param diasPlazo      plazo de reposicion en dias
     * @param nivelServicio  cobertura objetivo en el plazo, en (0, 1)
     * @return unidades a mantener en stock, o {@code 0.0} si el articulo no existe o no tiene ventas
     */
    @Transactional(TxType.SUPPORTS)
    public double puntoReorden(Long codigoArticulo, int diasPlazo, double nivelServicio) {
        if (!existeArticulo(codigoArticulo, "puntoReorden")) {
            return 0.0;
        }

        int plazo = Math.max(1, diasPlazo);
        if (plazo != diasPlazo) {
            LOG.warnf("puntoReorden: plazo de %d dias no utilizable para el articulo %d; se trabaja con 1 dia",
                    diasPlazo, codigoArticulo);
        }

        List<SerieDiaria> serie = serieDiaria(codigoArticulo);
        if (serie.isEmpty()) {
            return 0.0;
        }

        LocalDate hoy = LocalDate.now();
        int horizonte = horizonte(hoy.plusDays(plazo), hoy);
        MetodoPronostico metodo = metodoEfectivo(codigoArticulo, serie, horizonte);
        List<Double> diario = MotorPronostico.pronosticar(
                metodo, serie, horizonte, Hiperparametros.defecto());

        // El plazo de reposicion cubre los dias siguientes a hoy: h = 1..plazo.
        double demanda = sumarRango(diario, hoy.plusDays(1), hoy.plusDays(plazo), hoy, horizonte);
        double sigma = IntervalosPronostico.sigmaUnPaso(metodo, serie);
        ConfiguracionPronosticoService.ParametrosPronostico parametros =
                configuracionPronosticoService.parametros();
        double nivelEfectivo = EconomiaReposicion.nivelServicio(
                nivelServicio, parametros.costoFaltante(), parametros.costoMantenimiento());
        EconomiaReposicion.Reposicion reposicion = EconomiaReposicion.calcular(
                0, demanda, sigma, plazo, nivelEfectivo, 0, 1);

        LOG.debugf("puntoReorden: articulo=%d metodo=%s plazo=%d demanda=%.2f seguridad=%.2f nivel=%.3f",
                codigoArticulo, metodo, plazo, reposicion.demandaPlazo(),
                reposicion.stockSeguridad(), reposicion.nivelServicio());
        return reposicion.puntoReorden();
    }

    // ------------------------------------------------------------------
    // Pronostico top-down por familia
    // ------------------------------------------------------------------

    /**
     * Pronostico top-down de una familia: se pronostica la familia como un todo y el total se
     * reparte despues entre sus articulos, en lugar de sumar pronosticos independientes.
     *
     * <p><b>Por que top-down y no bottom-up.</b> Los articulos de una familia comparten patron
     * (misma gondola, mismo cliente, misma campana) y muchos de ellos son de rotacion lenta: su
     * historia individual es corta y su pronostico individual sale ruidoso y descuadrado con
     * respecto al movimiento real de la familia. Pronosticando el agregado se gana una serie mucho
     * mas estable, a cambio de perder la forma individual de cada articulo, que es justo lo que se
     * acepta aqui.</p>
     *
     * <p><b>Reparto.</b> Cada articulo recibe la parte proporcional a sus unidades vendidas en los
     * ultimos {@value #DIAS_PARTICIPACION} dias. Los articulos sin venta en esa ventana reciben
     * <b>cero</b> y se marcan {@link FlagCalidad#NO_RECENT_PARTICIPATION}; no se les asigna una
     * cuota especulativa. Si ningun articulo tiene participacion, el total repartido es cero y la
     * respuesta lo declara: no hay base para atribuir demanda. Esto reemplaza el reparto a partes
     * iguales anterior, que podia crear pronosticos implausibles para SKUs dormidos.</p>
     *
     * <p><b>Metodo.</b> Se elige aqui, sin persistir nada, el candidato elegible con menor MAE de
     * un paso adelante dentro de la muestra: la familia no tiene filas en
     * {@link PrecisionPronostico} (esa tabla es por articulo) y la eleccion por backtesting de
     * una familia entera multiplicaria el coste de la corrida semanal por una medida que la
     * planificacion ya consume de otra forma. Ante empate gana el primer candidato de la lista del
     * regimen, que el motor ordena de mas simple a mas parametrizado.</p>
     *
     * @param familiaCodigo id de la familia
     * @param desde         primer dia del rango (inclusive)
     * @param hasta         ultimo dia del rango (inclusive)
     * @return total de la familia con su intervalo y {@code metodo = "TOP_DOWN:<metodo>"} y MASE
     *         {@code null} (no hay medicion persistida de una familia), o {@code null} si la
     *         familia no existe
     * @throws IllegalArgumentException si {@code desde} o {@code hasta} son nulos, o si
     *                                  {@code hasta} es anterior a {@code desde}
     */
    @Transactional(TxType.SUPPORTS)
    @Nullable
    public PrediccionDemanda predecirFamilia(Long familiaCodigo, LocalDate desde, LocalDate hasta) {
        validarRango(desde, hasta);

        if (familiaCodigo == null) {
            LOG.warn("predecirFamilia: se llamo sin codigo de familia; se devuelve null");
            return null;
        }
        Familia familia = entityManager.find(Familia.class, familiaCodigo.intValue());
        if (familia == null) {
            LOG.warnf("predecirFamilia: la familia %d no existe; se devuelve null", familiaCodigo);
            return null;
        }

        List<Long> miembros = miembrosDe(familiaCodigo);
        if (miembros.isEmpty()) {
            LOG.warnf("predecirFamilia: la familia %d no tiene articulos; se devuelve un pronostico vacio",
                    familiaCodigo);
            return new PrediccionDemanda(desde, hasta, 0.0, 0.0, 0.0,
                    PREFIJO_TOP_DOWN + MetodoPronostico.INGENUO.name(), null);
        }

        List<SerieDiaria> agregada = serieAgregada(miembros);
        if (agregada.isEmpty()) {
            LOG.warnf("predecirFamilia: ningun articulo de la familia %d registro ventas en los ultimos %d dias",
                    familiaCodigo, DIAS_HISTORIA);
            return new PrediccionDemanda(desde, hasta, 0.0, 0.0, 0.0,
                    PREFIJO_TOP_DOWN + MetodoPronostico.INGENUO.name(), null);
        }

        MetodoPronostico metodo = metodoDeFamilia(agregada);
        LocalDate hoy = LocalDate.now();
        int horizonte = horizonte(hasta, hoy);
        List<Double> diario = MotorPronostico.pronosticar(
                metodo, agregada, horizonte, Hiperparametros.defecto());
        double total = sumarRango(diario, desde, hasta, hoy, horizonte);

        // El total devuelto es la suma de las cuotas por articulo. Solo se
        // reparte entre articulos con participacion positiva: un SKU dormido
        // no recibe una cuota especulativa, se queda en cero y se marca.
        Map<Long, Double> porArticulo = repartirPorParticipacion(total, miembros);
        double totalRepartido = 0.0;
        long sinParticipacion = 0;
        for (Double cuota : porArticulo.values()) {
            totalRepartido += cuota;
            if (cuota <= 0.0) {
                sinParticipacion++;
            }
        }
        String flags = sinParticipacion > 0
                ? FlagCalidad.NO_RECENT_PARTICIPATION.name() : "";
        if (sinParticipacion > 0) {
            LOG.warnf("predecirFamilia: familia=%d tiene %d articulo(s) sin venta en la ventana "
                            + "de participacion; se les asigna 0 y se marcan en lugar de repartir a ciegas",
                    familiaCodigo, sinParticipacion);
        }

        double sigma = IntervalosPronostico.sigmaUnPaso(metodo, agregada);
        IntervalosPronostico.Intervalo intervalo = IntervalosPronostico.intervalo(
                metodo, agregada, horizonte, totalRepartido, nivelServicioEfectivo(), sigma);
        LOG.debugf("predecirFamilia: familia=%d metodo=%s articulos=%d total=%.2f "
                        + "intervalo=[%.2f,%.2f] tipo=%s reparto=%s",
                familiaCodigo, metodo, miembros.size(), totalRepartido,
                intervalo.limiteInferior(), intervalo.limiteSuperior(),
                intervalo.metodo(), porArticulo);

        return new PrediccionDemanda(desde, hasta, totalRepartido,
                intervalo.limiteInferior(), intervalo.limiteSuperior(),
                PREFIJO_TOP_DOWN + metodo.name(), null, intervalo.metodo(), flags);
    }

    // ------------------------------------------------------------------
    // Pronostico de un articulo
    // ------------------------------------------------------------------

    /**
     * Pronostico de demanda de un articulo con su intervalo de prediccion.
     *
     * @param desde primer dia del rango (inclusive)
     * @param hasta ultimo dia del rango (inclusive)
     * @return prediccion con su intervalo y el MASE medido del metodo elegido
     */
    public record PrediccionDemanda(LocalDate desde,
                                    LocalDate hasta,
                                    double totalPronosticado,
                                    double limiteInferior,
                                    double limiteSuperior,
                                    String metodo,
                                    Double mase,
                                    String tipoIntervalo,
                                    String calidadFlags) {

        public PrediccionDemanda(LocalDate desde, LocalDate hasta, double totalPronosticado,
                                 double limiteInferior, double limiteSuperior,
                                 String metodo, Double mase) {
            this(desde, hasta, totalPronosticado, limiteInferior, limiteSuperior,
                    metodo, mase, "APROXIMADO", "");
        }
    }

    // ------------------------------------------------------------------
    // Metodo efectivo
    // ------------------------------------------------------------------

    /**
     * Metodo con el que se pronostica: el que gano el backtesting si sigue siendo elegible sobre
     * la historia actual; si no, el primer candidato elegible del regimen de la serie; si tampoco
     * hay ninguno, {@link MetodoPronostico#INGENUO}.
     */
    private MetodoPronostico metodoEfectivo(Long codigoArticulo, List<SerieDiaria> serie, int horizonte) {
        Optional<MetodoPronostico> elegido = seleccionMetodoService.metodoElegido(codigoArticulo, horizonte)
                .flatMap(DemandaService::aMetodo);
        if (elegido.isPresent() && MotorPronostico.elegible(elegido.get(), serie)) {
            return elegido.get();
        }
        if (elegido.isPresent()) {
            LOG.debugf("El metodo elegido %s ya no es elegible sobre %d observaciones; se recalcula por regimen",
                    elegido.get(), serie.size());
        }

        Regimen regimen = MotorPronostico.clasificar(serie);
        for (MetodoPronostico candidato : MotorPronostico.candidatos(regimen)) {
            if (MotorPronostico.elegible(candidato, serie)) {
                return candidato;
            }
        }
        // Solo se llega aqui con una serie que el motor no puede usar; INGENUO es el metodo que
        // nunca falla y devuelve ceros, que es la lectura correcta de "sin demanda".
        LOG.warnf("Ningun candidato del regimen %s es elegible sobre %d observaciones; se pronostica con INGENUO",
                regimen, serie.size());
        return MetodoPronostico.INGENUO;
    }

    /**
     * Eleccion rapida para una serie sin seleccion persistida: el candidato elegible con menor
     * MAE de un paso adelante dentro de la muestra. Empate: gana el primero de la lista de
     * candidatos, que el motor ordena de mas simple a mas parametrizado.
     */
    private static MetodoPronostico metodoDeFamilia(List<SerieDiaria> serie) {
        Regimen regimen = MotorPronostico.clasificar(serie);
        MetodoPronostico mejor = null;
        double mejorMae = Double.POSITIVE_INFINITY;

        for (MetodoPronostico candidato : MotorPronostico.candidatos(regimen)) {
            if (!MotorPronostico.elegible(candidato, serie)) {
                continue;
            }
            double mae = maeUnPasoAdelante(residuosUnPasoAdelante(serie, candidato));
            if (!Double.isFinite(mae)) {
                continue;
            }
            if (mae < mejorMae) {
                mejorMae = mae;
                mejor = candidato;
            }
        }
        if (mejor == null) {
            LOG.warnf("La serie agregada de familia (regimen %s, %d observaciones) no admite ningun candidato; se usa INGENUO",
                    regimen, serie.size());
            return MetodoPronostico.INGENUO;
        }
        LOG.debugf("Metodo de familia por minimo MAE in-sample: %s (MAE=%.3f, regimen=%s)", mejor, mejorMae, regimen);
        return mejor;
    }

    /** Traduce el nombre guardado en {@code precision_pronostico.metodo}; tolera valores ajenos. */
    private static Optional<MetodoPronostico> aMetodo(String nombre) {
        if (nombre == null) {
            LOG.warn("La fila de precision guardada no trae metodo; se recalcula por regimen");
            return Optional.empty();
        }
        try {
            return Optional.of(MetodoPronostico.valueOf(nombre.trim()));
        } catch (IllegalArgumentException e) {
            LOG.warnf("El metodo guardado '%s' no existe en el catalogo de MetodoPronostico; se recalcula por regimen", nombre);
            return Optional.empty();
        }
    }

    /**
     * Ultimo MASE persistido del metodo con el que se pronostica, o {@code null} si el articulo
     * nunca fue evaluado con ese metodo (o su MASE quedo indefinido y se guardo como nulo).
     */
    private Double maseAlmacenado(Long codigoArticulo, MetodoPronostico metodo, int horizonte) {
        List<Double> mases = entityManager.createQuery(
                        "SELECT p.mase FROM PrecisionPronostico p "
                                + "WHERE p.articulo.codigo = :codigo AND p.metodo = :metodo "
                                + "AND p.horizonteDias = :horizonte AND p.mase IS NOT NULL "
                                + "ORDER BY p.fechaCalculo DESC, p.id DESC",
                        Double.class)
                .setParameter("codigo", codigoArticulo)
                .setParameter("metodo", metodo.name())
                .setParameter("horizonte", Math.max(1, horizonte))
                .setMaxResults(1)
                .getResultList();
        return mases.isEmpty() ? null : mases.get(0);
    }

    // ------------------------------------------------------------------
    // Residuos e incertidumbre
    // ------------------------------------------------------------------

    /**
     * Residuos de un paso adelante del metodo sobre la serie: para cada uno de los ultimos
     * {@value #VENTANA_RESIDUOS} dias, la diferencia entre lo que ocurrio y lo que el metodo
     * habria pronosticado entrenando <em>solo</em> con los dias anteriores.
     *
     * <p>Es la unica forma de medir el error del metodo sin contaminarlo con su propia salida:
     * un residuo calculado sobre el mismo tramo con el que se entreno, ademas, seria una medida de
     * ajuste, no de prediccion.</p>
     */
    private static List<Double> residuosUnPasoAdelante(List<SerieDiaria> serie, MetodoPronostico metodo) {
        int total = serie.size();
        int ventana = Math.min(VENTANA_RESIDUOS, total);
        List<Double> residuos = new ArrayList<>(ventana);

        for (int t = Math.max(1, total - ventana); t < total; t++) {
            List<SerieDiaria> entrenamiento = List.copyOf(serie.subList(0, t));
            if (!MotorPronostico.elegible(metodo, entrenamiento)) {
                continue;
            }
            List<Double> pronostico = MotorPronostico.pronosticar(
                    metodo, entrenamiento, 1, Hiperparametros.defecto());
            if (pronostico == null || pronostico.isEmpty()) {
                continue;
            }
            Double previsto = pronostico.get(0);
            if (previsto == null || !Double.isFinite(previsto)) {
                continue;
            }
            residuos.add(serie.get(t).cantidad() - previsto);
        }
        return residuos;
    }

    /** Media del error absoluto de un paso adelante, que es el criterio de eleccion por familia. */
    private static double maeUnPasoAdelante(List<Double> residuos) {
        if (residuos.isEmpty()) {
            return Double.NaN;
        }
        double suma = 0.0;
        for (double residuo : residuos) {
            suma += Math.abs(residuo);
        }
        return suma / residuos.size();
    }

    // ------------------------------------------------------------------
    // Serie y agregacion de familia
    // ------------------------------------------------------------------

    /**
     * Serie diaria de ventas del articulo, en la misma ventana que usa el backtesting y con la
     * misma agregacion: se delega en {@link SeleccionMetodoService#serieDiaria} para que exista
     * una sola definicion de "un dia de ventas" en el sistema.
     */
    private List<SerieDiaria> serieDiaria(Long codigoArticulo) {
        List<SerieDiaria> serie = seleccionMetodoService.serieDiaria(codigoArticulo, DIAS_HISTORIA);
        return (serie == null) ? List.of() : serie;
    }

    /** Articulos de la familia, incluidos los archivados: si vendieron, forman parte del agregado. */
    private List<Long> miembrosDe(Long familiaCodigo) {
        return entityManager.createQuery(
                        "SELECT a.codigo FROM Articulos a WHERE a.familia.id = :familia ORDER BY a.codigo",
                        Long.class)
                .setParameter("familia", familiaCodigo.intValue())
                .getResultList();
    }

    /**
     * Serie agregada de la familia: suma de las series diarias de sus articulos, colapsada sobre
     * una rejilla diaria continua entre el primer y el ultimo dia con movimiento de cualquiera de
     * ellos. Un dia en el que todavia no empezo a vender un articulo cuenta como 0, que es la
     * lectura correcta (no hay demanda, no hay registro de demanda).
     */
    private List<SerieDiaria> serieAgregada(List<Long> miembros) {
        TreeMap<LocalDate, Double> porDia = new TreeMap<>();
        for (Long codigo : miembros) {
            for (SerieDiaria punto : serieDiaria(codigo)) {
                if (punto.fecha() == null) {
                    continue;
                }
                porDia.merge(punto.fecha(), punto.cantidad(), Double::sum);
            }
        }
        if (porDia.isEmpty()) {
            return List.of();
        }

        List<SerieDiaria> agregada = new ArrayList<>(porDia.size());
        for (LocalDate dia = porDia.firstKey(); !dia.isAfter(porDia.lastKey()); dia = dia.plusDays(1)) {
            agregada.add(new SerieDiaria(dia, porDia.getOrDefault(dia, 0.0)));
        }
        return agregada;
    }

    /**
     * Reparte un total de familia entre sus articulos de forma proporcional a las unidades
     * vendidas en los ultimos {@value #DIAS_PARTICIPACION} dias. Un articulo sin venta en esa
     * ventana recibe 0 y se marca {@link FlagCalidad#NO_RECENT_PARTICIPATION}: no se le asigna una
     * cuota especulativa.
     */
    private Map<Long, Double> repartirPorParticipacion(double total, List<Long> miembros) {
        Map<Long, Double> unidades = new LinkedHashMap<>();
        double suma = 0.0;
        for (Long codigo : miembros) {
            List<SerieDiaria> serie = serieDiaria(codigo);
            int desde = Math.max(0, serie.size() - DIAS_PARTICIPACION);
            double vendido = 0.0;
            for (int i = desde; i < serie.size(); i++) {
                vendido += serie.get(i).cantidad();
            }
            unidades.put(codigo, vendido);
            suma += vendido;
        }

        Map<Long, Double> reparto = new LinkedHashMap<>();
        for (Map.Entry<Long, Double> articulo : unidades.entrySet()) {
            if (suma > 0.0 && articulo.getValue() > 0.0) {
                reparto.put(articulo.getKey(), total * articulo.getValue() / suma);
            } else {
                // Sin participacion reciente no se inventa una cuota: el SKU
                // queda en cero y el llamador lo marca
                // NO_RECENT_PARTICIPATION.
                reparto.put(articulo.getKey(), 0.0);
            }
        }
        return reparto;
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** Suma los valores diarios del pronostico que caen en el rango pedido. */
    private static double sumarRango(List<Double> pronostico, LocalDate desde, LocalDate hasta,
                                      LocalDate hoy, int horizonte) {
        double total = 0.0;
        for (LocalDate dia = desde; !dia.isAfter(hasta); dia = dia.plusDays(1)) {
            total += valorDelDia(pronostico, dia, hoy, horizonte);
        }
        return total;
    }

    /**
     * Valor del pronostico para un dia. El indice es {@code h = dias(hoy, dia) + 1} porque el
     * motor numera el horizonte desde el dia siguiente a la ultima observacion; se acota a
     * {@code [1, horizonte]} para que un rango en el pasado o mas alla del tope no rompa el
     * pronostico.
     */
    private static double valorDelDia(List<Double> pronostico, LocalDate dia, LocalDate hoy, int horizonte) {
        if (pronostico.isEmpty()) {
            return 0.0;
        }
        long h = ChronoUnit.DAYS.between(hoy, dia) + 1L;
        int indice = (int) Math.max(1L, Math.min(horizonte, h));
        Double valor = pronostico.get(indice - 1);
        if (valor == null || !Double.isFinite(valor)) {
            return 0.0;
        }
        return Math.max(0.0, valor);
    }

    /**
     * Horizonte pedido al motor para llegar a {@code hasta}: {@code dias(hoy, hasta) + 1} acotado
     * a {@code [1, horizonte-maximo]}.
     */
    private int horizonte(LocalDate hasta, LocalDate hoy) {
        int maximo = Math.max(1, horizonteMaximoConfigurado);
        long dias = ChronoUnit.DAYS.between(hoy, hasta) + 1L;
        if (dias < 1L) {
            LOG.debugf("El rango termina en %s, antes de hoy; el horizonte se acota a 1 dia", hasta);
            return 1;
        }
        if (dias > maximo) {
            LOG.debugf("El rango pide %d dias de horizonte y el tope configurado es %d; se pronostica a %d dias",
                    dias, maximo, maximo);
            return maximo;
        }
        return (int) dias;
    }

    /** Nivel de servicio vigente en la configuracion de pronostico, validado por el servicio. */
    private double nivelServicioEfectivo() {
        return configuracionPronosticoService.parametros().nivelServicio();
    }

    /** El articulo existe: si no, se avisa y el llamador decide que hacer con el hueco. */
    private boolean existeArticulo(Long codigoArticulo, String origen) {
        if (codigoArticulo == null) {
            LOG.warnf("%s: se llamo sin codigo de articulo", origen);
            return false;
        }
        if (entityManager.find(Articulos.class, codigoArticulo) == null) {
            LOG.warnf("%s: el articulo %d no existe; no hay demanda que pronosticar", origen, codigoArticulo);
            return false;
        }
        return true;
    }

    private static void validarRango(LocalDate desde, LocalDate hasta) {
        if (desde == null || hasta == null) {
            throw new IllegalArgumentException(
                    "El rango de pronostico necesita 'desde' y 'hasta' no nulos (desde=" + desde + ", hasta=" + hasta + ")");
        }
        if (hasta.isBefore(desde)) {
            throw new IllegalArgumentException(
                    "'hasta' (" + hasta + ") es anterior a 'desde' (" + desde + "): el rango esta invertido");
        }
    }
}
