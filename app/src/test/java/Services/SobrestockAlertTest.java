package Services;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import Models.AlertaStock;
import Models.Articulos.Articulos;
import Models.Inventario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Overstock alerts: stock far above optimal raises {@code overstock}.
 *
 * <p>Behavioral pin for the overstock evaluation in
 * {@code StockAlertService.checkAndCreateStockAlerts}. An article alerts when
 * its current stock exceeds {@code optimal x multiploSobrestock} (default 2,
 * via {@code mercurius.stock.sobrestock.multiplo}). Same table and lifecycle
 * as the low-stock family — {@code active} until acknowledged or resolved —
 * with per-tipo dedup so a stale low-stock alert from before a large purchase
 * never silences a fresh overstock.</p>
 *
 * <p>Determinism: with no sale movements in the 30-day window,
 * {@code calculateOptimalStock} falls back to {@code diasStockSeguridad x 2},
 * so seeding {@code diasStockSeguridad = 7} fixes optimal at 14 and the
 * threshold at 28. Stock is seeded as plain {@code Inventario} rows, which is
 * exactly what {@code getCurrentStock} sums.</p>
 */
@QuarkusTest
@DisplayName("Sobrestock: exceso sobre el optimo genera alerta")
class SobrestockAlertTest {

    @Inject StockAlertService stockAlertService;
    @Inject ArticulosService articulosService;
    @Inject InventarioService inventarioService;
    @Inject LoginService loginService;
    @Inject EntityManager em;
    @Inject UserTransaction utx;

    private static long secuencia = System.nanoTime();

    private static synchronized String codigoUnico(String prefijo) {
        return prefijo + Math.abs(secuencia++);
    }

    private Articulos sembrarArticulo(boolean alertas) {
        Articulos articulo = new Articulos();
        articulo.setNombre("Sobrestock " + codigoUnico("art"));
        articulo.setCodigoBarra(codigoUnico("SOB"));
        articulo.setUnidadMedida("Unidad");
        articulo.setUnidadMedidaComercial("Unidad");
        articulo.setStatus(true);
        articulo.setProcessed(true);
        articulo.setDiasStockSeguridad(7);
        articulo.setEstadoAlertas(alertas);
        articulosService.create(articulo);
        assertThat(articulo.getCodigo()).isNotNull();
        return articulo;
    }

    private void sembrarStock(Articulos articulo, int cantidad) {
        Inventario movimiento = new Inventario();
        movimiento.setArticulo(articulo);
        movimiento.setCantidad(BigDecimal.valueOf(cantidad));
        movimiento.setTipoMovimiento("Compra");
        movimiento.setFechaMovimiento(new Date());
        movimiento.setStatus(true);
        movimiento.setProcessed(true);
        movimiento.setNotas("Siembra de prueba para sobrestock");
        inventarioService.create(movimiento);
    }

    private List<AlertaStock> alertasDe(Articulos articulo) {
        return em.createQuery(
                        "SELECT a FROM AlertaStock a WHERE a.articulo.codigo = :codigo ORDER BY a.id",
                        AlertaStock.class)
                .setParameter("codigo", articulo.getCodigo())
                .getResultList();
    }

    private void limpiar(Articulos articulo) {
        try {
            utx.begin();
            em.createQuery("DELETE FROM AlertaStock a WHERE a.articulo.codigo = :codigo")
                    .setParameter("codigo", articulo.getCodigo())
                    .executeUpdate();
            em.createQuery("DELETE FROM Inventario i WHERE i.articulo.codigo = :codigo")
                    .setParameter("codigo", articulo.getCodigo())
                    .executeUpdate();
            Articulos gestionado = em.find(Articulos.class, articulo.getCodigo());
            if (gestionado != null) {
                em.remove(gestionado);
            }
            utx.commit();
        } catch (Exception e) {
            try {
                utx.rollback();
            } catch (Exception ignorado) {
                // Limpieza de pruebas: lo importante es no dejar filas huerfanas.
            }
        }
    }

    @Test
    @DisplayName("stock sobre el multiplo genera alerta overstock activa")
    void sobrestockSeDispara() {
        Articulos articulo = sembrarArticulo(true);
        try {
            sembrarStock(articulo, 100);

            stockAlertService.checkAndCreateStockAlerts();

            List<AlertaStock> alertas = alertasDe(articulo).stream()
                    .filter(a -> "overstock".equals(a.getTipoAlerta()))
                    .toList();
            assertThat(alertas)
                    .as("100 unidades con optimo 14 y multiplo 2 (umbral 28) debe alertar")
                    .hasSize(1);
            AlertaStock alerta = alertas.get(0);
            assertThat(alerta.getEstado()).isEqualTo("active");
            assertThat(alerta.getCantidadActual()).isEqualTo(100);
            assertThat(alerta.getCantidadMinima())
                    .as("el umbral que disparo la alerta queda registrado")
                    .isEqualTo(28);
            assertThat(alerta.getSugeridoReordenar())
                    .as("no hay nada que reordenar en un sobrestock")
                    .isNull();
            assertThat(alerta.getNotas()).contains("Sobrestock");
        } finally {
            limpiar(articulo);
        }
    }

    @Test
    @DisplayName("stock bajo el umbral no genera overstock (ni low-stock falso)")
    void bajoElUmbralNoAlerta() {
        Articulos articulo = sembrarArticulo(true);
        try {
            // 20 esta sobre el optimo (14, sin low-stock) y bajo el umbral (28).
            sembrarStock(articulo, 20);

            stockAlertService.checkAndCreateStockAlerts();

            assertThat(alertasDe(articulo))
                    .as("20 unidades no es ni bajo ni sobre el umbral")
                    .isEmpty();
        } finally {
            limpiar(articulo);
        }
    }

    @Test
    @DisplayName("el reintento no duplica la alerta activa")
    void noDuplicaActivas() {
        Articulos articulo = sembrarArticulo(true);
        try {
            sembrarStock(articulo, 100);

            stockAlertService.checkAndCreateStockAlerts();
            stockAlertService.checkAndCreateStockAlerts();

            assertThat(alertasDe(articulo).stream()
                    .filter(a -> "overstock".equals(a.getTipoAlerta())
                            && "active".equals(a.getEstado()))
                    .count())
                    .as("una sola alerta overstock activa por articulo")
                    .isEqualTo(1);
        } finally {
            limpiar(articulo);
        }
    }

    @Test
    @DisplayName("articulo con alertas desactivadas no genera overstock")
    void respetaEstadoAlertasDesactivado() {
        Articulos articulo = sembrarArticulo(false);
        try {
            sembrarStock(articulo, 100);

            stockAlertService.checkAndCreateStockAlerts();

            assertThat(alertasDe(articulo))
                    .as("estadoAlertas=false silencia tambien el sobrestock")
                    .isEmpty();
        } finally {
            limpiar(articulo);
        }
    }

    @Test
    @DisplayName("una alerta low-stock activa previa no silencia el sobrestock")
    void lowPreviaNoSilenciaSobrestock() throws Exception {
        Articulos articulo = sembrarArticulo(true);
        try {
            // Una alerta low_stock vieja y activa (de antes de una gran compra).
            utx.begin();
            AlertaStock vieja = new AlertaStock();
            vieja.setArticulo(em.merge(articulo));
            vieja.setTipoAlerta("low_stock");
            vieja.setCantidadActual(5);
            vieja.setCantidadMinima(14);
            em.persist(vieja);
            utx.commit();

            sembrarStock(articulo, 100);
            stockAlertService.checkAndCreateStockAlerts();

            assertThat(alertasDe(articulo).stream()
                    .filter(a -> "overstock".equals(a.getTipoAlerta())
                            && "active".equals(a.getEstado()))
                    .count())
                    .as("el sobrestock no hereda el dedup por articulo del low-stock")
                    .isEqualTo(1);
        } finally {
            limpiar(articulo);
        }
    }
}
