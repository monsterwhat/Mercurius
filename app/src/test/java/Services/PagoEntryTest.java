package Services;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import Models.PagoEntry;

/**
 * Unit tests for {@link PagoEntry#metodoPagoLabel(String)} (T6): every mapped
 * Hacienda medio-pago code, the unmapped "09" gap and arbitrary unknown codes
 * falling back to the code itself, plus the bean's field defaults.
 */
class PagoEntryTest {

    @Test
    void metodoPagoLabel_code01IsEfectivo() {
        assertThat(PagoEntry.metodoPagoLabel("01")).isEqualTo("Efectivo");
    }

    @Test
    void metodoPagoLabel_code02IsTarjeta() {
        assertThat(PagoEntry.metodoPagoLabel("02")).isEqualTo("Tarjeta");
    }

    @Test
    void metodoPagoLabel_code03IsCheque() {
        assertThat(PagoEntry.metodoPagoLabel("03")).isEqualTo("Cheque");
    }

    @Test
    void metodoPagoLabel_code04IsTransferenciaDeposito() {
        assertThat(PagoEntry.metodoPagoLabel("04")).isEqualTo("Transferencia/Depósito");
    }

    @Test
    void metodoPagoLabel_code05IsRecaudadoPorTerceros() {
        assertThat(PagoEntry.metodoPagoLabel("05")).isEqualTo("Recaudado por Terceros");
    }

    @Test
    void metodoPagoLabel_code06IsSinpeMovil() {
        assertThat(PagoEntry.metodoPagoLabel("06")).isEqualTo("SINPE Móvil");
    }

    @Test
    void metodoPagoLabel_code07IsPlataformaDigital() {
        assertThat(PagoEntry.metodoPagoLabel("07")).isEqualTo("Plataforma Digital");
    }

    @Test
    void metodoPagoLabel_code08IsBilleteraElectronica() {
        assertThat(PagoEntry.metodoPagoLabel("08")).isEqualTo("Billetera Electrónica");
    }

    @Test
    void metodoPagoLabel_code09IsNotMappedAndFallsBackToCodeItself() {
        // The switch maps 01..08, 10 and 99 — "09" intentionally falls to default.
        assertThat(PagoEntry.metodoPagoLabel("09")).isEqualTo("09");
    }

    @Test
    void metodoPagoLabel_code10IsCredito() {
        assertThat(PagoEntry.metodoPagoLabel("10")).isEqualTo("Crédito");
    }

    @Test
    void metodoPagoLabel_code99IsOtros() {
        assertThat(PagoEntry.metodoPagoLabel("99")).isEqualTo("Otros");
    }

    @Test
    void metodoPagoLabel_unknownNumericCodeReturnsCodeItself() {
        assertThat(PagoEntry.metodoPagoLabel("77")).isEqualTo("77");
    }

    @Test
    void metodoPagoLabel_unknownNonNumericCodeReturnsCodeItself() {
        assertThat(PagoEntry.metodoPagoLabel("ABC")).isEqualTo("ABC");
    }

    @Test
    void pagoEntry_defaultsAreEfectivoAndZeroMonto() {
        PagoEntry entry = new PagoEntry();
        assertThat(entry.getMetodoPago()).isEqualTo("01");
        assertThat(entry.getMonto()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
