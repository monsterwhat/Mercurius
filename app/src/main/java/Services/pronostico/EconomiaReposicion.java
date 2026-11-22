package Services.pronostico;

import org.jboss.logging.Logger;

/**
 * Economia de reposicion: convierte demanda pronosticada e incertidumbre en
 * punto de reorden y cantidad de pedido.
 *
 * <p><b>Por que no basta "demanda del horizonte menos stock".</b> Esa formula
 * ignora el plazo de reposicion, el costo relativo de faltante y sobrante, y
 * las restricciones del proveedor (minimo y multiplo). El punto de reorden es
 * la demanda esperada durante el plazo <em>mas</em> el stock de seguridad; la
 * cantidad de pedido es lo que falta para cubrir ese objetivo, redondeado a la
 * unidad de compra.</p>
 *
 * <p><b>Nivel de servicio.</b> El nivel configurado es un suelo. Si los costos
 * estan definidos, el nivel se eleva hasta la razon critica
 * {@code costoFaltante / (costoFaltante + costoMantenimiento)}, que es el
 * nivel que minimiza el costo esperado de faltante y mantenimiento. Un
 * faltante mas caro que mantener produce un nivel de servicio mas alto; con
 * costos iguales, 0.50, y por tanto manda el suelo configurado.</p>
 *
 * <p>La cola normal se resuelve con la inversa de Acklam (precision relativa
 * cercana a 1e-9), no con una tabla de cuatro valores: el nivel de servicio
 * ahora se deriva de costos y puede caer en cualquier punto de (0, 1).</p>
 */
public final class EconomiaReposicion {

    private static final Logger LOG = Logger.getLogger(EconomiaReposicion.class);

    /** Nivel de servicio minimo con el que se trabaja; por debajo no tiene sentido operativo. */
    public static final double NIVEL_MINIMO = 0.50;

    /** Techo: la normal tipada nunca debe usarse para prometer certeza absoluta. */
    public static final double NIVEL_MAXIMO = 0.999;

    private EconomiaReposicion() {
        // Clase de utilidad.
    }

    /** Parametros de negocio de la reposicion. */
    public record Parametros(double costoFaltante,
                             double costoMantenimiento,
                             int plazoDias,
                             int cantidadMinima,
                             int multiploPedido) {

        public Parametros {
            plazoDias = Math.max(1, plazoDias);
            cantidadMinima = Math.max(0, cantidadMinima);
            multiploPedido = Math.max(1, multiploPedido);
        }

        public static Parametros defecto() {
            return new Parametros(10.0, 1.0, 7, 0, 1);
        }
    }

    /** Resultado de la politica de reposicion. */
    public record Reposicion(double puntoReorden,
                             int cantidadPedido,
                             double nivelServicio,
                             double stockSeguridad,
                             double demandaPlazo,
                             double z) {
    }

    /**
     * Nivel de servicio efectivo: el mayor entre el suelo configurado y la
     * razon critica de costos. Valores no representativos se registran y se
     * resuelven con el suelo.
     */
    public static double nivelServicio(double nivelConfigurado,
                                       double costoFaltante,
                                       double costoMantenimiento) {
        double suelo = Double.isFinite(nivelConfigurado) && nivelConfigurado > 0.0
                && nivelConfigurado < 1.0 ? nivelConfigurado : 0.95;
        if (!(Double.isFinite(nivelConfigurado) && nivelConfigurado > 0.0 && nivelConfigurado < 1.0)) {
            LOG.warnf("Nivel de servicio configurado %s fuera de (0, 1); se usa %.2f",
                    nivelConfigurado, suelo);
        }
        if (costoFaltante < 0.0 || costoMantenimiento < 0.0
                || !Double.isFinite(costoFaltante) || !Double.isFinite(costoMantenimiento)) {
            LOG.warnf("Costos de faltante/mantenimiento invalidos (%s, %s); se usa solo el nivel configurado",
                    costoFaltante, costoMantenimiento);
            return acotar(suelo);
        }
        double suma = costoFaltante + costoMantenimiento;
        if (suma <= 0.0) {
            return acotar(suelo);
        }
        double razonCritica = costoFaltante / suma;
        return acotar(Math.max(suelo, razonCritica));
    }

    /** Inversa de la normal estandar; {@code nivel} se acota a (0, 1). */
    public static double z(double nivel) {
        double p = acotar(nivel);
        return inversaNormal(p);
    }

    /**
     * Punto de reorden y cantidad de pedido.
     *
     * @param stockActual    unidades disponibles hoy
     * @param demandaPlazo   demanda pronosticada durante el plazo de reposicion
     * @param sigmaDiaria    desviacion tipica diaria del error (o de la demanda,
     *                       si no hay residuos); se usa como aproximacion
     * @param plazoDias      dias de plazo de reposicion
     * @param nivelServicio  nivel efectivo en (0, 1)
     * @param minimo         cantidad minima de pedido (0 = sin minimo)
     * @param multiplo       multiplo de compra (1 = sin restriccion)
     * @return reposicion con punto de reorden, cantidad y desglose
     */
    public static Reposicion calcular(int stockActual,
                                      double demandaPlazo,
                                      double sigmaDiaria,
                                      int plazoDias,
                                      double nivelServicio,
                                      int minimo,
                                      int multiplo) {
        int plazo = Math.max(1, plazoDias);
        double nivel = acotar(nivelServicio);
        double z = z(nivel);
        double sigma = Math.max(0.0, sigmaDiaria);
        double seguridad = z * sigma * Math.sqrt(plazo);
        double punto = Math.max(0.0, demandaPlazo + seguridad);
        double faltante = punto - Math.max(0, stockActual);
        int cantidad = faltante <= 0.0 ? 0 : redondearPedido(faltante, minimo, multiplo);
        return new Reposicion(punto, cantidad, nivel, seguridad, Math.max(0.0, demandaPlazo), z);
    }

    /**
     * Redondea al alza al multiplo y aplica el minimo. Un pedido de cero sigue
     * siendo cero: el minimo del proveedor no obliga a comprar si no hace falta.
     */
    public static int redondearPedido(double faltante, int minimo, int multiplo) {
        if (faltante <= 0.0 || !Double.isFinite(faltante)) {
            return 0;
        }
        int paso = Math.max(1, multiplo);
        int objetivo = Math.max(Math.max(0, minimo), (int) Math.ceil(faltante));
        return ((objetivo + paso - 1) / paso) * paso;
    }

    private static double acotar(double nivel) {
        if (!Double.isFinite(nivel)) {
            LOG.warnf("Nivel de servicio no finito (%s); se usa 0.95", nivel);
            return 0.95;
        }
        return Math.min(NIVEL_MAXIMO, Math.max(NIVEL_MINIMO, nivel));
    }

    /**
     * Inversa de la normal estandar por el algoritmo de Acklam. Error relativo
     * menor que 1.15e-9 en todo el rango; suficiente para un nivel de servicio
     * que luego se redondea a unidades enteras.
     */
    private static double inversaNormal(double p) {
        if (p <= 0.0) {
            return Double.NEGATIVE_INFINITY;
        }
        if (p >= 1.0) {
            return Double.POSITIVE_INFINITY;
        }
        // Coeficientes de Acklam.
        double[] a = {-3.969683028665376e+01, 2.209460984245205e+02,
                -2.759285104469687e+02, 1.383577518672690e+02,
                -3.066479806614716e+01, 2.506628277459239e+00};
        double[] b = {-5.447609879822406e+01, 1.615858368580409e+02,
                -1.556989798598866e+02, 6.680131188771972e+01,
                -1.328068155288572e+01};
        double[] c = {-7.784894002430293e-03, -3.223964580411365e-01,
                -2.400758277161838e+00, -2.549732539343734e+00,
                4.374664141464968e+00, 2.938163982698783e+00};
        double[] d = {7.784695709041462e-03, 3.224671290700398e-01,
                2.445134137142996e+00, 3.754408661907416e+00};

        double pBaja = 0.02425;
        double pAlta = 1.0 - pBaja;

        if (p < pBaja) {
            double q = Math.sqrt(-2.0 * Math.log(p));
            return (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                    / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1.0);
        }
        if (p > pAlta) {
            double q = Math.sqrt(-2.0 * Math.log(1.0 - p));
            return -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                    / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1.0);
        }
        double q = p - 0.5;
        double r = q * q;
        return (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q
                / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1.0);
    }
}
