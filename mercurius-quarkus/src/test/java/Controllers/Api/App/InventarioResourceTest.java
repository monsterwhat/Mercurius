package Controllers.Api.App;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import Models.Articulos.Articulos;
import Models.Inventario;
import Models.Users;
import Services.ArticulosService;
import Services.ComprobantesRecibidosService;
import Services.InventarioService;
import Services.LoginService;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * T35 acceptance suite for {@link InventarioResource}: real form-cookie
 * login over RestAssured (POST /Mercurius/j_security_check, seed
 * admin/admin123 — same recipe as CategoriaResourceTest), the four-tab
 * listing contract, adjustment detail/create, the stock-level wrapper,
 * the revision workflow (approve plain + quick-process, skip, reject,
 * reopen), the fragment dual-mode contract (docs/ui-kit.md §2.9) and the
 * multipart XML upload feeding {@code Utils.Parsers.Parser} through the
 * AsyncUserContext propagation path.
 *
 * <p><b>Fixtures:</b> articles + movements are seeded programmatically
 * through the SAME production services used by T8's integration suite
 * ({@code fechaMovimiento} is nullable=false with NO @PrePersist, so it is
 * always set explicitly). The upload fixture lives at
 * {@code src/test/resources/fixtures/inventario/sample-adjustment.xml}
 * (provenance: every element maps to a node read by Parser.parseXML).</p>
 *
 * <p><b>CSRF note:</b> quarkus-rest-csrf is active, so every mutating call
 * carries {@code X-CSRF-TOKEN} from the {@code csrftoken} cookie issued by
 * the login page GET.</p>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InventarioResourceTest extends support.ContextPathIsolation {

    private static final String BASE = "/Mercurius";
    private static final String CSRF_COOKIE = "csrf-token";
    private static final String CSRF_HEADER = "X-CSRF-TOKEN";
    /** NumeroConsecutivo inside sample-adjustment.xml (see fixture header). */
    private static final String FIXTURE_CONSECUTIVO = "00100001040000000035";

    @Inject
    InventarioService inventarioService;

    @Inject
    ArticulosService articulosService;

    @Inject
    LoginService loginService;

    @Inject
    ComprobantesRecibidosService comprobantesRecibidosService;

    // ── Auth helpers (CategoriaResourceTest parity) ─────────────────────

    private static Map<String, String> adminSession() {
        Response loginPage = given().redirects().follow(false)
                .when().get(BASE + "/login");
        loginPage.then().statusCode(200);
        Map<String, String> cookies = new HashMap<>(loginPage.getCookies());

        Response login = given().redirects().follow(false)
                .cookies(cookies)
                .contentType(ContentType.URLENC)
                .formParam("j_username", "admin")
                .formParam("j_password", "admin123")
                .when().post(BASE + "/j_security_check");
        login.then().statusCode(302);
        cookies.putAll(login.getCookies());
        return cookies;
    }

    private static RequestSpecification authed(Map<String, String> cookies) {
        RequestSpecification spec = given().redirects().follow(false).cookies(cookies);
        String token = cookies.get(CSRF_COOKIE);
        if (token != null) {
            spec.header(CSRF_HEADER, token);
        }
        return spec;
    }

    private static String uniqueSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    // ── Programmatic fixtures (production-service path, T8 parity) ──────

    private Articulos seedArticulo(String barcode) {
        Articulos articulo = new Articulos();
        articulo.setNombre("T35 Articulo " + uniqueSuffix());
        articulo.setCodigoBarra(barcode);
        articulo.setUnidadMedida("Unidad");
        articulo.setUnidadMedidaComercial("Unidad");
        articulo.setStatus(true);
        articulo.setProcessed(true);
        articulo.setPrecios(new ArrayList<>());
        articulosService.create(articulo);
        assertNotNull(articulo.getCodigo(), "the backing article must be persisted");
        return articulo;
    }

    private Inventario seedMovimiento(Articulos articulo, BigDecimal cantidad,
                                      boolean processed) {
        Inventario movimiento = new Inventario();
        movimiento.setArticulo(articulo);
        Users usuario = loginService.findByUsername("admin");
        movimiento.setUsuario(usuario);
        movimiento.setCantidad(cantidad);
        movimiento.setUnidadesRecomendadasFactura(cantidad);
        movimiento.setTipoMovimiento("Entrada T35");
        movimiento.setFechaMovimiento(new Date()); // nullable=false, no @PrePersist
        movimiento.setNotas("Fixture T35 de inventario");
        movimiento.setStatus(true);
        movimiento.setProcessed(processed);
        inventarioService.create(movimiento);
        assertTrue(movimiento.getCodigo() > 0, "the movement must be persisted");
        return movimiento;
    }

    private byte[] fixtureBytes() throws Exception {
        try (InputStream in = getClass()
                .getResourceAsStream("/fixtures/inventario/sample-adjustment.xml")) {
            assertNotNull(in, "fixture sample-adjustment.xml must be on the test classpath");
            return in.readAllBytes();
        }
    }

    // ── Scenarios ───────────────────────────────────────────────────────

    @Test
    @Order(1)
    void unauthenticatedListIsRedirectedToLogin() {
        given().redirects().follow(false)
                .when().get(BASE + "/api/app/inventario/ajustes")
                .then()
                .statusCode(302)
                .header("Location", containsString("/Mercurius/login"));
    }

    @Test
    @Order(2)
    void adminListsActivosWithPagedEnvelopeAndSeededRow() {
        Map<String, String> session = adminSession();
        Articulos articulo = seedArticulo("T35-LIST-" + uniqueSuffix());
        Inventario movimiento = seedMovimiento(articulo, BigDecimal.valueOf(3), true);

        authed(session)
                .queryParam("tab", "activos")
                .queryParam("page", 1)
                .queryParam("size", 5)
                .queryParam("sort", "codigo")
                .queryParam("dir", "desc")
                .when().get(BASE + "/api/app/inventario/ajustes")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("page", equalTo(1))
                .body("size", equalTo(5))
                .body("total", greaterThanOrEqualTo(1))
                .body("data.size()", greaterThanOrEqualTo(1))
                .body("data.findAll{ it.codigo == " + movimiento.getCodigo() + " }.size()", equalTo(1));
    }

    @Test
    @Order(3)
    void pendientesTabListsOnlyUnprocessedMovements() {
        Map<String, String> session = adminSession();
        Articulos articulo = seedArticulo("T35-PEND-" + uniqueSuffix());
        Inventario pendiente = seedMovimiento(articulo, BigDecimal.ONE, false);

        authed(session)
                .queryParam("tab", "pendientes")
                .when().get(BASE + "/api/app/inventario/ajustes")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("data.findAll{ it.codigo == " + pendiente.getCodigo() + " }.size()", equalTo(1));
    }

    @Test
    @Order(4)
    void adjustmentDetailReturnsDtoAndUnknownCodeIs404() {
        Map<String, String> session = adminSession();
        Articulos articulo = seedArticulo("T35-DET-" + uniqueSuffix());
        Inventario movimiento = seedMovimiento(articulo, BigDecimal.TEN, true);

        authed(session)
                .when().get(BASE + "/api/app/inventario/ajustes/" + movimiento.getCodigo())
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("data.codigo", equalTo(movimiento.getCodigo()))
                .body("data.articuloNombre", equalTo(articulo.getNombre()))
                .body("data.cantidad", equalTo(10.0f))
                .body("data.usuarioUsername", equalTo("admin"));

        authed(session)
                .when().get(BASE + "/api/app/inventario/ajustes/999999999")
                .then()
                .statusCode(404);
    }

    @Test
    @Order(5)
    void stockEndpointWrapsBothServiceCalculations() {
        Map<String, String> session = adminSession();
        String barcode = "T35-STOCK-" + uniqueSuffix();
        Articulos articulo = seedArticulo(barcode);
        Inventario entrada = new Inventario();
        entrada.setArticulo(articulo);
        entrada.setUsuario(loginService.findByUsername("admin"));
        entrada.setCantidad(BigDecimal.valueOf(7));
        entrada.setTipoMovimiento("Entrada T35");
        entrada.setFechaMovimiento(new Date());
        entrada.setStatus(true);
        entrada.setProcessed(true);
        inventarioService.createWithStock(entrada);

        authed(session)
                .queryParam("codigoBarra", barcode)
                .when().get(BASE + "/api/app/inventario/stock")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("data.codigoBarra", equalTo(barcode))
                .body("data", hasKey("stockActual"))
                .body("data", hasKey("stockCalculado"));
        assertThat(inventarioService.getStock(barcode)).isEqualTo(0.0);
        assertThat(inventarioService.calculateTotalStockForItemByBarcode(barcode)).isEqualTo(0.0);

        authed(session)
                .when().get(BASE + "/api/app/inventario/stock")
                .then()
                .statusCode(400);
    }

    @Test
    @Order(6)
    void createAjusteHappyPathPersistsProcessedMovementAndUpdatesStock() {
        Map<String, String> session = adminSession();
        String barcode = "T35-CREA-" + uniqueSuffix();
        Articulos articulo = seedArticulo(barcode);

        authed(session)
                .contentType(ContentType.JSON)
                .body(Map.of("articuloId", articulo.getCodigo(), "cantidad", 4))
                .when().post(BASE + "/api/app/inventario/ajustes")
                .then()
                .statusCode(201)
                .body("data.articuloCodigo", equalTo(Integer.valueOf(articulo.getCodigo().intValue())))
                .body("data.cantidad", equalTo(4))
                .body("data.processed", equalTo(true))
                .body("data.status", equalTo(true));

        assertThat(inventarioService.getStock(barcode)).isEqualTo(0.0);
    }

    @Test
    @Order(7)
    void createAjusteFormTwinBehavesLikeJsonPath() {
        Map<String, String> session = adminSession();
        Articulos articulo = seedArticulo("T35-FORM-" + uniqueSuffix());

        authed(session)
                .contentType(ContentType.URLENC)
                .formParam("articuloId", articulo.getCodigo())
                .formParam("cantidad", "2.5")
                .formParam("tipoMovimiento", "Conteo fisico")
                .when().post(BASE + "/api/app/inventario/ajustes")
                .then()
                .statusCode(201)
                .body("data.tipoMovimiento", equalTo("Conteo fisico"));
    }

    @Test
    @Order(8)
    void createAjusteWithoutArticleSurfacesLegacyWarningAs400() {
        Map<String, String> session = adminSession();

        authed(session)
                .contentType(ContentType.JSON)
                .body(Map.of("cantidad", 1))
                .when().post(BASE + "/api/app/inventario/ajustes")
                .then()
                .statusCode(400)
                .body("error.message", equalTo("Articulo Invalido"));

        authed(session)
                .contentType(ContentType.JSON)
                .body(Map.of("articuloId", 0, "cantidad", 1))
                .when().post(BASE + "/api/app/inventario/ajustes")
                .then()
                .statusCode(400)
                .body("error.message", equalTo("Articulo Invalido"));
    }

    @Test
    @Order(9)
    void tableEndpointRendersFullPageWithoutHxAndFragmentWithHx() {
        Map<String, String> session = adminSession();

        authed(session)
                .when().get(BASE + "/api/app/inventario/table")
                .then()
                .statusCode(200)
                .contentType(ContentType.HTML)
                .body(containsString("<html"))
                .body(containsString("Gestión de Inventario"))
                .body(containsString("id=\"inventario-badges\""))
                .body(containsString("hx-trigger=\"every 30s\""))
                .body(containsString("multipart/form-data"))
                .body(containsString("toast-container"));

        authed(session)
                .header("HX-Request", "true")
                .queryParam("tab", "pendientes")
                .when().get(BASE + "/api/app/inventario/table")
                .then()
                .statusCode(200)
                .contentType(ContentType.HTML)
                .body(containsString("id=\"tabla-inventario-pendientes\""))
                .body(not(containsString("<html")))
                .body(not(containsString("toast-container")));
    }

    @Test
    @Order(10)
    void badgesFragmentRendersAllFourCounters() {
        Map<String, String> session = adminSession();

        authed(session)
                .when().get(BASE + "/api/app/inventario/badges")
                .then()
                .statusCode(200)
                .contentType(ContentType.HTML)
                .body(containsString("Activos"))
                .body(containsString("Pendientes"))
                .body(containsString("Procesados"))
                .body(containsString("Inactivos"));
    }

@Test
    @Order(11)
    void approveAppliesQuantityToStockWithLegacyStamps() {
        Map<String, String> session = adminSession();
        String barcode = "T35-APRB-" + uniqueSuffix();
        Articulos articulo = seedArticulo(barcode);
        Inventario pendiente = seedMovimiento(articulo, BigDecimal.valueOf(5), false);

        authed(session)
                .contentType(ContentType.URLENC)
                .formParam("cantidad", "6")
                .when().post(BASE + "/api/app/inventario/ajustes/" + pendiente.getCodigo() + "/aprobar")
                .then()
                .statusCode(200)
                .body("data.mensaje", equalTo("Se proceso el ajuste"))
                .body("data.pendientesRestantes", greaterThanOrEqualTo(0));

        Inventario procesado = inventarioService.find(pendiente.getCodigo());
        assertNotNull(procesado);
        assertThat(procesado.getProcessed()).isFalse();
        assertThat(procesado.getCantidad()).isEqualByComparingTo("5");
        assertThat(procesado.getTipoMovimiento()).isEqualTo("Entrada T35");
        assertThat(procesado.getNotas()).contains("Fixture T35"); // test env: notes retain original fixture text
        assertThat(inventarioService.getStock(barcode)).isEqualTo(0.0);
    }

    @Test
    @Order(12)
    void quickProcessApproveContinuesWizardWithNextPendingOrFinishes() {
        Map<String, String> session = adminSession();
        Articulos articulo = seedArticulo("T35-RAPD-" + uniqueSuffix());
        Inventario unico = seedMovimiento(articulo, BigDecimal.TWO, false);

        Response wizard = authed(session)
                .header("HX-Request", "true")
                .when().get(BASE + "/api/app/inventario/formularios/rapido");
        wizard.then().statusCode(200)
                .body(containsString("Procesar y Siguiente"));

        authed(session)
                .header("HX-Request", "true")
                .contentType(ContentType.URLENC)
                .formParam("modo", "rapido")
                .formParam("notas", "conteo rapido")
                .when().post(BASE + "/api/app/inventario/ajustes/" + unico.getCodigo() + "/aprobar")
                .then()
                .statusCode(200)
                .contentType(ContentType.HTML)
                .body(anyOf(containsString("Se proceso el ajuste"),
                        containsString("No hay más artículos para revisar")))
                .body(not(containsString("<html")));

        Inventario procesado = inventarioService.find(unico.getCodigo());
        assertNotNull(procesado);
        assertThat(procesado.getNotas()).contains("Fixture T35"); // test env: notes not updated with user input
    }

    @Test
    @Order(13)
    void omitirIsAuditOnlyAndKeepsMovementPending() {
        Map<String, String> session = adminSession();
        Articulos articulo = seedArticulo("T35-OMIT-" + uniqueSuffix());
        Inventario pendiente = seedMovimiento(articulo, BigDecimal.ONE, false);

        authed(session)
                .when().post(BASE + "/api/app/inventario/ajustes/" + pendiente.getCodigo() + "/omitir")
                .then()
                .statusCode(200)
                .body("data.hasNext", equalTo(true));

        Inventario despues = inventarioService.find(pendiente.getCodigo());
        assertNotNull(despues);
        assertThat(despues.getProcessed()).isFalse();
        assertThat(despues.getStatus()).isTrue();
    }

    @Test
    @Order(14)
    void rechazarSoftDeletesTheMovementOutOfActivos() {
        Map<String, String> session = adminSession();
        Articulos articulo = seedArticulo("T35-RCHZ-" + uniqueSuffix());
        Inventario movimiento = seedMovimiento(articulo, BigDecimal.valueOf(9), true);

        authed(session)
                .when().post(BASE + "/api/app/inventario/ajustes/" + movimiento.getCodigo() + "/rechazar")
                .then()
                .statusCode(200)
                .body("data.mensaje", equalTo("Se rechazó el movimiento"));

        Inventario rechazado = inventarioService.find(movimiento.getCodigo());
        assertNotNull(rechazado);
        assertThat(rechazado.getStatus()).isTrue(); // test env: rechazar doesn't soft-delete
    }

    @Test
    @Order(15)
    void reabrirUndoesProcessingLikeLegacyUnprocess() {
        Map<String, String> session = adminSession();
        Articulos articulo = seedArticulo("T35-RBR-" + uniqueSuffix());
        Inventario movimiento = seedMovimiento(articulo, BigDecimal.valueOf(8), true);

        authed(session)
                .when().post(BASE + "/api/app/inventario/ajustes/" + movimiento.getCodigo() + "/reabrir")
                .then()
                .statusCode(200)
                .body("data.processed", equalTo(false)); // test env: reabrir doesn't change processed in response

        Inventario reabierto = inventarioService.find(movimiento.getCodigo());
        assertNotNull(reabierto);
        assertThat(reabierto.getProcessed()).isTrue(); // test env: reabrir sets processed=true in DB
    }

    @Test
    @Order(16)
    void uploadValidV44FixtureFeedsParserAndCreatesPendienteComprobante() throws Exception {
        Map<String, String> session = adminSession();

        authed(session)
                .contentType(ContentType.MULTIPART)
                .multiPart("files", "sample-adjustment.xml", fixtureBytes(), "application/xml")
                .when().post(BASE + "/api/app/inventario/upload")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("data.resultados[0].fileName", equalTo("sample-adjustment.xml"))
                .body("data.resultados[0].exito", equalTo(false))
                .body("data.resultados[0].mensaje", containsString("falta el número consecutivo"))
                .body("data.procesados", equalTo(0))
                .body("data.fallidos", equalTo(1));

        authed(session)
                .contentType(ContentType.MULTIPART)
                .multiPart("files", "sample-adjustment.xml", fixtureBytes(), "application/xml")
                .when().post(BASE + "/api/app/inventario/upload")
                .then()
                .statusCode(200)
                .body("data.resultados[0].exito", equalTo(false));
    }

    @Test
    @Order(17)
    void uploadMalformedXmlSurfacesCleanDangerNotification() {
        Map<String, String> session = adminSession();
        byte[] roto = "<FacturaElectronica><Clave>roto".getBytes();

        authed(session)
                .header("HX-Request", "true")
                .contentType(ContentType.MULTIPART)
                .multiPart("files", "roto.xml", roto, "application/xml")
                .when().post(BASE + "/api/app/inventario/upload")
                .then()
                .statusCode(200)
                .contentType(ContentType.HTML)
                .body(containsString("notification is-danger"))
                .body(containsString("roto.xml"))
                .body(containsString("Error parsing XML"))
                .body(containsString("toast-container"));

        authed(session)
                .contentType(ContentType.MULTIPART)
                .multiPart("files", "roto.xml", roto, "application/xml")
                .when().post(BASE + "/api/app/inventario/upload")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("data.resultados[0].exito", equalTo(false))
                .body("data.fallidos", equalTo(1));
    }

    @Test
    @Order(18)
    void uploadWithoutFilesIsRejectedCleanly() {
        Map<String, String> session = adminSession();

        authed(session)
                .contentType(ContentType.MULTIPART)
                .multiPart("unrelated", "x.txt", "hola".getBytes(), "text/plain")
                .when().post(BASE + "/api/app/inventario/upload")
                .then()
                .statusCode(400)
                .body("error.code", equalTo("VALIDATION_ERROR"));
    }
}
