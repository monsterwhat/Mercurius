package Controllers.Api.App;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import Models.Articulos.Articulos;
import Models.Articulos.ArticuloPrecio;
import Models.Cabys;
import Services.AppSettingsService;
import Services.ArticulosService;
import Services.CabysService;
import Services.ComprobantesEmitidosService;
import Services.DirectoryService;
import Services.LoginService;
import Services.cart.CartSessionStore;
import Utils.PDFGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Retrying a sale must never invoice twice.
 *
 * <p>Behavioral pin for the idempotency stamp in {@code PosResource.doFacturar}.
 * The pipeline commits stock and the invoice (step 8) and THEN still answers
 * 500 when the PDF file is missing — with the cart deliberately intact. Before
 * the stamp, every retry re-ran the whole pipeline: a second stock decrement
 * and a second numbered invoice for one sale. The same duplication happened on
 * a client timeout after commit or a double-clicked submit.</p>
 *
 * <p>{@code PDFGenerator} is mocked to write nothing, which forces the genuine
 * {@code PDF_NO_GENERADO} 500 after a committed invoice — the exact
 * precondition for the duplicate. The first test proves the retry is safe even
 * when the PDF never recovers; the second proves a recovered PDF completes the
 * original sale instead of starting a new one.</p>
 */
@QuarkusTest
@DisplayName("POS: reintentar una venta no factura dos veces")
class PosFacturarIdempotenciaTest extends support.ContextPathIsolation {

    private static final String BASE = "/Mercurius";
    private static final String POS = BASE + "/api/app/pos";

    @Inject ArticulosService articulosService;
    @Inject CabysService cabysService;
    @Inject LoginService loginService;
    @Inject AppSettingsService appSettingsService;
    @Inject CartSessionStore cartSessionStore;
    @Inject ComprobantesEmitidosService emitidosService;
    @Inject DirectoryService dirService;
    @Inject EntityManager em;

    @InjectMock PDFGenerator pdfGenerator;

    private static final java.util.concurrent.atomic.AtomicInteger SECUENCIA =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /** CABYS fijo de 10 caracteres (varchar(13)): se reutiliza entre pruebas. */
    private static final String CABYS_CODIGO = "IDMPOS0001";

    private Cabys ensureCabys() {
        Cabys cabys = cabysService.find(CABYS_CODIGO);
        if (cabys == null) {
            cabys = new Cabys(CABYS_CODIGO, "Articulo de prueba idempotencia",
                    "Pruebas", "0", "https://example.com/cabys", "Activo");
            cabysService.create(cabys);
        }
        return cabys;
    }

    private Articulos sembrarArticulo() {
        // Códigos cortos: codigoCabys va a varchar(13) en linea_detalle y el
        // codigo de barras es clave unica de ArticuloStock.
        String tag = String.format("%04d", SECUENCIA.getAndIncrement());
        Articulos articulo = new Articulos();
        articulo.setNombre("Articulo idempotencia " + tag);
        articulo.setCodigoBarra("IDM" + tag);
        articulo.setUnidadMedida("Unidad");
        articulo.setUnidadMedidaComercial("Unidad");
        articulo.setStatus(true);
        articulo.setProcessed(true);

        ArticuloPrecio precio = new ArticuloPrecio();
        precio.setArticulo(articulo);
        precio.setPrecioCostoSinIVA(new BigDecimal("1000"));
        precio.setPorcentajeUtilidad(BigDecimal.ZERO);
        precio.setPrecioConUtilidad(new BigDecimal("1000"));
        articulo.setPrecios(new ArrayList<>(List.of(precio)));
        articulo.setCodigoCabys(ensureCabys());

        articulosService.create(articulo);
        assertThat(articulo.getCodigo()).isNotNull();
        return articulo;
    }

    private Map<String, String> adminSession() {
        var lp = given().redirects().follow(false).when().get(BASE + "/login");
        lp.then().statusCode(200);
        Map<String, String> c = new HashMap<>(lp.getCookies());
        var login = given().redirects().follow(false).cookies(c)
                .contentType(ContentType.URLENC)
                .formParam("j_username", "admin")
                .formParam("j_password", "admin123")
                .when().post(BASE + "/j_security_check");
        login.then().statusCode(302);
        c.putAll(login.getCookies());
        return c;
    }

    private io.restassured.specification.RequestSpecification authed(Map<String, String> c) {
        var spec = given().redirects().follow(false).cookies(c);
        String token = c.get("csrf-token");
        if (token == null) {
            token = c.get("csrftoken");
        }
        if (token != null) {
            spec.header("X-CSRF-TOKEN", token);
        }
        return spec;
    }

    private void ensureAppSettings() {
        if (appSettingsService.returnCurrent() != null) {
            return;
        }
        var settings = new Models.ConfiguracionAplicacion();
        settings.setEstatus(true);
        settings.setNombre("Cajero idempotencia");
        settings.setCodigoSucursal("001");
        settings.setCodigoTerminal("00001");
        settings.setIdentificacion("3100100008");
        settings.setTipoIdentificacion("02");
        appSettingsService.create(settings);
    }


    private void escanear(Map<String, String> s, Articulos articulo) {
        authed(s).contentType(ContentType.JSON)
                .body("{\"codigoBarra\":\"" + articulo.getCodigoBarra() + "\"}")
                .when().post(POS + "/scan")
                .then().statusCode(200);
    }

    private Response facturar(Map<String, String> s) {
        return authed(s).contentType(ContentType.JSON)
                .body("{\"tipoDocumento\":\"04\","
                        + "\"pagos\":[{\"metodoPago\":\"01\",\"monto\":1000}],"
                        + "\"puntosARedimir\":0}")
                .when().post(POS + "/facturar");
    }

    private long contarFacturas() {
        return emitidosService.listAll().size();
    }

    private BigDecimal stockDe(String codigoBarra) {
        List<Models.Articulos.ArticuloStock> filas = em.createQuery(
                        "SELECT a FROM ArticuloStock a WHERE a.codigoBarra = :codigo",
                        Models.Articulos.ArticuloStock.class)
                .setParameter("codigo", codigoBarra)
                .getResultList();
        if (filas.isEmpty()) {
            return null;
        }
        return filas.get(0).getStock();
    }

    private long ultimaFacturaId() {
        return emitidosService.listAll().stream()
                .mapToLong(f -> f.getId())
                .max()
                .orElseThrow();
    }

    private void limpiarPdfsDePrueba() throws Exception {
        // The invoice ids restart at 1 on every %test drop-and-create boot, but
        // the PDF directory lives on the filesystem and survives across test
        // classes and runs. A stale tiqueteElectronico_1.pdf from another suite
        // would satisfy the isFile() check and turn the forced 500 into a 200.
        Path dir = Paths.get(dirService.getFacturasDirPath());
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (var flujo = Files.list(dir)) {
            for (Path p : (Iterable<Path>) flujo::iterator) {
                String nombre = p.getFileName().toString();
                if (nombre.startsWith("tiqueteElectronico_") && nombre.endsWith(".pdf")) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    @Test
    @DisplayName("un 500 por PDF no duplica la factura ni el inventario al reintentar")
    void reintentoTras500NoDuplica() throws Exception {
        ensureAppSettings();
        limpiarPdfsDePrueba();
        Map<String, String> s = adminSession();
        cartSessionStore.remove("admin");
        Articulos articulo = sembrarArticulo();
        escanear(s, articulo);

        long facturasAntes = contarFacturas();
        BigDecimal stockAntes = stockDe(articulo.getCodigoBarra());

        // Primer intento: el PDF mockeado no escribe nada -> 500, pero la
        // factura y el movimiento de inventario ya estan confirmados.
        facturar(s).then()
                .statusCode(500)
                .body("error.code", equalTo("PDF_NO_GENERADO"));

        assertThat(contarFacturas())
                .as("el primer intento si creo la factura")
                .isEqualTo(facturasAntes + 1);
        BigDecimal stockTrasPrimero = stockDe(articulo.getCodigoBarra());
        assertThat(stockTrasPrimero).isNotNull();
        if (stockAntes != null) {
            assertThat(stockTrasPrimero)
                    .as("el primer intento desconto exactamente 1 unidad")
                    .isEqualByComparingTo(stockAntes.subtract(BigDecimal.ONE));
        } else {
            assertThat(stockTrasPrimero)
                    .as("primera venta del articulo: crea la fila con -1")
                    .isEqualByComparingTo(new BigDecimal("-1"));
        }

        // Reintento con el mismo carrito intacto: debe responder 500 otra vez
        // (el PDF sigue sin existir) SIN crear nada nuevo.
        facturar(s).then()
                .statusCode(500)
                .body("error.code", equalTo("PDF_NO_GENERADO"));

        assertThat(contarFacturas())
                .as("el reintento no debe crear una segunda factura")
                .isEqualTo(facturasAntes + 1);
        assertThat(stockDe(articulo.getCodigoBarra()))
                .as("el reintento no debe descontar inventario otra vez")
                .isEqualByComparingTo(stockTrasPrimero);

        cartSessionStore.remove("admin");
    }

    @Test
    @DisplayName("si el PDF aparece, el reintento completa la venta original")
    void reintentoRecuperaLaVentaOriginal() throws Exception {
        ensureAppSettings();
        limpiarPdfsDePrueba();
        Map<String, String> s = adminSession();
        cartSessionStore.remove("admin");
        Articulos articulo = sembrarArticulo();
        escanear(s, articulo);

        long facturasAntes = contarFacturas();

        facturar(s).then()
                .statusCode(500)
                .body("error.code", equalTo("PDF_NO_GENERADO"));

        long id = ultimaFacturaId();
        assertThat(contarFacturas()).isEqualTo(facturasAntes + 1);

        // El subsistema de PDF se recupera: el archivo aparece en disco.
        Path pdf = Paths.get(dirService.getFacturasDirPath(), "tiqueteElectronico_" + id + ".pdf");
        Files.createDirectories(pdf.getParent());
        Files.write(pdf, "%PDF-1.4 recuperado".getBytes(StandardCharsets.UTF_8));

        // El reintento devuelve 200 con LA MISMA factura, no una nueva.
        facturar(s).then()
                .statusCode(200)
                .body("data.comprobanteId", equalTo((int) id))
                .body("data.pdfUrl", org.hamcrest.Matchers.containsString("tiqueteElectronico_" + id + ".pdf"));

        assertThat(contarFacturas())
                .as("la recuperacion no debe crear otra factura")
                .isEqualTo(facturasAntes + 1);

        // Y la venta queda cerrada: el carrito se consume como en un exito normal.
        authed(s).when().get(POS + "/cart")
                .then()
                .body("data.items", org.hamcrest.Matchers.hasSize(0));

        Files.deleteIfExists(pdf);
        cartSessionStore.remove("admin");
    }
}
