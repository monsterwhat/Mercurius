package Services;

import static org.assertj.core.api.Assertions.assertThat;

import Models.Inventario;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * La tabla de Gestión de Inventario pagina en PostgreSQL. Este test fija:
 * <ul>
 *   <li>el orden por defecto (fechaMovimiento más reciente primero),</li>
 *   <li>el LIMIT/OFFSET de la página,</li>
 *   <li>el filtro global {@code q} y el conteo,</li>
 *   <li>el orden explícito ascendente.</li>
 * </ul>
 */
@QuarkusTest
class InventarioPageQueryTest {

    private static final String TIPO = "QAORDER-PAGE-QUERY";

    @Inject
    InventarioService service;

    @Inject
    EntityManager em;

    @Test
    void defaultOrderIsNewestFechaMovimientoAndPagesInSql() {
        long base = System.currentTimeMillis() - 3_600_000L;
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                for (int i = 0; i < 3; i++) {
                    Inventario m = new Inventario();
                    m.setCantidad(BigDecimal.valueOf(i + 1));
                    m.setTipoMovimiento(TIPO);
                    m.setFechaMovimiento(new Date(base + i * 60_000L));
                    m.setStatus(true);
                    m.setProcessed(true);
                    em.persist(m);
                }
            });

            assertThat(service.countAjustes("activos", TIPO)).isEqualTo(3);

            List<Inventario> newestFirst = service.pageAjustes("activos", TIPO, null, "desc", 0, 10);
            assertThat(newestFirst).hasSize(3);
            assertThat(newestFirst.get(0).getFechaMovimiento())
                    .isAfterOrEqualTo(newestFirst.get(1).getFechaMovimiento());
            assertThat(newestFirst.get(1).getFechaMovimiento())
                    .isAfterOrEqualTo(newestFirst.get(2).getFechaMovimiento());

            List<Inventario> second = service.pageAjustes("activos", TIPO, null, "desc", 1, 1);
            assertThat(second).hasSize(1);
            // La página 2 (offset 1) salta el más reciente.
            assertThat(second.get(0).getCodigo())
                    .isEqualTo(newestFirst.get(1).getCodigo());

            List<Inventario> asc = service.pageAjustes("activos", TIPO, "fechaMovimiento", "asc", 0, 10);
            assertThat(asc).hasSize(3);
            assertThat(asc.get(0).getFechaMovimiento())
                    .isBeforeOrEqualTo(asc.get(1).getFechaMovimiento());

            assertThat(service.countAjustes("activos", "NO-EXISTE-QAORDER")).isZero();
        } finally {
            QuarkusTransaction.requiringNew().run(() -> em.createQuery(
                    "DELETE FROM Inventario i WHERE i.tipoMovimiento = :tipo")
                    .setParameter("tipo", TIPO)
                    .executeUpdate());
        }
    }
}
