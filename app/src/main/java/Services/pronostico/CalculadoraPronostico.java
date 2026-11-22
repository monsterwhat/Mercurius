package Services.pronostico;

import Models.PrecisionPronostico;
import org.jboss.logging.Logger;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Conversion de una serie diaria y una seleccion de metodo en un pronostico
 * canonico con su economia de reposicion y sus marcas de calidad.
 *
 * <p>Es la pieza que comparten la pagina, la exportacion y el API: si los tres
 * llaman aqui, el valor que muestra la tabla y el que devuelve el endpoint no
 * pueden divergir. La clase es pura (sin CDI, sin base de datos) para que las
 * reglas se puedan probar con series sinteticas.</p>
 */
public final class CalculadoraPronostico {

    private static final Logger LOG = Logger.getLogger(CalculadoraPronostico.class);

    /** Version del contrato/pipeline, persistida en cada fila. */
    public static final String MODELO_VERSION = "canonico-v1";

    /** Dias del stock objetivo que se muestra en el reporte de salud. */
    public static final int DIAS_STOCK_OBJETIVO = 30;

    /** Racha de ceros, en dias, a partir de la cual se marca DATA_GAP. */
    private static final int DIAS_RACHA_SOSPECHOSA = 28;

    /** Minimo de dias con demanda para considerar medible la seleccion. */
    private static final int MIN_DIAS_CON_DEMANDA = 3;

    /** Minimo de dias de rejilla para no marcar INSUFFICIENT_HISTORY. */
    private static final int MIN_DIAS_HISTORIA = 14;

    private CalculadoraPronostico() {
        // Clase de utilidad.
    }

    /** Articulo del catalogo con su stock actual. */
    public record Articulo(long codigo,
                           String nombre,
                           String departamento,
                           String familia,
                           int stock) {
    }

    /** Parametros economicos de la reposicion. */
    public record Opciones(double nivelServicio,
                           double costoFaltante,
                           double costoMantenimiento,
                           int plazoDias,
                           int cantidadMinima,
                           int multiploPedido) {

        public static Opciones defecto() {
            return new Opciones(0.95, 10.0, 1.0, 7, 0, 1);
        }
    }

    /** Resultado canonico por articulo. */
    public record Resultado(Articulo articulo,
                            int horizonte,
                            List<Double> diario,
                            double demandaTotal,
                            double promedioDiarioCalendario,
                            double totalHistorico,
                            int diasHistoria,
                            int diasConDemanda,
                            double sigmaDiaria,
                            double coeficienteVariacion,
                            MetodoPronostico metodo,
                            Double mase,
                            Double sesgo,
                            Double mae,
                            Regimen regimen,
                            Set<FlagCalidad> flags,
                            String motivoFallback,
                            LocalDate historiaInicio,
                            LocalDate historiaFin,
                            double puntoReorden,
                            int stockOptimo,
                            int cantidadSugerida,
                            int diasHastaQuiebre,
                            String recomendacion,
                            double nivelServicio,
                            double intervaloInferior,
                            double intervaloSuperior,
                            boolean intervaloAproximado,
                            String metodoBaseline,
                            Double mejoraBaseline,
                            String modeloVersion) {

        public boolean despertoConfianzaMedida() {
            return mase != null;
        }
    }

    /**
     * Calcula el pronostico canonico.
     *
     * @param articulo articulo y stock actual
     * @param serie    historia diaria cero-rellenada (puede estar vacia)
     * @param horizonte dias a pronosticar
     * @param seleccion fila ganadora del backtesting para ese horizonte, o null
     * @param opciones parametros de reposicion
     * @param legacyNameMatch true si la serie vino del puente por nombre
     */
    public static Resultado calcular(Articulo articulo,
                                     List<SerieDiaria> serie,
                                     int horizonte,
                                     PrecisionPronostico seleccion,
                                     Opciones opciones,
                                     boolean legacyNameMatch) {
        return calcular(articulo, serie, horizonte, seleccion, opciones,
                legacyNameMatch, false, 0.0);
    }

    /**
     * Igual que la sobrecarga corta, con control del intervalo. El reporte
     * masivo usa {@code intervaloEmpirico = false} (aproximacion barata); la
     * ficha de un articulo puede pedir el empirico, que construye varios
     * pronosticos de origen y por eso no se hace para todo el catalogo.
     */
    public static Resultado calcular(Articulo articulo,
                                     List<SerieDiaria> serie,
                                     int horizonte,
                                     PrecisionPronostico seleccion,
                                     Opciones opciones,
                                     boolean legacyNameMatch,
                                     boolean intervaloEmpirico) {
        return calcular(articulo, serie, horizonte, seleccion, opciones,
                legacyNameMatch, intervaloEmpirico, 0.0);
    }

    /**
     * Calcula el pronostico canonico considerando ordenes de compra abiertas.
     *
     * @param ordenesCompraAbiertas unidades ya pedidas y aun no recibidas; se
     *                              suman al stock disponible para la reposicion
     *                              sin alterar el stock fisico mostrado
     */
    public static Resultado calcular(Articulo articulo,
                                     List<SerieDiaria> serie,
                                     int horizonte,
                                     PrecisionPronostico seleccion,
                                     Opciones opciones,
                                     boolean legacyNameMatch,
                                     boolean intervaloEmpirico,
                                     double ordenesCompraAbiertas) {
        int h = Math.max(1, horizonte);
        List<SerieDiaria> historia = serie == null ? List.of() : serie;
        Opciones op = opciones == null ? Opciones.defecto() : opciones;

        if (historia.isEmpty()) {
            // Camino rapido: no se invoca el motor para 250.000 articulos sin
            // historia (cada llamada registraria un aviso) ni se calculan
            // intervalos sobre una muestra vacia.
            return sinHistoria(articulo, h, op, legacyNameMatch, ordenesCompraAbiertas);
        }

        Regimen regimen = MotorPronostico.clasificar(historia);
        Eleccion eleccion = elegirMetodo(historia, regimen, seleccion);
        List<Double> diario = MotorPronostico.pronosticar(
                eleccion.metodo(), historia, h, Hiperparametros.defecto());

        double total = suma(diario);
        double totalHistorico = 0.0;
        int diasConDemanda = 0;
        int racha = 0;
        int rachaMaxima = 0;
        for (SerieDiaria punto : historia) {
            if (punto == null) {
                continue;
            }
            if (punto.cantidad() != 0.0) {
                diasConDemanda++;
                racha = 0;
            } else {
                racha++;
                rachaMaxima = Math.max(rachaMaxima, racha);
            }
            totalHistorico += punto.cantidad();
        }
        int diasHistoria = historia.size();
        double promedioDiario = diasHistoria == 0 ? 0.0 : totalHistorico / diasHistoria;
        double sigma = sigmaDiaria(historia);
        double variabilidad = promedioDiario <= 0.0 ? 0.0 : sigma / promedioDiario;

        double nivelServicio = EconomiaReposicion.nivelServicio(
                op.nivelServicio(), op.costoFaltante(), op.costoMantenimiento());
        int plazo = Math.max(1, op.plazoDias());
        double demandaPlazo = sumaHasta(diario, plazo);
        EconomiaReposicion.Reposicion reposicion = EconomiaReposicion.calcular(
                (int) Math.round(articulo.stock() + Math.max(0.0, ordenesCompraAbiertas)),
                demandaPlazo, sigma, plazo, nivelServicio,
                op.cantidadMinima(), op.multiploPedido());

        double demandaStockObjetivo = sumaHasta(diario, DIAS_STOCK_OBJETIVO);
        int stockOptimo = redondear(demandaStockObjetivo);
        int diasHastaQuiebre = diasHastaQuiebre(diario, articulo.stock());
        String recomendacion = recomendar(articulo.stock(), diasHastaQuiebre, plazo);

        IntervalosPronostico.Intervalo intervalo = intervaloEmpirico
                ? IntervalosPronostico.intervalo(eleccion.metodo(), historia, h, total,
                        nivelServicio, sigma)
                : IntervalosPronostico.aproximado(total, h, nivelServicio, sigma);

        boolean marcarIntervalo = intervaloEmpirico && intervalo.aproximado();
        Set<FlagCalidad> flags = flags(historia, regimen, articulo.stock(),
                rachaMaxima, diasConDemanda, seleccion, eleccion.motivoFallback() != null,
                legacyNameMatch, marcarIntervalo);

        LocalDate inicio = historia.isEmpty() ? null : historia.get(0).fecha();
        LocalDate fin = historia.isEmpty() ? null : historia.get(historia.size() - 1).fecha();

        Resultado resultado = new Resultado(
                articulo, h, diario, total, promedioDiario, totalHistorico,
                diasHistoria, diasConDemanda, sigma, variabilidad, eleccion.metodo(),
                seleccion == null ? null : seleccion.getMase(),
                seleccion == null ? null : seleccion.getSesgo(),
                seleccion == null ? null : seleccion.getMae(),
                regimen, Set.copyOf(flags), eleccion.motivoFallback(),
                inicio, fin, reposicion.puntoReorden(), stockOptimo,
                reposicion.cantidadPedido(), diasHastaQuiebre, recomendacion,
                nivelServicio, intervalo.limiteInferior(), intervalo.limiteSuperior(),
                intervalo.aproximado(),
                seleccion == null ? null : seleccion.getMetodoBaseline(),
                seleccion == null ? null : seleccion.getMejoraBaseline(),
                MODELO_VERSION);

        LOG.debugf("pronostico: articulo=%d horizonte=%d metodo=%s regimen=%s "
                        + "historia=[%s..%s] dias=%d conDemanda=%d total=%.2f promedio=%.4f "
                        + "mase=%s mae=%s sesgo=%s flags=%s fallback=%s",
                articulo.codigo(), h, resultado.metodo(), regimen,
                inicio, fin, diasHistoria, diasConDemanda, totalHistorico, promedioDiario,
                resultado.mase(), resultado.mae(), resultado.sesgo(), flags,
                eleccion.motivoFallback());
        return resultado;
    }

    private static Resultado sinHistoria(Articulo articulo, int horizonte, Opciones op,
                                         boolean legacyNameMatch, double ordenesCompraAbiertas) {
        double nivelServicio = EconomiaReposicion.nivelServicio(
                op.nivelServicio(), op.costoFaltante(), op.costoMantenimiento());
        EconomiaReposicion.Reposicion reposicion = EconomiaReposicion.calcular(
                (int) Math.round(articulo.stock() + Math.max(0.0, ordenesCompraAbiertas)),
                0.0, 0.0, Math.max(1, op.plazoDias()), nivelServicio,
                op.cantidadMinima(), op.multiploPedido());
        Set<FlagCalidad> flags = EnumSet.of(FlagCalidad.INSUFFICIENT_HISTORY,
                FlagCalidad.NO_EVALUATION);
        if (articulo.stock() <= 0) {
            flags.add(FlagCalidad.RECENT_STOCKOUT);
        }
        if (legacyNameMatch) {
            flags.add(FlagCalidad.LEGACY_NAME_MATCH);
        }
        int diasHastaQuiebre = articulo.stock() <= 0 ? 0 : 999;
        String recomendacion = articulo.stock() <= 0 ? "Comprar" : "OK";
        return new Resultado(articulo, horizonte,
                java.util.Collections.nCopies(horizonte, 0.0),
                0.0, 0.0, 0.0, 0, 0, 0.0, 0.0, MetodoPronostico.INGENUO,
                null, null, null, Regimen.SIN_DATOS, Set.copyOf(flags), "SIN_EVALUACION",
                null, null, reposicion.puntoReorden(), 0, reposicion.cantidadPedido(),
                diasHastaQuiebre, recomendacion, nivelServicio, 0.0, 0.0, false,
                null, null, MODELO_VERSION);
    }

    /** Metodo efectivo y por que se eligio. */
    private record Eleccion(MetodoPronostico metodo, String motivoFallback) {
    }

    private static Eleccion elegirMetodo(List<SerieDiaria> historia,
                                         Regimen regimen,
                                         PrecisionPronostico seleccion) {
        if (seleccion != null && seleccion.getMetodo() != null) {
            try {
                MetodoPronostico metodo = MetodoPronostico.valueOf(seleccion.getMetodo().trim());
                if (MotorPronostico.elegible(metodo, historia)) {
                    return new Eleccion(metodo, null);
                }
                LOG.debugf("El metodo elegido %s ya no es elegible sobre %d observaciones",
                        metodo, historia.size());
                return new Eleccion(respaldo(historia, regimen), "METODO_NO_ELEGIBLE");
            } catch (IllegalArgumentException e) {
                LOG.warnf("Metodo persistido '%s' desconocido; se usa el respaldo del regimen %s",
                        seleccion.getMetodo(), regimen);
                return new Eleccion(respaldo(historia, regimen), "METODO_DESCONOCIDO");
            }
        }
        return new Eleccion(respaldo(historia, regimen), "SIN_EVALUACION");
    }

    private static MetodoPronostico respaldo(List<SerieDiaria> historia, Regimen regimen) {
        for (MetodoPronostico candidato : MotorPronostico.candidatos(regimen)) {
            if (MotorPronostico.elegible(candidato, historia)) {
                return candidato;
            }
        }
        LOG.warnf("Ningun candidato del regimen %s es elegible sobre %d observaciones; se usa INGENUO",
                regimen, historia.size());
        return MetodoPronostico.INGENUO;
    }

    private static Set<FlagCalidad> flags(List<SerieDiaria> historia,
                                          Regimen regimen,
                                          int stock,
                                          int rachaMaxima,
                                          int diasConDemanda,
                                          PrecisionPronostico seleccion,
                                          boolean evaluacionNoUtilizable,
                                          boolean legacyNameMatch,
                                          boolean intervaloAproximado) {
        Set<FlagCalidad> flags = EnumSet.noneOf(FlagCalidad.class);
        if (historia.size() < MIN_DIAS_HISTORIA || diasConDemanda < MIN_DIAS_CON_DEMANDA) {
            flags.add(FlagCalidad.INSUFFICIENT_HISTORY);
        }
        if (regimen == Regimen.INTERMITENTE || regimen == Regimen.GRUMOSO) {
            flags.add(FlagCalidad.INTERMITTENT_DEMAND);
        }
        if (stock <= 0) {
            flags.add(FlagCalidad.RECENT_STOCKOUT);
        }
        if (rachaMaxima > DIAS_RACHA_SOSPECHOSA) {
            flags.add(FlagCalidad.DATA_GAP);
        }
        if (seleccion == null || evaluacionNoUtilizable) {
            flags.add(FlagCalidad.NO_EVALUATION);
        }
        if (seleccion != null && seleccion.getCalidadFlags() != null
                && seleccion.getCalidadFlags().contains(FlagCalidad.MODEL_UNSTABLE.name())) {
            flags.add(FlagCalidad.MODEL_UNSTABLE);
        }
        if (legacyNameMatch) {
            flags.add(FlagCalidad.LEGACY_NAME_MATCH);
        }
        if (intervaloAproximado) {
            flags.add(FlagCalidad.INTERVAL_APPROXIMATE);
        }
        return flags;
    }

    private static double suma(List<Double> valores) {
        double total = 0.0;
        for (Double valor : valores) {
            if (valor != null && Double.isFinite(valor)) {
                total += Math.max(0.0, valor);
            }
        }
        return total;
    }

    private static double sumaHasta(List<Double> valores, int dias) {
        double total = 0.0;
        int limite = Math.min(dias, valores.size());
        for (int i = 0; i < limite; i++) {
            Double valor = valores.get(i);
            if (valor != null && Double.isFinite(valor)) {
                total += Math.max(0.0, valor);
            }
        }
        return total;
    }

    private static int diasHastaQuiebre(List<Double> diario, int stock) {
        if (stock <= 0) {
            return 0;
        }
        double acumulado = 0.0;
        for (int i = 0; i < diario.size(); i++) {
            Double valor = diario.get(i);
            if (valor != null && Double.isFinite(valor)) {
                acumulado += Math.max(0.0, valor);
            }
            if (acumulado > stock) {
                return i + 1;
            }
        }
        return diario.isEmpty() ? 999 : diario.size() + 1;
    }

    private static String recomendar(int stock, int diasHastaQuiebre, int plazo) {
        if (stock <= 0) {
            return "Comprar";
        }
        if (diasHastaQuiebre <= plazo) {
            return "Comprar";
        }
        if (diasHastaQuiebre <= plazo * 2) {
            return "Pronto";
        }
        return "OK";
    }

    private static int redondear(double valor) {
        if (!Double.isFinite(valor) || valor <= 0.0) {
            return 0;
        }
        return (int) Math.round(valor);
    }

    /**
     * Desviacion tipica de la demanda diaria de la rejilla. Es una medida
     * barata y conservadora; el error de prediccion real se mide con
     * {@link IntervalosPronostico} alli donde el coste lo permite.
     */
    public static double sigmaDiaria(List<SerieDiaria> historia) {
        if (historia == null || historia.size() < 2) {
            return 0.0;
        }
        double media = 0.0;
        for (SerieDiaria punto : historia) {
            media += punto.cantidad();
        }
        media /= historia.size();
        double suma = 0.0;
        for (SerieDiaria punto : historia) {
            double d = punto.cantidad() - media;
            suma += d * d;
        }
        return Math.sqrt(suma / (historia.size() - 1));
    }
}
