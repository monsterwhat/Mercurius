package Services;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import Models.AlertaStock;
import Models.Articulos.Articulos;
import Models.Inventario;
import Models.SugerenciaReposicion;
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
 *
 * <p>Two sweep-level rules are covered here too, and both make the fixtures
 * date-sensitive: the minimum-history gate
 * ({@code mercurius.stock.antiguedad.minima-dias}, 7 by default) skips articles
 * whose oldest movement is younger than that — so movements meant to qualify
 * are seeded {@link #DIAS_HISTORIA} days back, never "today" — and the
 * per-sweep cap ({@code mercurius.stock.lote.maximo-nuevas}, lowered to 2 in
 * the %test profile so the cut is observable) bounds how many new alerts a
 * single sweep may create.</p>
 */
@QuarkusTest
@DisplayName("Sobrestock: exceso sobre el optimo genera alerta")
class SobrestockAlertTest {

    /** movements seeded this far back clear the 7-day minimum-history gate. */
    private static final int DIAS_HISTORIA = 12;

    /** qualifiers seeded for the cap test; must exceed the %test cap (2). */
    private static final int CALIFICADORES_PARA_TOPE = 3;

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
        sembrarStock(articulo, cantidad, DIAS_HISTORIA);
    }

    /** Compra con {@code diasAtras} días de antigüedad (0 = hoy). */
    private void sembrarStock(Articulos articulo, int cantidad, int diasAtras) {
        sembrarMovimiento(articulo, cantidad, "Compra", diasAtras);
    }

    /** Venta (cantidad negativa, como la guarda el motor) con esa antigüedad. */
    private void sembrarVenta(Articulos articulo, int unidades, int diasAtras) {
        sembrarMovimiento(articulo, -unidades, "Venta", diasAtras);
    }

    private void sembrarMovimiento(Articulos articulo, int cantidad, String tipo, int diasAtras) {
        Inventario movimiento = new Inventario();
        movimiento.setArticulo(articulo);
        movimiento.setCantidad(BigDecimal.valueOf(cantidad));
        movimiento.setTipoMovimiento(tipo);
        movimiento.setFechaMovimiento(haceDias(diasAtras));
        movimiento.setStatus(true);
        movimiento.setProcessed(true);
        movimiento.setNotas("Siembra de prueba para alertas de stock");
        inventarioService.create(movimiento);
    }

    private static Date haceDias(int dias) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, -dias);
        return cal.getTime();
    }

    private List<AlertaStock> alertasDe(Articulos articulo) {
        return em.createQuery(
                        "SELECT a FROM AlertaStock a WHERE a.articulo.codigo = :codigo ORDER BY a.id",
                        AlertaStock.class)
                .setParameter("codigo", articulo.getCodigo())
                .getResultList();
    }

    /** Mayor id de alerta existente: marca de agua para contar las nuevas del barrido. */
    private int maxIdAlerta() {
        List<Integer> ids = em.createQuery("SELECT a.id FROM AlertaStock a ORDER BY a.id DESC",
                        Integer.class)
                .setMaxResults(1)
                .getResultList();
        return ids.isEmpty() ? 0 : ids.get(0);
    }

    private long nuevasDesde(int idBase) {
        return em.createQuery("SELECT COUNT(a) FROM AlertaStock a WHERE a.id > :idBase", Long.class)
                .setParameter("idBase", idBase)
                .getSingleResult();
    }

    private long alertados(List<Articulos> articulos) {
        return articulos.stream()
                .filter(articulo -> alertasDe(articulo).stream()
                        .anyMatch(alerta -> "overstock".equals(alerta.getTipoAlerta())
                                && "active".equals(alerta.getEstado())))
                .count();
    }

    private void limpiar(Articulos articulo) {
        try {
            utx.begin();
            em.createQuery("DELETE FROM AlertaStock a WHERE a.articulo.codigo = :codigo")
                    .setParameter("codigo", articulo.getCodigo())
                    .executeUpdate();
            em.createQuery("DELETE FROM SugerenciaReposicion s WHERE s.articulo.codigo = :codigo")
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

    @Test
    @DisplayName("gate de antiguedad: movimientos de hoy no generan overstock")
    void movimientosDeHoyNoAlertanSobrestock() {
        Articulos articulo = sembrarArticulo(true);
        try {
            // 100 unidades como antes, pero sembradas hoy: historia de 0 dias contra
            // el minimo de 7, asi que el articulo ni siquiera se juzga.
            sembrarStock(articulo, 100, 0);

            stockAlertService.checkAndCreateStockAlerts();

            assertThat(alertasDe(articulo))
                    .as("un articulo sin historia no se mide contra el optimo de respaldo")
                    .isEmpty();
        } finally {
            limpiar(articulo);
        }
    }

    @Test
    @DisplayName("gate de antiguedad: movimientos de hoy tampoco generan low_stock")
    void movimientosDeHoyNoAlertanStockBajo() {
        Articulos articulo = sembrarArticulo(true);
        try {
            // Mismo caso que historiaSuficienteAlertaStockBajo pero sembrado hoy: con
            // 2 unidades y una venta de 98 el optimo por velocidad (980) dispararia
            // stock bajo si el gate no existiera.
            sembrarStock(articulo, 100, 0);
            sembrarVenta(articulo, 98, 0);

            stockAlertService.checkAndCreateStockAlerts();

            assertThat(alertasDe(articulo))
                    .as("el gate de antiguedad cubre tambien la familia de stock bajo")
                    .isEmpty();
        } finally {
            limpiar(articulo);
        }
    }

    @Test
    @DisplayName("gate de antiguedad: con historia suficiente el stock bajo si dispara")
    void historiaSuficienteAlertaStockBajo() {
        Articulos articulo = sembrarArticulo(true);
        try {
            sembrarStock(articulo, 100, DIAS_HISTORIA);
            sembrarVenta(articulo, 98, DIAS_HISTORIA - 1);

            stockAlertService.checkAndCreateStockAlerts();

            List<AlertaStock> alertas = alertasDe(articulo).stream()
                    .filter(a -> "low_stock".equals(a.getTipoAlerta()))
                    .toList();
            assertThat(alertas)
                    .as("2 unidades frente a un optimo de 98 x (3 + 7) dias dispara stock bajo")
                    .hasSize(1);
            assertThat(alertas.get(0).getEstado()).isEqualTo("active");
            assertThat(alertas.get(0).getCantidadActual()).isEqualTo(2);
            assertThat(alertas.get(0).getCantidadMinima()).isEqualTo(980);
        } finally {
            limpiar(articulo);
        }
    }

    @Test
    @DisplayName("tope por barrido: se corta con break y el resto entra en la corrida siguiente")
    void topePorBarrido() {
        int tope = stockAlertService.getMaximoNuevasPorBarrido();
        assertThat(tope)
                .as("el perfil de pruebas baja el tope para poder observar el corte")
                .isPositive()
                .isLessThan(CALIFICADORES_PARA_TOPE);

        List<Articulos> calificadores = new ArrayList<>();
        try {
            for (int i = 0; i < CALIFICADORES_PARA_TOPE; i++) {
                Articulos articulo = sembrarArticulo(true);
                calificadores.add(articulo);
                sembrarStock(articulo, 100, DIAS_HISTORIA);
            }

            int idBase = maxIdAlerta();
            stockAlertService.checkAndCreateStockAlerts();
            long nuevasPrimeraCorrida = nuevasDesde(idBase);
            assertThat(nuevasPrimeraCorrida)
                    .as("un solo barrido no puede pasar del tope de alertas nuevas")
                    .isEqualTo(tope);

            stockAlertService.checkAndCreateStockAlerts();
            assertThat(nuevasDesde(idBase))
                    .as("lo que no cupo se crea en la corrida siguiente (catch-up)")
                    .isGreaterThan(nuevasPrimeraCorrida);

            // La BD de pruebas es compartida y el backlog puede venir de otras
            // clases: se sigue barriendo hasta que los tres calificadores queden
            // alertados, que es la garantia de catch-up completo.
            for (int corrida = 0;
                    corrida < 5 && alertados(calificadores) < calificadores.size();
                    corrida++) {
                stockAlertService.checkAndCreateStockAlerts();
            }
            assertThat(alertados(calificadores))
                    .as("corridas sucesivas cubren a los tres calificadores")
                    .isEqualTo(calificadores.size());
        } finally {
            calificadores.forEach(this::limpiar);
        }
    }
}
