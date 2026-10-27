package Utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Contrato de formato visible. Los montos deben salir con dos decimales,
 * separadores es-CR y el símbolo de colones; los porcentajes ya vienen en
 * puntos y no deben multiplicarse por 100.
 */
class FormatoVistaTest {

    private static final String NBSP = "\u00A0";

    @Test
    void monedaFormateaDosDecimalesYSeparadores() {
        assertThat(FormatoVista.moneda(new BigDecimal("67552787.745557")))
                .isEqualTo("₡67" + NBSP + "552" + NBSP + "787,75");
        assertThat(FormatoVista.moneda(new BigDecimal("3250.00000")))
                .isEqualTo("₡3" + NBSP + "250,00");
    }

    @Test
    void monedaAceptaNumerosYTextoNumerico() {
        assertThat(FormatoVista.moneda(1234)).isEqualTo("₡1" + NBSP + "234,00");
        assertThat(FormatoVista.moneda("12.5")).isEqualTo("₡12,50");
    }

    @Test
    void valoresAusentesNoRompenLaPagina() {
        assertThat(FormatoVista.moneda(null)).isEqualTo("—");
        assertThat(FormatoVista.numero(null)).isEqualTo("—");
        assertThat(FormatoVista.porcentaje(null)).isEqualTo("—");
        assertThat(FormatoVista.moneda("₡1.234,56")).isEqualTo("₡1.234,56");
    }

    @Test
    void monedaColocaElSignoAntesDelSimbolo() {
        assertThat(FormatoVista.moneda(new BigDecimal("-50"))).isEqualTo("-₡50,00");
        assertThat(FormatoVista.moneda(new BigDecimal("-1234.5")))
                .isEqualTo("-₡1" + NBSP + "234,50");
        assertThat(FormatoVista.moneda(new BigDecimal("-0.001"))).isEqualTo("₡0,00");
    }

    @Test
    void porcentajeNoMultiplicaPorCien() {
        assertThat(FormatoVista.porcentaje(new BigDecimal("6.83"))).isEqualTo("6,83%");
        assertThat(FormatoVista.porcentaje(new BigDecimal("20.00"))).isEqualTo("20%");
    }

    @Test
    void numeroAgrupaYRecortaDecimales() {
        assertThat(FormatoVista.numero(5596)).isEqualTo("5" + NBSP + "596");
        assertThat(FormatoVista.numero(new BigDecimal("12.3456"))).isEqualTo("12,35");
    }
}
