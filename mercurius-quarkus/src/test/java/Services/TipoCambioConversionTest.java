package Services;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import Models.TipoCambio;

/**
 * Unit tests for the conversion math actually exposed by {@link TipoCambioService} (T6).
 * <p>
 * Scope note: this class exposes NO public colones&harr;d&oacute;lares arithmetic
 * (currency conversion lives in the controllers/cart flows, not here). The pure
 * conversion math the class owns is:
 * <ul>
 *   <li>{@code parseTipoCambio(String)} &mdash; Hacienda API JSON &rarr; {@link TipoCambio},
 *       normalizing both rates with {@code setScale(5, HALF_UP)}</li>
 *   <li>{@code parseFechaVenta(JsonNode)} &mdash; date-string normalization
 *       ({@code yyyy-MM-dd}, space-separated and ISO forms)</li>
 * </ul>
 * Both are private with no public seam that avoids a real HTTP call, so they are
 * exercised via reflection. Covered boundary values: zero, large and fractional
 * rates, plus HALF_UP rounding at 5dp in both directions.
 */
class TipoCambioConversionTest {

    private final TipoCambioService service = new TipoCambioService();

    private TipoCambio parseTipoCambio(String json) throws Exception {
        Method m = TipoCambioService.class.getDeclaredMethod("parseTipoCambio", String.class);
        m.setAccessible(true);
        return (TipoCambio) m.invoke(service, json);
    }

    private LocalDateTime parseFechaVenta(String ventaJson) throws Exception {
        JsonNode ventaNode = new ObjectMapper().readTree(ventaJson);
        Method m = TipoCambioService.class.getDeclaredMethod("parseFechaVenta", JsonNode.class);
        m.setAccessible(true);
        return (LocalDateTime) m.invoke(service, ventaNode);
    }

    // --- parseTipoCambio: rate conversion/boundary values ---

    @Test
    void parseTipoCambio_zeroRatesNormalizeToZeroWithFullScale() throws Exception {
        TipoCambio tc = parseTipoCambio(
                "{\"venta\":{\"valor\":\"0\",\"fecha\":\"2026-07-15\"},\"compra\":{\"valor\":\"0\"}}");

        assertThat(tc.getValorVenta()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(tc.getValorCompra()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(tc.getValorVenta().scale()).isEqualTo(5);
        assertThat(tc.getValorCompra().scale()).isEqualTo(5);
    }

    @Test
    void parseTipoCambio_fractionalRatesPreservedAndScaledTo5dp() throws Exception {
        TipoCambio tc = parseTipoCambio(
                "{\"venta\":{\"valor\":\"549.52\",\"fecha\":\"2026-07-15 08:30:00\"},\"compra\":{\"valor\":\"511.1875\"}}");

        assertThat(tc.getValorVenta()).isEqualByComparingTo(new BigDecimal("549.52"));
        assertThat(tc.getValorCompra()).isEqualByComparingTo(new BigDecimal("511.1875"));
        assertThat(tc.getValorVenta().scale()).isEqualTo(5);
        assertThat(tc.getValorCompra().scale()).isEqualTo(5);
    }

    @Test
    void parseTipoCambio_largeRatesSurviveWithoutOverflow() throws Exception {
        TipoCambio tc = parseTipoCambio(
                "{\"venta\":{\"valor\":\"99999999.99\",\"fecha\":\"2026-07-15\"},\"compra\":{\"valor\":\"88888888.88\"}}");

        assertThat(tc.getValorVenta()).isEqualByComparingTo(new BigDecimal("99999999.99"));
        assertThat(tc.getValorCompra()).isEqualByComparingTo(new BigDecimal("88888888.88"));
    }

    @Test
    void parseTipoCambio_roundingHalfUpAt5dpRoundsUpOnExactHalf() throws Exception {
        // 1.000005 -> 5dp HALF_UP -> 1.00001 (exact half of the discarded ulp rounds away from zero)
        TipoCambio tc = parseTipoCambio(
                "{\"venta\":{\"valor\":\"1.000005\",\"fecha\":\"2026-07-15\"},\"compra\":{\"valor\":\"0\"}}");

        assertThat(tc.getValorVenta()).isEqualByComparingTo(new BigDecimal("1.00001"));
        assertThat(tc.getValorVenta().scale()).isEqualTo(5);
    }

    @Test
    void parseTipoCambio_roundingHalfUpAt5dpKeepsValueBelowHalfUlp() throws Exception {
        // 1.000004 -> 5dp HALF_UP -> 1.00000 (discarded fraction below half ulp rounds down)
        TipoCambio tc = parseTipoCambio(
                "{\"venta\":{\"valor\":\"1.000004\",\"fecha\":\"2026-07-15\"},\"compra\":{\"valor\":\"2.000001\"}}");

        assertThat(tc.getValorVenta()).isEqualByComparingTo(new BigDecimal("1"));
        assertThat(tc.getValorCompra()).isEqualByComparingTo(new BigDecimal("2.00000"));
    }

    @Test
    void parseTipoCambio_fechaTakenFromVentaNode() throws Exception {
        TipoCambio tc = parseTipoCambio(
                "{\"venta\":{\"valor\":\"600.10\",\"fecha\":\"2026-08-25\"},\"compra\":{\"valor\":\"590.20\"}}");

        assertThat(tc.getFecha()).isEqualTo(LocalDateTime.of(2026, 8, 25, 0, 0, 0));
        assertThat(tc.getValorVenta()).isEqualByComparingTo(new BigDecimal("600.10"));
        assertThat(tc.getValorCompra()).isEqualByComparingTo(new BigDecimal("590.20"));
    }

    // --- parseFechaVenta: accepted date formats ---

    @Test
    void parseFechaVenta_shortDateOnlyFormGetsMidnightTime() throws Exception {
        LocalDateTime fecha = parseFechaVenta("{\"fecha\":\"2026-07-15\"}");
        assertThat(fecha).isEqualTo(LocalDateTime.of(2026, 7, 15, 0, 0, 0));
    }

    @Test
    void parseFechaVenta_spaceSeparatedDateTimeFormIsNormalized() throws Exception {
        LocalDateTime fecha = parseFechaVenta("{\"fecha\":\"2026-07-15 14:30:00\"}");
        assertThat(fecha).isEqualTo(LocalDateTime.of(2026, 7, 15, 14, 30, 0));
    }

    @Test
    void parseFechaVenta_isoTFormPassesThrough() throws Exception {
        LocalDateTime fecha = parseFechaVenta("{\"fecha\":\"2026-07-15T05:30:45\"}");
        assertThat(fecha).isEqualTo(LocalDateTime.of(2026, 7, 15, 5, 30, 45));
    }
}
