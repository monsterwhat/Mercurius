package Controllers.Api.App.Reportes;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import Models.Articulos.Articulos;
import Models.ComprobantesEmitidos;
import Models.Detalles.DetalleServicio;
import Models.Detalles.LineaDetalle;
import Models.Encabezado.Encabezado;
import Models.Articulos.ArticuloPrecio;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import Services.ArticulosService;
import Services.ComprobantesEmitidosService;
import Services.ProductPerformanceService;
import Services.StockForecastService;
import jakarta.persistence.EntityManager;

/**
 * Un comprobante REP (tipo 10) no lleva Cantidad en su LineaDetalle: el XSD V4.4
 * lo simplifica y {@code ComprobanteService} lo omite a proposito. Por eso
 * {@code linea_detalle.cantidad} queda NULL en un comprobante REP real, y un
 * {@code SUM(ld.cantidad)} sobre un grupo cuyas filas son todas NULL devuelve
 * NULL (no 0).
 *
 * <p>Los tres metodos que castean ese agregado a {@code Number} reventaban con
 * NullPointerException y las paginas de Rendimiento y Pronosticos respondian
 * 500. Se اكتivo al integrar la suite del POS por tipo de documento, que si
 * emite un REP; hasta entonces ningun test dejaba un REP vivo en la base.
 *
 * <p>Esta suite siembra el caso de forma DETERMINISTA (no depende del orden de
 * las clases ni de que otra prueba deje un REP a medio sembrar) y verifica las
 * tres lecturas afectadas: best-selling, best-by-revenue y el historial de
 * ventas del pronostico.
 */
@QuarkusTest
@DisplayName("Agregados de unidades toleran lineas REP con Cantidad nula")
class AgregadosCantidadNulaTest {

    private static final String RENDIMIENTO = "/app/reportes/articulos/rendimiento";
    private static final String PRONOSTICOS = "/app/reportes/inventario/pronosticos";
    

    private static final AtomicInteger SUFIJOS = new AtomicInteger();

    @Inject
    ArticulosService articulosService;
    @Inject
    ComprobantesEmitidosService emitidosService;
    @Inject
    ProductPerformanceService productPerformanceService;
    @Inject
    StockForecastService stockForecastService;
    @Inject
    EntityManager em;
    @Inject
    UserTransaction utx;

    /** Lo sembrado por la prueba, para borrarlo en el finally sin depender de cascadas. */
    private record Siembra(Long articulo, String nombreArticulo,
                           Long comprobante, Long detalle, Long encabezado, String linea) {
    }

    // ── Pruebas ────────────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "inventario"})
    @DisplayName("best-selling no devuelve 500 con un REP de Cantidad nula")
    void bestSellingToleraCantidadNula() {
        Siembra siembra = sembrarRepSinCantidad();
        try {
            var resumen = productPerformanceService.getBestSellingProducts(
                    java.util.Date.from(LocalDateTime.now().minusDays(30).atZone(
                            java.time.ZoneId.systemDefault()).toInstant()),
                    new java.util.Date(), 50);
            assertThat(resumen).isNotNull();
            // El articulo del REP aparece con 0 unidades, no con un NPE.
            var fila = resumen.stream()
                    .filter(f -> siembra.nombreArticulo().equals(f.getProductName()))
                    .findFirst();
            assertThat(fila).as("el articulo del REP debe aparecer con 0 unidades").isPresent();
            assertThat(fila.get().getQuantitySold()).as("REP: Cantidad ausente equivale a 0 unidades")
                    .isZero();
        } finally {
            limpiar(siembra);
        }
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "inventario"})
    @DisplayName("best-by-revenue ordena por ingreso aunque Cantidad sea nula")
    void bestPorIngresosToleraCantidadNula() {
        Siembra siembra = sembrarRepSinCantidad();
        try {
            var resumen = productPerformanceService.getBestSellingProductsByRevenue(
                    java.util.Date.from(LocalDateTime.now().minusDays(30).atZone(
                            java.time.ZoneId.systemDefault()).toInstant()),
                    new java.util.Date(), 50);
            assertThat(resumen).isNotNull();
            assertThat(resumen.stream()
                    .filter(f -> siembra.nombreArticulo().equals(f.getProductName()))
                    .findFirst()).isPresent();
        } finally {
            limpiar(siembra);
        }
    }

    @Test
    @DisplayName("el pronostico de un articulo con solo REP no falla: 0 unidades vendidas")
    void pronosticoToleraCantidadNula() {
        Siembra siembra = sembrarRepSinCantidad();
        try {
            // generateForecast es la via publica que llama a getSalesHistory (que
            // es package-private); el NPE se disparaba dentro de ese recorrido.
            var pronostico = stockForecastService.generateForecast(siembra.articulo(), 7);
            assertThat(pronostico).as("un REP no debe romper el pronostico").isNotNull();
            assertThat(pronostico).allSatisfy(p ->
                    assertThat(p.predictedSales()).isGreaterThanOrEqualTo(0));
            assertThat(stockForecastService.getReorderRecommendation(siembra.articulo()))
                    .as("la recomendacion de reposicion tambien lee el historial").isNotNull();
            assertThat(stockForecastService.predictDemand(siembra.articulo(), 7))
                    .as("la prediccion de demanda tampoco debe fallar").isNotNull();
        } finally {
            limpiar(siembra);
        }
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "inventario"})
    @DisplayName("las paginas de rendimiento y pronosticos siguen en 200 con un REP vivo")
    void paginasSiguenRespondiendoConRepVivo() {
        Siembra siembra = sembrarRepSinCantidad();
        try {
            given().when().get(RENDIMIENTO).then().statusCode(200);
            given().when().get(RENDIMIENTO + "?seccion=ingresos").then().statusCode(200);
            given().when().get(RENDIMIENTO + "?seccion=menos").then().statusCode(200);
            given().when().get(PRONOSTICOS).then().statusCode(200);
        } finally {
            limpiar(siembra);
        }
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    /**
     * Articulo + comprobante REP (codigoDocumento 10) cuya unica linea NO lleva
     * Cantidad, replicando exactamente lo que produce ComprobanteService para
     * el tipo 10 (la rama {@code if (!isRep)} omite ese campo).
     */
    private Siembra sembrarRepSinCantidad() {
        String sufijo = "REP-NULL-" + SUFIJOS.incrementAndGet();

        Articulos articulo = new Articulos();
        articulo.setNombre("Articulo " + sufijo);
        articulo.setCodigoBarra("RP" + sufijo);
        articulo.setUnidadMedida("Unidad");
        articulo.setUnidadMedidaComercial("Unidad");
        articulo.setStatus(true);
        articulo.setProcessed(true);
        ArticuloPrecio precio = new ArticuloPrecio();
        precio.setArticulo(articulo);
        precio.setPrecioCostoSinIVA(BigDecimal.valueOf(1000));
        precio.setPorcentajeUtilidad(BigDecimal.ZERO);
        precio.setPrecioConUtilidad(BigDecimal.valueOf(1000));
        articulo.setPrecios(new ArrayList<>(List.of(precio)));
        articulosService.create(articulo);
        assertNotNull(articulo.getCodigo(), "el alta del articulo debe asignar codigo");

        Encabezado encabezado = new Encabezado();
        encabezado.setNumeroConsecutivo(sufijo);
        encabezado.setFechaEmision(LocalDateTime.now());
        encabezado.setCondicionVenta("01");
        encabezado.setSchemaVersion("4.4");
        encabezado.setCodigoDocumento("10");

        LineaDetalle linea = new LineaDetalle();
        linea.setNumeroLinea(1);
        linea.setDetalle(articulo.getNombre());
        linea.setMontoTotal(BigDecimal.valueOf(1000));
        linea.setSubTotal(BigDecimal.valueOf(1000));
        // NO se setea Cantidad: es lo que hace ComprobanteService para REP.

        DetalleServicio detalles = new DetalleServicio();
        linea.setDetalleServicio(detalles);
        detalles.setLineasDetalle(new ArrayList<>(List.of(linea)));

        ComprobantesEmitidos comprobante = new ComprobantesEmitidos();
        comprobante.setSchemaVersion("4.4");
        comprobante.setEncabezado(encabezado);
        comprobante.setDetalles(detalles);
        comprobante.setStatus(true);
        comprobante.setUser("it-agregados-cantidad-nula");
        comprobante.setHaciendaEstado("ACEPTADO");
        ComprobantesEmitidos creado = emitidosService.createAndReturn(comprobante);
        assertNotNull(creado, "el comprobante REP del fixture debe crearse");

        return new Siembra(articulo.getCodigo(), articulo.getNombre(), creado.getId(),
                detalles.getId(), encabezado.getId(), linea.getDetalle());
    }

    /**
     * Borra lo sembrado por id, en el orden que imponen las claves foraneas
     * (linea -&gt; comprobante -&gt; detalle -&gt; encabezado) y sin depender de la
     * cascada, que es lo que dejo huerfanos en la suite del POS. Es idempotente:
     * el finally de cada prueba puede llamarla mas de una vez.
     */
    private void limpiar(Siembra siembra) {
        try {
            utx.begin();
            em.createQuery("DELETE FROM LineaDetalle l WHERE l.detalle = :detalle")
                    .setParameter("detalle", siembra.linea())
                    .executeUpdate();
            em.createQuery("DELETE FROM ComprobantesEmitidos c WHERE c.id = :id")
                    .setParameter("id", siembra.comprobante())
                    .executeUpdate();
            em.createQuery("DELETE FROM DetalleServicio d WHERE d.id = :id")
                    .setParameter("id", siembra.detalle())
                    .executeUpdate();
            em.createQuery("DELETE FROM Encabezado e WHERE e.id = :id")
                    .setParameter("id", siembra.encabezado())
                    .executeUpdate();
            em.createQuery("DELETE FROM ArticuloPrecio p WHERE p.articulo.codigo = :codigo")
                    .setParameter("codigo", siembra.articulo())
                    .executeUpdate();
            Articulos entity = em.find(Articulos.class, siembra.articulo());
            if (entity != null) {
                em.remove(entity);
            }
            utx.commit();
        } catch (Exception e) {
            try {
                utx.rollback();
            } catch (Exception rollback) {
                rollback.printStackTrace();
            }
            throw new IllegalStateException("Limpieza de fixtures fallida", e);
        }
    }
}