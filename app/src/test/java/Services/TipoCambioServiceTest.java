package Services;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import Models.TipoCambio;

/**
 * La tasa del dólar es DIARIA: "Actualizar" desde la API no debe dejar una fila
 * duplicada cada vez que se pulsa. Antes forceRefreshFromApi insertaba siempre.
 */
@QuarkusTest
class TipoCambioServiceTest {

    @Inject
    TipoCambioService service;

    @Test
    void actualizarDosVecesElMismoDiaNoDuplicaFilas() {
        LocalDateTime hoy = LocalDateTime.now()
                .withHour(0).withMinute(0).withSecond(0).withNano(0);
        long antes = contarFilasDelDia(hoy);

        service.applyFetchedRate(tasaDelDia(hoy, "450.31000", "454.65000"));
        service.applyFetchedRate(tasaDelDia(hoy, "451.00000", "455.00000"));

        long despues = contarFilasDelDia(hoy);
        assertThat(despues - antes)
                .as("dos actualizaciones del mismo día deben crear/actualizar UNA fila, no dos")
                .isLessThanOrEqualTo(1);
    }

    private long contarFilasDelDia(LocalDateTime dia) {
        return service.listUltimos(500).stream()
                .filter(t -> t.getFecha() != null
                        && t.getFecha().toLocalDate().equals(dia.toLocalDate()))
                .count();
    }

    private static TipoCambio tasaDelDia(LocalDateTime fecha, String compra, String venta) {
        TipoCambio tc = new TipoCambio();
        tc.setFecha(fecha);
        tc.setValorCompra(new BigDecimal(compra));
        tc.setValorVenta(new BigDecimal(venta));
        return tc;
    }
}
