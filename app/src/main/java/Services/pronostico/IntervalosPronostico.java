package Services.pronostico;

import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Intervalos de prediccion empiricos y su evaluacion de cobertura.
 *
 * <p>La aproximacion clasica {@code punto +- z * sigma * sqrt(h)} supone
 * errores independientes y de varianza constante. Con demanda intermitente,
 * tendencia o estacionalidad esa suposicion falla y el intervalo puede
 * prometer una cobertura que no entrega. Esta clase mide la cobertura contra
 * resultados historicos reales y, cuando hay suficientes origenes, construye
 * el intervalo con los cuantiles empiricos del error <em>a ese horizonte</em>.</p>
 *
 * <p><b>Sin fuga de informacion.</b> Para evaluar el origen {@code i}, la
 * distribucion de errores se calcula con los demas origenes, nunca con el
 * propio. Un intervalo construido con el error que luego se comprueba siempre
 * cubriria y la metrica no valdria nada.</p>
 *
 * <p><b>Cuando no hay muestra.</b> Con menos de {@value #MIN_MUESTRAS_EMPIRICAS}
 * errores por horizonte se devuelve la aproximacion normal y se marca
 * {@link FlagCalidad#INTERVAL_APPROXIMATE}. La cobertura medida sigue siendo
 * real: lo aproximado es el metodo, no la comprobacion.</p>
 */
public final class IntervalosPronostico {

    private static final Logger LOG = Logger.getLogger(IntervalosPronostico.class);

    /** Errores de horizonte completos minimos para usar cuantiles empiricos. */
    public static final int MIN_MUESTRAS_EMPIRICAS = 5;

    /** Ventana de residuos de un paso para la sigma de respaldo. */
    public static final int VENTANA_RESIDUOS = 60;

    private IntervalosPronostico() {
        // Clase de utilidad.
    }

    /** Intervalo de prediccion para un total pronosticado. */
    public record Intervalo(double limiteInferior,
                            double limiteSuperior,
                            double limiteInferiorAproximado,
                            double limiteSuperiorAproximado,
                            String metodo,
                            int muestras) {

        public boolean aproximado() {
            return "APROXIMADO".equals(metodo);
        }

        public double ancho() {
            return limiteSuperior - limiteInferior;
        }
    }

    /** Cobertura empirica de un metodo y horizonte. */
    public record Cobertura(int horizonte,
                            double nivelObjetivo,
                            double cobertura,
                            double errorCobertura,
                            double anchoMedio,
                            double tasaSubpronostico,
                            double tasaSobrepronostico,
                            double wis,
                            int muestras,
                            String metodoIntervalo) {
    }

    /** Error total de un origen: {@code suma(real) - suma(pronostico)}. */
    private record ErrorOrigen(double error, double real, double pronostico) {
    }

    /**
     * Errores de horizonte completo en origenes moviles. Solo se usan origenes
     * con {@code horizonte} observaciones reales por delante; un horizonte
     * truncado mediria un error artificial.
     */
    public static List<Double> erroresHorizonte(MetodoPronostico metodo,
                                                List<SerieDiaria> historia,
                                                int horizonte,
                                                int maxOrigenes) {
        return errores(metodo, historia, horizonte, maxOrigenes).stream()
                .map(ErrorOrigen::error)
                .toList();
    }

    private static List<ErrorOrigen> errores(MetodoPronostico metodo,
                                             List<SerieDiaria> historia,
                                             int horizonte,
                                             int maxOrigenes) {
        if (historia == null || metodo == null || horizonte <= 0 || historia.size() <= horizonte + 1) {
            return List.of();
        }
        int pedidos = Math.max(1, maxOrigenes);
        int primero = Math.max(1, historia.size() - horizonte - pedidos);
        List<ErrorOrigen> errores = new ArrayList<>();
        for (int origen = primero; origen + horizonte < historia.size(); origen++) {
            List<SerieDiaria> entrenamiento = List.copyOf(historia.subList(0, origen + 1));
            if (!MotorPronostico.elegible(metodo, entrenamiento)) {
                continue;
            }
            List<Double> pronostico;
            try {
                pronostico = MotorPronostico.pronosticar(
                        metodo, entrenamiento, horizonte, Hiperparametros.defecto());
            } catch (IllegalArgumentException e) {
                LOG.debugf("Intervalos: %s no elegible en el origen %d (%s)", metodo, origen, e.getMessage());
                continue;
            }
            if (pronostico == null || pronostico.size() < horizonte) {
                continue;
            }
            double real = 0.0;
            double previsto = 0.0;
            for (int h = 0; h < horizonte; h++) {
                real += historia.get(origen + 1 + h).cantidad();
                previsto += pronostico.get(h);
            }
            errores.add(new ErrorOrigen(real - previsto, real, previsto));
        }
        return errores;
    }

    /**
     * Intervalo aproximado sin evaluar la distribucion empirica de errores.
     * Es barato y sirve para el reporte masivo, donde construir 12 pronosticos
     * por articulo no es viable; el resultado se marca como aproximado.
     */
    public static Intervalo aproximado(double punto, int horizonte, double nivel, double sigmaUnPaso) {
        double objetivo = acotarNivel(nivel);
        int h = Math.max(1, horizonte);
        double z = EconomiaReposicion.z(objetivo);
        double margen = z * Math.max(0.0, sigmaUnPaso) * Math.sqrt(h);
        double inferior = Math.max(0.0, punto - margen);
        double superior = Math.max(inferior, punto + margen);
        return new Intervalo(inferior, superior, inferior, superior, "APROXIMADO", 0);
    }

    /**
     * Intervalo de un total pronosticado.
     *
     * @param metodo       metodo que produjo el pronostico
     * @param historia     historia diaria usada para pronosticar
     * @param horizonte    dias del total pronosticado
     * @param punto        total pronosticado
     * @param nivel        cobertura objetivo en (0, 1)
     * @param sigmaUnPaso  sigma de residuos de un paso (respaldo)
     */
    public static Intervalo intervalo(MetodoPronostico metodo,
                                      List<SerieDiaria> historia,
                                      int horizonte,
                                      double punto,
                                      double nivel,
                                      double sigmaUnPaso) {
        double objetivo = acotarNivel(nivel);
        int h = Math.max(1, horizonte);
        List<Double> errores = erroresHorizonte(metodo, historia, h, 12);
        double z = EconomiaReposicion.z(objetivo);
        double aproximadoInferior = Math.max(0.0, punto - z * sigmaUnPaso * Math.sqrt(h));
        double aproximadoSuperior = Math.max(aproximadoInferior, punto + z * sigmaUnPaso * Math.sqrt(h));

        if (errores.size() < MIN_MUESTRAS_EMPIRICAS) {
            return new Intervalo(aproximadoInferior, aproximadoSuperior,
                    aproximadoInferior, aproximadoSuperior, "APROXIMADO", errores.size());
        }

        double alfa = 1.0 - objetivo;
        double qInferior = cuantil(errores, alfa / 2.0);
        double qSuperior = cuantil(errores, 1.0 - alfa / 2.0);
        double inferior = Math.max(0.0, punto + qInferior);
        double superior = Math.max(inferior, punto + qSuperior);
        return new Intervalo(inferior, superior,
                aproximadoInferior, aproximadoSuperior, "EMPIRICO", errores.size());
    }

    /**
     * Evalua la cobertura de un metodo en varios horizontes y niveles. Para
     * cada origen se construye el intervalo con los errores de los demas
     * origenes (dejando fuera el que se comprueba) y se cuenta si el total real
     * cae dentro.
     */
    public static List<Cobertura> evaluarCobertura(MetodoPronostico metodo,
                                                   List<SerieDiaria> historia,
                                                   List<Integer> horizontes,
                                                   List<Double> niveles,
                                                   int maxOrigenes) {
        List<Cobertura> resultados = new ArrayList<>();
        if (metodo == null || historia == null || historia.isEmpty()) {
            return resultados;
        }
        for (int horizonte : horizontes) {
            int h = Math.max(1, horizonte);
            List<ErrorOrigen> errores = errores(metodo, historia, h, maxOrigenes);
            for (double nivel : niveles) {
                resultados.add(cobertura(metodo, historia, h, nivel, errores));
            }
        }
        return List.copyOf(resultados);
    }

    private static Cobertura cobertura(MetodoPronostico metodo,
                                       List<SerieDiaria> historia,
                                       int horizonte,
                                       double nivel,
                                       List<ErrorOrigen> errores) {
        double objetivo = acotarNivel(nivel);
        if (errores.size() < 2) {
            return new Cobertura(horizonte, objetivo, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, errores.size(), "SIN_MUESTRA");
        }
        double sigma = Math.sqrt(varianza(errores) / horizonte);
        double alfa = 1.0 - objetivo;
        int dentro = 0;
        int sub = 0;
        int sobre = 0;
        double sumaAncho = 0.0;
        double sumaWis = 0.0;
        for (int i = 0; i < errores.size(); i++) {
            ErrorOrigen evaluado = errores.get(i);
            List<Double> otros = new ArrayList<>(errores.size() - 1);
            for (int j = 0; j < errores.size(); j++) {
                if (j != i) {
                    otros.add(errores.get(j).error());
                }
            }
            Intervalo intervalo = intervaloDesdeErrores(
                    evaluado.pronostico(), otros, alfa, sigma, nivel, horizonte);
            sumaAncho += intervalo.ancho();
            double real = evaluado.real();
            if (real >= intervalo.limiteInferior() && real <= intervalo.limiteSuperior()) {
                dentro++;
            }
            if (real > intervalo.limiteSuperior()) {
                sub++;
            }
            if (real < intervalo.limiteInferior()) {
                sobre++;
            }
            sumaWis += intervaloScore(real, intervalo, alfa);
        }
        int n = errores.size();
        double cobertura = (double) dentro / n;
        return new Cobertura(horizonte, objetivo, cobertura, cobertura - objetivo,
                sumaAncho / n, (double) sub / n, (double) sobre / n,
                sumaWis / n, n, otros(errores) >= MIN_MUESTRAS_EMPIRICAS ? "EMPIRICO" : "APROXIMADO");
    }

    private static int otros(List<ErrorOrigen> errores) {
        return errores.size() - 1;
    }

    private static Intervalo intervaloDesdeErrores(double punto,
                                                   List<Double> errores,
                                                   double alfa,
                                                   double sigma,
                                                   double nivel,
                                                   int horizonte) {
        if (errores.size() < MIN_MUESTRAS_EMPIRICAS - 1) {
            double z = EconomiaReposicion.z(nivel);
            double margen = z * sigma * Math.sqrt(Math.max(1, horizonte));
            double inferior = Math.max(0.0, punto - margen);
            return new Intervalo(inferior, Math.max(inferior, punto + margen),
                    inferior, Math.max(inferior, punto + margen), "APROXIMADO", errores.size());
        }
        double inferior = Math.max(0.0, punto + cuantil(errores, alfa / 2.0));
        double superior = Math.max(inferior, punto + cuantil(errores, 1.0 - alfa / 2.0));
        return new Intervalo(inferior, superior, inferior, superior, "EMPIRICO", errores.size());
    }

    /** Cuantil con interpolacion lineal; lista vacia devuelve 0. */
    public static double cuantil(List<Double> valores, double probabilidad) {
        if (valores == null || valores.isEmpty()) {
            return 0.0;
        }
        List<Double> ordenados = new ArrayList<>(valores);
        Collections.sort(ordenados);
        double p = Math.min(1.0, Math.max(0.0, probabilidad));
        double posicion = p * (ordenados.size() - 1);
        int inferior = (int) Math.floor(posicion);
        int superior = (int) Math.ceil(posicion);
        if (inferior == superior) {
            return ordenados.get(inferior);
        }
        double peso = posicion - inferior;
        return ordenados.get(inferior) * (1.0 - peso) + ordenados.get(superior) * peso;
    }

    /**
     * Interval score de un intervalo central. Menor es mejor; penaliza ancho y
     * fallos de cobertura con el peso de la cola.
     */
    public static double intervaloScore(double real, Intervalo intervalo, double alfa) {
        double ancho = intervalo.ancho();
        double penalizacion = 0.0;
        if (real < intervalo.limiteInferior()) {
            penalizacion = (2.0 / alfa) * (intervalo.limiteInferior() - real);
        } else if (real > intervalo.limiteSuperior()) {
            penalizacion = (2.0 / alfa) * (real - intervalo.limiteSuperior());
        }
        return ancho + penalizacion;
    }

    /** Sigma de residuos de un paso adelante del metodo sobre la historia. */
    public static double sigmaUnPaso(MetodoPronostico metodo, List<SerieDiaria> historia) {
        if (historia == null || historia.size() < 3) {
            return 0.0;
        }
        int total = historia.size();
        int ventana = Math.min(VENTANA_RESIDUOS, total);
        List<Double> residuos = new ArrayList<>();
        for (int t = Math.max(1, total - ventana); t < total; t++) {
            List<SerieDiaria> entrenamiento = List.copyOf(historia.subList(0, t));
            if (!MotorPronostico.elegible(metodo, entrenamiento)) {
                continue;
            }
            List<Double> pronostico = MotorPronostico.pronosticar(
                    metodo, entrenamiento, 1, Hiperparametros.defecto());
            if (pronostico == null || pronostico.isEmpty() || !Double.isFinite(pronostico.get(0))) {
                continue;
            }
            residuos.add(historia.get(t).cantidad() - pronostico.get(0));
        }
        return desviacion(residuos);
    }

    /** Desviacion tipica muestral (n - 1); 0 con menos de dos valores. */
    public static double desviacion(List<Double> valores) {
        if (valores == null || valores.size() < 2) {
            return 0.0;
        }
        double media = 0.0;
        for (double valor : valores) {
            media += valor;
        }
        media /= valores.size();
        double suma = 0.0;
        for (double valor : valores) {
            double d = valor - media;
            suma += d * d;
        }
        return Math.sqrt(suma / (valores.size() - 1));
    }

    private static double varianza(List<ErrorOrigen> errores) {
        if (errores.size() < 2) {
            return 0.0;
        }
        double media = 0.0;
        for (ErrorOrigen error : errores) {
            media += error.error();
        }
        media /= errores.size();
        double suma = 0.0;
        for (ErrorOrigen error : errores) {
            double d = error.error() - media;
            suma += d * d;
        }
        return suma / (errores.size() - 1);
    }

    private static double acotarNivel(double nivel) {
        if (!Double.isFinite(nivel) || nivel <= 0.0 || nivel >= 1.0) {
            LOG.warnf("Nivel de cobertura %s fuera de (0, 1); se usa 0.95", nivel);
            return 0.95;
        }
        return nivel;
    }
}
