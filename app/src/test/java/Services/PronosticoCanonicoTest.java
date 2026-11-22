package Services;

import Models.Articulos.Articulos;
import Models.ConfiguracionPronostico;
import Models.Inventario;
import Models.PrecisionPronostico;
import Services.pronostico.MetodoPronostico;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contrato canonico del pronostico con base de datos: denominador de dias
 * naturales, independencia de nombres duplicados, seleccion por horizonte y
 * configuracion economica persistida.
 */
@QuarkusTest
@Tag("integration-services")
class PronosticoCanonicoTest {

    private static final AtomicInteger SUFIJOS = new AtomicInteger();

    @Inject
    StockForecastService stockForecastService;

    @Inject
    SeleccionMetodoService seleccionMetodoService;

    @Inject
    ConfiguracionPronosticoService configuracionPronosticoService;

    @Inject
    ArticulosService articulosService;

    @Inject
    InventarioService inventarioService;

    @Inject
    EntityManager em;

    @Inject
    UserTransaction utx;

    @Test
    void demandaDispersaSeDividePorDiasNaturales() {
        Long codigo = null;
        try {
            codigo = sembrarArticulo("Dispersa");
            // 5 ventas de 10 unidades en una ventana de 90 dias: total 50.
            for (int desplazamiento : List.of(89, 69, 49, 29, 9)) {
                sembrarVenta(codigo, LocalDate.now().minusDays(desplazamiento), 10);
            }

            StockForecastService.DemandPrediction prediccion =
                    stockForecastService.predictDemand(codigo, 30);

            assertNotNull(prediccion);
            double esperado = 50.0 / 90.0;
            assertTrue(prediccion.avgDailySales().doubleValue() > esperado - 0.02
                            && prediccion.avgDailySales().doubleValue() < esperado + 0.02,
                    "promedio diario esperado ~" + esperado + " (50/90), fue "
                            + prediccion.avgDailySales());
            assertTrue(prediccion.predictedDemand().doubleValue() > 0.0,
                    "un articulo con ventas no puede pronosticar cero");
        } finally {
            limpiar(codigo);
        }
    }

    @Test
    void nombresDuplicadosNoCompartenDemanda() {
        Long alto = null;
        Long bajo = null;
        try {
            String nombre = "IT Canonico Duplicado -" + SUFIJOS.incrementAndGet();
            alto = sembrarArticuloConNombre(nombre);
            bajo = sembrarArticuloConNombre(nombre);
            for (int dia = 29; dia >= 0; dia--) {
                sembrarVenta(alto, LocalDate.now().minusDays(dia), 10);
                sembrarVenta(bajo, LocalDate.now().minusDays(dia), 2);
            }

            StockForecastService.DemandPrediction prediccionAlto =
                    stockForecastService.predictDemand(alto, 30);
            StockForecastService.DemandPrediction prediccionBajo =
                    stockForecastService.predictDemand(bajo, 30);

            assertNotNull(prediccionAlto);
            assertNotNull(prediccionBajo);
            assertTrue(prediccionAlto.avgDailySales().compareTo(
                            prediccionBajo.avgDailySales()) > 0,
                    "cada articulo debe conservar SU demanda aunque compartan nombre: "
                            + prediccionAlto.avgDailySales() + " vs " + prediccionBajo.avgDailySales());
        } finally {
            limpiar(alto);
            limpiar(bajo);
        }
    }

    @Test
    void seleccionEsPorHorizonte() {
        Long codigo = null;
        try {
            codigo = sembrarArticulo("Horizontes");
            guardarPrecision(codigo, 7, MetodoPronostico.INGENUO);
            guardarPrecision(codigo, 14, MetodoPronostico.SES);
            guardarPrecision(codigo, 30, MetodoPronostico.MEDIA_MOVIL);
            guardarPrecision(codigo, 60, MetodoPronostico.SBA);

            assertEquals("INGENUO", seleccionMetodoService.metodoElegido(codigo, 7).orElse(null));
            assertEquals("SES", seleccionMetodoService.metodoElegido(codigo, 14).orElse(null));
            assertEquals("MEDIA_MOVIL", seleccionMetodoService.metodoElegido(codigo, 30).orElse(null));
            assertEquals("SBA", seleccionMetodoService.metodoElegido(codigo, 60).orElse(null));
            assertTrue(seleccionMetodoService.metodoElegido(codigo, 90).isEmpty(),
                    "un horizonte sin evaluacion no hereda el ganador de otro");
        } finally {
            limpiar(codigo);
        }
    }

    @Test
    void paginaYApiCompartenElMismoPronostico() {
        Long codigo = null;
        try {
            codigo = sembrarArticulo("Acuerdo");
            for (int dia = 29; dia >= 0; dia--) {
                sembrarVenta(codigo, LocalDate.now().minusDays(dia), 3);
            }

            StockForecastService.ReportePronosticos reporte =
                    stockForecastService.reportePronosticos(30, "pronosticos", null, null);
            StockForecastService.FilaPronostico fila = null;
            for (StockForecastService.FilaPronostico candidata : reporte.filas()) {
                if (candidata.articuloCodigo() == codigo) {
                    fila = candidata;
                    break;
                }
            }
            assertNotNull(fila, "la pagina debe incluir el articulo");
            StockForecastService.ProductForecast api = null;
            for (StockForecastService.ProductForecast candidato : stockForecastService.generateBulkForecast(30)) {
                if (codigo.equals(candidato.articuloId())) {
                    api = candidato;
                    break;
                }
            }
            assertNotNull(api, "el API debe incluir el articulo");

            assertEquals(fila.demandaEstimada(), Math.round(api.predictedDemand()),
                    "la demanda de la pagina y la del API deben salir de la misma corrida");
            assertEquals(fila.metodo(), api.metodo(),
                    "el metodo mostrado y el del API deben ser el mismo");
        } finally {
            limpiar(codigo);
        }
    }

    @Test
    void stockDeApiReporteYReposicionCoinciden() {
        Long codigo = null;
        try {
            codigo = sembrarArticulo("StockUnico");
            sembrarStock(codigo, 10);
            sembrarStock(codigo, -3);

            StockForecastService.ProductForecast api = null;
            for (StockForecastService.ProductForecast candidato : stockForecastService.generateBulkForecast(30)) {
                if (codigo.equals(candidato.articuloId())) {
                    api = candidato;
                    break;
                }
            }
            assertNotNull(api, "el API debe incluir el articulo");
            Models.Articulos.Articulos articulo = em.find(Models.Articulos.Articulos.class, codigo);

            assertEquals(7, api.currentStock(), "el reporte/API debe leer ArticuloStock");
            assertEquals(7.0,
                    inventarioService.getStockByArticleId(codigo).doubleValue(), 0.0001,
                    "la lectura por id de articulo debe coincidir");
            assertEquals(7.0, inventarioService.getStock(articulo.getCodigoBarra()), 0.0001,
                    "la lectura por barcode alterno debe coincidir");
            assertEquals(7.0,
                    inventarioService.calculateTotalStockForItemByBarcode(articulo.getCodigoBarra()),
                    0.0001,
                    "el libro de inventario debe coincidir con la proyeccion");
        } finally {
            limpiar(codigo);
        }
    }

    @Test
    @TestTransaction
    void configuracionDePronosticoSeGuardaYSeLee() {
        int antes = configuracionPronosticoService.listAllOrderById().size();
        ConfiguracionPronostico nueva = new ConfiguracionPronostico();
        nueva.setNivelServicio(new BigDecimal("0.970"));
        nueva.setCostoFaltante(new BigDecimal("42.00"));
        nueva.setCostoMantenimiento(new BigDecimal("3.50"));
        nueva.setPlazoReposicionDias(11);
        nueva.setCantidadMinimaPedido(6);
        nueva.setMultiploPedido(4);

        configuracionPronosticoService.guardar(nueva, null);

        ConfiguracionPronosticoService.ParametrosPronostico parametros =
                configuracionPronosticoService.parametros();
        assertEquals(0.970, parametros.nivelServicio(), 0.0001);
        assertEquals(42.00, parametros.costoFaltante(), 0.0001);
        assertEquals(3.50, parametros.costoMantenimiento(), 0.0001);
        assertEquals(11, parametros.plazoReposicionDias());
        assertEquals(6, parametros.cantidadMinimaPedido());
        assertEquals(4, parametros.multiploPedido());
        assertTrue(configuracionPronosticoService.listAllOrderById().size() > antes,
                "cada guardado crea una fila nueva (INSERT-only)");
    }

    // ── fixtures ────────────────────────────────────────────────────────

    private Long sembrarArticulo(String etiqueta) {
        return sembrarArticuloConNombre("IT Canonico " + etiqueta + " -" + SUFIJOS.incrementAndGet());
    }

    private Long sembrarArticuloConNombre(String nombre) {
        Articulos articulo = new Articulos();
        articulo.setNombre(nombre);
        articulo.setCodigoBarra("IT-CAN-" + SUFIJOS.incrementAndGet());
        articulo.setUnidadMedida("Unidad");
        articulo.setStatus(true);
        articulo.setProcessed(true);
        articulosService.create(articulo);
        assertNotNull(articulo.getCodigo());
        return articulo.getCodigo();
    }

    private void sembrarStock(Long codigo, double unidades) {
        Inventario movimiento = new Inventario();
        movimiento.setArticulo(em.find(Articulos.class, codigo));
        movimiento.setCantidad(BigDecimal.valueOf(unidades));
        movimiento.setTipoMovimiento(unidades >= 0 ? "Entrada" : "Venta");
        movimiento.setFechaMovimiento(new Date());
        movimiento.setNotas("IT Pronostico Canonico stock");
        movimiento.setProcessed(Boolean.TRUE);
        movimiento.setStatus(Boolean.TRUE);
        inventarioService.createWithStock(movimiento);
    }

    private void sembrarVenta(Long codigo, LocalDate dia, double unidades) {
        Inventario movimiento = new Inventario();
        movimiento.setArticulo(em.find(Articulos.class, codigo));
        movimiento.setCantidad(BigDecimal.valueOf(-unidades));
        movimiento.setTipoMovimiento("Venta");
        movimiento.setFechaMovimiento(Date.from(dia.atTime(12, 0)
                .atZone(ZoneId.systemDefault()).toInstant()));
        movimiento.setNotas("IT Pronostico Canonico");
        movimiento.setProcessed(Boolean.TRUE);
        movimiento.setStatus(Boolean.TRUE);
        inventarioService.create(movimiento);
    }

    private void guardarPrecision(Long codigo, int horizonte, MetodoPronostico metodo) {
        try {
            utx.begin();
            PrecisionPronostico fila = new PrecisionPronostico();
            fila.setArticulo(em.find(Articulos.class, codigo));
            fila.setMetodo(metodo.name());
            fila.setMase(0.75);
            fila.setSesgo(0.0);
            fila.setMae(1.0);
            fila.setHorizonteDias(horizonte);
            fila.setFechaCalculo(java.time.LocalDateTime.now());
            em.persist(fila);
            utx.commit();
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo sembrar la precision", e);
        }
    }

    private void limpiar(Long codigo) {
        if (codigo == null) {
            return;
        }
        try {
            utx.begin();
            em.createQuery("DELETE FROM PrecisionPronostico p WHERE p.articulo.codigo = :codigo")
                    .setParameter("codigo", codigo)
                    .executeUpdate();
            em.createQuery("DELETE FROM Inventario i WHERE i.articulo.codigo = :codigo")
                    .setParameter("codigo", codigo)
                    .executeUpdate();
            Articulos articulo = em.find(Articulos.class, codigo);
            if (articulo != null) {
                em.remove(articulo);
            }
            utx.commit();
        } catch (Exception e) {
            throw new IllegalStateException("Limpieza de fixtures fallida", e);
        }
    }
}
