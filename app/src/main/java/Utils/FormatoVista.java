package Utils;

import io.quarkus.qute.TemplateExtension;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;
import org.jboss.logging.Logger;

/**
 * Formato de valores visibles para las plantillas Qute.
 *
 * <p>Las plantillas reciben {@link BigDecimal} crudos desde los servicios y los
 * imprimían tal cual: {@code 67552787.7455570000000000000}, {@code 3250.00000}.
 * Eso no es un problema de estilos, es un dato que el operador no puede leer y
 * que además desborda las tarjetas en móvil. Este namespace centraliza el
 * formato para que todas las pantallas usen la misma convención que el front
 * ({@code web/chart-config.js}): locale {@code es-CR}, dos decimales y el
 * símbolo {@code ₡}.</p>
 *
 * <p>Uso en Qute: {@code {fmt:moneda(fila.total)}},
 * {@code {fmt:numero(registros)}}, {@code {fmt:porcentaje(fila.margen)}}.</p>
 *
 * <p>Los métodos aceptan {@link Object} a propósito: los modelos de las
 * páginas mezclan {@code BigDecimal}, {@code Integer} y valores ya
 * convertidos a texto. Si el valor no es numérico se devuelve sin tocar en vez
 * de fallar la renderización completa de la página.</p>
 */
@TemplateExtension(namespace = "fmt")
public final class FormatoVista {

    private static final Logger LOG = Logger.getLogger(FormatoVista.class);
    private static final Locale ES_CR = Locale.forLanguageTag("es-CR");
    private static final String SIN_DATO = "—";

    private FormatoVista() {
    }

    /** Monto en colones: {@code ₡1 234,56}. */
    public static String moneda(Object valor) {
        BigDecimal numero = comoDecimal(valor, "moneda");
        if (numero == null) {
            return valor == null ? SIN_DATO : String.valueOf(valor);
        }
        NumberFormat formato = NumberFormat.getNumberInstance(ES_CR);
        formato.setMinimumFractionDigits(2);
        formato.setMaximumFractionDigits(2);
        formato.setRoundingMode(RoundingMode.HALF_UP);
        BigDecimal redondeado = numero.setScale(2, RoundingMode.HALF_UP);
        String monto = formato.format(redondeado.abs());
        return redondeado.signum() < 0 ? "-₡" + monto : "₡" + monto;
    }

    /** Número sin símbolo ni decimales forzados (máximo dos). */
    public static String numero(Object valor) {
        BigDecimal numero = comoDecimal(valor, "numero");
        if (numero == null) {
            return valor == null ? SIN_DATO : String.valueOf(valor);
        }
        NumberFormat formato = NumberFormat.getNumberInstance(ES_CR);
        formato.setMinimumFractionDigits(0);
        formato.setMaximumFractionDigits(2);
        formato.setRoundingMode(RoundingMode.HALF_UP);
        return formato.format(numero);
    }

    /** Porcentaje ya expresado en puntos (6.83 → {@code 6,83%}). */
    public static String porcentaje(Object valor) {
        BigDecimal numero = comoDecimal(valor, "porcentaje");
        if (numero == null) {
            return valor == null ? SIN_DATO : String.valueOf(valor);
        }
        NumberFormat formato = NumberFormat.getNumberInstance(ES_CR);
        formato.setMinimumFractionDigits(0);
        formato.setMaximumFractionDigits(2);
        formato.setRoundingMode(RoundingMode.HALF_UP);
        return formato.format(numero) + "%";
    }

    private static BigDecimal comoDecimal(Object valor, String uso) {
        if (valor == null) {
            return null;
        }
        if (valor instanceof BigDecimal decimal) {
            return decimal;
        }
        if (valor instanceof Number numero) {
            return new BigDecimal(numero.toString());
        }
        String texto = String.valueOf(valor).trim();
        if (texto.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(texto);
        } catch (NumberFormatException e) {
            // Un valor ya formateado ("₡1.234,56") no se vuelve a formatear:
            // se devuelve tal cual. Se registra para poder detectar modelos
            // que lleguen con texto donde se esperaba un número.
            LOG.debugf("Valor no numérico en fmt:%s, se deja sin formato: %s", uso, texto);
            return null;
        }
    }
}
