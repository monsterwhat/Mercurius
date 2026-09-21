package Controllers.Api.App;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import Models.AppSettings;
import Models.Articulos.ArticuloPrecio;
import Models.Articulos.Articulos;
import Models.Cabys;
import Models.Clients;
import Models.Users;
import Services.AppSettingsService;
import Services.ArticulosService;
import Services.CabysService;
import Services.ClientService;
import Services.LoginService;
import Services.cart.CartSessionStore;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * T37-prep acceptance suite for {@link PosResource}: real form-cookie login
 * over RestAssured (POST /Mercurius/j_security_check, seed admin/admin123 —
 * same recipe as CategoriaResourceTest/InventarioResourceTest) plus
 * {@code @TestSecurity(user="cashier2")} for the per-user cart isolation proof.
 *
 * <p><b>CSRF note:</b> quarkus-rest-csrf is active with defaults (cookie
 * {@code csrf-token}, header {@code X-CSRF-TOKEN}); every mutating call carries
 * the header matching the cookie minted by any prior GET. @TestSecurity
 * requests are CSRF-gated too (pinned by CsrfEnforcementTest), so those tests
 * mint their pair with a GET /cart probe.</p>
 *
 * <p><b>Fixtures:</b> minimal catalog built in-test through the SAME production
 * services (Cabys row + Articulos with one ArticuloPrecio whose
 * precioConUtilidad feeds {@code getPrecioEfectivo()}), an AppSettings row when
 * none is active (facturar pipeline requirement), a loyalty client and the
 * cashier2 system user.</p>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PosResourceTest extends support.ContextPathIsolation {

    private static final String BASE = "/Mercurius";
    private static final String POS = BASE + "/api/app/pos";
    private static final String CSRF_COOKIE = "csrf-token";
    private static final String CSRF_HEADER = "X-CSRF-TOKEN";

    private static final String CABYS_CODIGO = "T37POS0001";

    /**
     * Isolation-proof state shared across the three ordered phases. Static on
     * purpose: JUnit instantiates a fresh test class per method by default,
     * and the proof spans three methods.
     */
    private static Articulos isolationAdminItem;
    private static Articulos isolationCashierItem;

    @Inject
    ArticulosService articulosService;

    @Inject
    CabysService cabysService;

    @Inject
    ClientService clientService;

    @Inject
    LoginService loginService;

    @Inject
    AppSettingsService appSettingsService;

    @Inject
    CartSessionStore cartSessionStore;

    // ── Auth helpers (house style) ──────────────────────────────────────

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
        String token = csrfTokenOf(cookies);
        if (token != null) {
            spec.header(CSRF_HEADER, token);
        }
        return spec;
    }

    /** Tolerates either rest-csrf cookie naming across extension versions. */
    private static String csrfTokenOf(Map<String, String> cookies) {
        String token = cookies.get(CSRF_COOKIE);
        return token != null ? token : cookies.get("csrftoken");
    }

    /** CSRF pair for @TestSecurity identities: any GET mints the cookie. */
    private static Map<String, String> testIdentityJar() {
        Response probe = given().redirects().follow(false)
                .when().get(POS + "/cart");
        probe.then().statusCode(200);
        return new HashMap<>(probe.getCookies());
    }

    private static RequestSpecification testAuthed(Map<String, String> jar) {
        return given().redirects().follow(false)
                .cookies(jar)
                .header(CSRF_HEADER, csrfTokenOf(jar));
    }

    private static String uniqueSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    // ── Fixtures (production-service path, T8/T35 parity) ───────────────

    private Cabys ensureCabys() {
        Cabys cabys = cabysService.find(CABYS_CODIGO);
        if (cabys == null) {
            cabys = new Cabys(CABYS_CODIGO, "Articulo de prueba T37",
                    "Pruebas", "13", "https://example.com/cabys", "Activo");
            cabysService.create(cabys);
        }
        return cabys;
    }

    /** Article whose effective price is {@code precioConUtilidad} at 13% IVA. */
    private Articulos seedArticulo(String barcode, String precioConUtilidad) {
        Articulos articulo = new Articulos();
        articulo.setNombre("T37 Articulo " + uniqueSuffix());
        articulo.setCodigoBarra(barcode);
        articulo.setUnidadMedida("Unidad");
        articulo.setUnidadMedidaComercial("Unidad");
        articulo.setStatus(true);
        articulo.setProcessed(true);

        ArticuloPrecio precio = new ArticuloPrecio();
        precio.setArticulo(articulo);
        precio.setPrecioCostoSinIVA(new BigDecimal("1000"));
        precio.setPorcentajeUtilidad(new BigDecimal("20"));
        precio.setPrecioConUtilidad(new BigDecimal(precioConUtilidad));
        articulo.setPrecios(new ArrayList<>(List.of(precio)));
        articulo.setCodigoCabys(ensureCabys());

        articulosService.create(articulo);
        assertThat(articulo.getCodigo()).isNotNull();
        return articulo;
    }

    /** Exempt (0% IVA) article so totals equal the effective price verbatim. */
    private Articulos seedExemptArticulo(String barcode, String precioConUtilidad) {
        Articulos articulo = new Articulos();
        articulo.setNombre("T37 Exento " + uniqueSuffix());
        articulo.setCodigoBarra(barcode);
        articulo.setUnidadMedida("Unidad");
        articulo.setUnidadMedidaComercial("Unidad");
        articulo.setStatus(true);
        articulo.setProcessed(true);

        ArticuloPrecio precio = new ArticuloPrecio();
        precio.setArticulo(articulo);
        precio.setPrecioCostoSinIVA(new BigDecimal(precioConUtilidad));
        precio.setPorcentajeUtilidad(BigDecimal.ZERO);
        precio.setPrecioConUtilidad(new BigDecimal(precioConUtilidad));
        articulo.setPrecios(new ArrayList<>(List.of(precio)));

        Cabys exento = new Cabys("T37EX" + uniqueSuffix(), "Exento T37",
                "Pruebas", "0", "https://example.com/cabys", "Activo");
        cabysService.create(exento);
        articulo.setCodigoCabys(exento);

        articulosService.create(articulo);
        assertThat(articulo.getCodigo()).isNotNull();
        return articulo;
    }

    private Clients seedCliente(BigDecimal puntosAcumulados) {
        Clients cliente = new Clients();
        cliente.setName("Cliente T37 " + uniqueSuffix());
        cliente.setEmail("t37-" + uniqueSuffix() + "@mercurius.local");
        cliente.setIdType("Cedula Fisica");
        cliente.setIdNumber(uniqueSuffix() + uniqueSuffix());
        cliente.setStatus(true);
        cliente.setPuntosAcumulados(puntosAcumulados);
        clientService.create(cliente);
        assertThat(cliente.getCode()).isGreaterThan(0);
        return cliente;
    }

    private void ensureAppSettings() {
        if (appSettingsService.returnCurrent() != null) {
            return;
        }
        AppSettings settings = new AppSettings();
        settings.setEstatus(true);
        settings.setNombre("Cajero T37");
        settings.setNombreNegocio("Mercurius T37 SA");
        settings.setTipoIdentificacion("02");
        settings.setIdentificacion("310123456789");
        settings.setTelefono("88888888");
        settings.setCorreoElectronicoTributacion("t37@mercurius.local");
        settings.setProvedor("Mercurius");
        settings.setCodigoActividad("620101");
        settings.setCodigoSucursal("001");
        settings.setCodigoTerminal("001");
        appSettingsService.create(settings);
    }

    private void seedCashier2User() {
        if (loginService.findByUsername("cashier2") != null) {
            return;
        }
        Users cashier2 = new Users();
        cashier2.setUsername("cashier2");
        cashier2.setPassword("cashier2pass");
        cashier2.setGroupName("facturacion");
        cashier2.setStatus(true);
        cashier2.setEmail("cashier2@mercurius.local");
        loginService.create(cashier2);
    }

    // ── Scenarios ───────────────────────────────────────────────────────

    @Test
    @Order(1)
    void unauthenticatedCartSurfacesApi401Envelope() {
        // PosResource is an unannotated REST surface that self-checks the
        // SecurityIdentity and answers with its documented ApiResponse
        // envelope (no HTML redirect for API callers).
        given().redirects().follow(false)
                .when().get(POS + "/cart")
                .then()
                .statusCode(401)
                .body("error.code", equalTo("UNAUTHENTICATED"));
    }

    @Test
    @Order(2)
    void adminStartsWithAnEmptyCartSnapshot() {
        Map<String, String> session = adminSession();
        cartSessionStore.remove("admin");

        authed(session)
                .when().get(POS + "/cart")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("data.items", hasSize(0))
                .body("data.totalCarrito", equalTo(0))
                .body("data.hasOverrides", is(false))
                .body("data.authorizedBy", nullValue());
    }

    @Test
    @Order(3)
    void scanGrowsTheCartSnapshot() {
        Map<String, String> session = adminSession();
        cartSessionStore.remove("admin");
        // 13% IVA article: total = precioConUtilidad × 1.13 × cantidad.
        Articulos articulo = seedArticulo("T37SCAN" + uniqueSuffix(), "1200");

        authed(session)
                .contentType(ContentType.JSON)
                .body("{\"codigoBarra\":\"" + articulo.getCodigoBarra()
                        + "\",\"cantidad\":2}")
                .when().post(POS + "/scan")
                .then()
                .statusCode(200)
                .body("data.status", equalTo("ARTICULO_AGREGADO"))
                .body("data.severity", equalTo("info"))
                .body("data.summary", equalTo("Artículo agregado"));

        // Snapshot: one merged line, cantidad 2, total = 1200 × 1.13 × 2 = 2712.
        Response snapshot = authed(session)
                .when().get(POS + "/cart");
        snapshot.then()
                .statusCode(200)
                .body("data.items", hasSize(1))
                .body("data.items[0].articuloCodigo", equalTo(articulo.getCodigo().intValue()))
                .body("data.items[0].cantidad", equalTo(2))
                .body("data.items[0].precioEfectivo", equalTo(1200F));
        assertThat(new BigDecimal(snapshot.then().extract().jsonPath().getString("data.totalCarrito")))
                .isEqualByComparingTo("2712");
    }

    @Test
    @Order(4)
    void scanUnknownBarcodeReturnsErrorWithoutAdding() {
        Map<String, String> session = adminSession();
        cartSessionStore.remove("admin");

        authed(session)
                .contentType(ContentType.JSON)
                .body("{\"codigoBarra\":\"NO-EXISTE-" + uniqueSuffix() + "\",\"cantidad\":1}")
                .when().post(POS + "/scan")
                .then()
                .statusCode(200)
                .body("data.status", equalTo("ARTICULO_NO_ENCONTRADO"))
                .body("data.severity", equalTo("error"));

        authed(session)
                .when().get(POS + "/cart")
                .then()
                .body("data.items", hasSize(0));
    }

    @Test
    @Order(5)
    void scanInvalidCantidadIsRejected() {
        Map<String, String> session = adminSession();
        cartSessionStore.remove("admin");
        Articulos articulo = seedExemptArticulo("T37BAD" + uniqueSuffix(), "500");

        authed(session)
                .contentType(ContentType.JSON)
                .body("{\"codigoBarra\":\"" + articulo.getCodigoBarra()
                        + "\",\"cantidad\":0}")
                .when().post(POS + "/scan")
                .then()
                .statusCode(200)
                .body("data.status", equalTo("CANTIDAD_INVALIDA"))
                .body("data.severity", equalTo("error"));

        authed(session)
                .when().get(POS + "/cart")
                .then()
                .body("data.items", hasSize(0));
    }

    @Test
    @Order(6)
    void scanBlankCodigoIsRejected() {
        Map<String, String> session = adminSession();
        cartSessionStore.remove("admin");

        authed(session)
                .contentType(ContentType.JSON)
                .body("{\"codigoBarra\":\"   \",\"cantidad\":1}")
                .when().post(POS + "/scan")
                .then()
                .statusCode(200)
                .body("data.status", equalTo("CODIGO_VACIO"))
                .body("data.severity", equalTo("error"));
    }

    @Test
    @Order(7)
    void addByArticuloIdMergesQuantities() {
        Map<String, String> session = adminSession();
        cartSessionStore.remove("admin");
        Articulos articulo = seedExemptArticulo("T37ADD" + uniqueSuffix(), "300");

        for (int i = 0; i < 2; i++) {
            authed(session)
                    .contentType(ContentType.JSON)
                    .body("{\"articuloId\":" + articulo.getCodigo() + ",\"cantidad\":1}")
                    .when().post(POS + "/add")
                    .then()
                    .statusCode(200)
                    .body("data.status", equalTo("ARTICULO_AGREGADO"));
        }
        authed(session)
                .contentType(ContentType.JSON)
                .body("{\"articuloId\":" + articulo.getCodigo() + ",\"cantidad\":2}")
                .when().post(POS + "/add")
                .then()
                .statusCode(200);

        authed(session)
                .when().get(POS + "/cart")
                .then()
                .statusCode(200)
                .body("data.items", hasSize(1))
                .body("data.items[0].cantidad", equalTo(4))
                .body("data.totalCarrito", equalTo(1200F));
    }

    @Test
    @Order(8)
    void deleteItemRemovesLineAndUnknownCodeYields404() {
        Map<String, String> session = adminSession();
        cartSessionStore.remove("admin");
        Articulos articulo = seedExemptArticulo("T37DEL" + uniqueSuffix(), "700");
        authed(session)
                .contentType(ContentType.JSON)
                .body("{\"codigoBarra\":\"" + articulo.getCodigoBarra() + "\"}")
                .when().post(POS + "/scan")
                .then().statusCode(200);

        authed(session)
                .when().delete(POS + "/item/" + articulo.getCodigo())
                .then()
                .statusCode(200)
                .body("data.status", equalTo("ARTICULO_ELIMINADO"));

        authed(session)
                .when().get(POS + "/cart")
                .then()
                .body("data.items", hasSize(0));

        authed(session)
                .when().delete(POS + "/item/" + articulo.getCodigo())
                .then()
                .statusCode(404)
                .body("error.code", equalTo("LINEA_NO_ENCONTRADA"));
    }

    @Test
    @Order(9)
    void clientAssignmentSelectsClientAndUnknownYields404() {
        Map<String, String> session = adminSession();
        cartSessionStore.remove("admin");
        Clients cliente = seedCliente(new BigDecimal("50"));

        authed(session)
                .contentType(ContentType.JSON)
                .body("{\"clientCode\":" + cliente.getCode() + "}")
                .when().post(POS + "/client")
                .then()
                .statusCode(200)
                .body("data.code", equalTo(cliente.getCode()))
                .body("data.puntosAcumulados", equalTo(50F));

        authed(session)
                .when().get(POS + "/cart")
                .then()
                .body("data.clienteCode", equalTo(cliente.getCode()));

        authed(session)
                .contentType(ContentType.JSON)
                .body("{\"clientCode\":999999999}")
                .when().post(POS + "/client")
                .then()
                .statusCode(404)
                .body("error.code", equalTo("CLIENTE_NO_ENCONTRADO"));
    }

    @Test
    @Order(10)
    void paymentEntriesStageAndComputeVuelto() {
        Map<String, String> session = adminSession();
        cartSessionStore.remove("admin");
        Articulos articulo = seedExemptArticulo("T37PAY" + uniqueSuffix(), "1356");
        authed(session)
                .contentType(ContentType.JSON)
                .body("{\"codigoBarra\":\"" + articulo.getCodigoBarra() + "\"}")
                .when().post(POS + "/scan")
                .then().statusCode(200);

        authed(session)
                .contentType(ContentType.JSON)
                .body("[{\"metodoPago\":\"01\",\"monto\":2000}]")
                .when().post(POS + "/payment-entries")
                .then()
                .statusCode(200)
                .body("data.totalPagado", equalTo(2000))
                .body("data.vuelto", equalTo(644))
                .body("data.vueltoString", containsString("Vuelto"));
    }

    // ── User isolation proof (three phases) ─────────────────────────────
    //
    // @TestSecurity authenticates EVERY request of its method through the
    // TestAuthMechanism, so admin-cookie calls and cashier2 calls can never be
    // mixed inside one @TestSecurity method. The proof therefore spans three
    // ordered methods: admin scans first, cashier2 scans second (and must NOT
    // see the admin line), then admin must NOT see the cashier2 line.

    @Test
    @Order(11)
    void isolationPhase1AdminScansIntoOwnCart() {
        Map<String, String> session = adminSession();
        cartSessionStore.remove("admin");
        isolationAdminItem = seedExemptArticulo("T37ADM" + uniqueSuffix(), "111");

        authed(session)
                .contentType(ContentType.JSON)
                .body("{\"codigoBarra\":\"" + isolationAdminItem.getCodigoBarra() + "\"}")
                .when().post(POS + "/scan")
                .then().statusCode(200)
                .body("data.status", equalTo("ARTICULO_AGREGADO"));

        authed(session)
                .when().get(POS + "/cart")
                .then()
                .statusCode(200)
                .body("data.items", hasSize(1))
                .body("data.items[0].articuloCodigo",
                        equalTo(isolationAdminItem.getCodigo().intValue()));
    }

    @Test
    @Order(12)
    @TestSecurity(user = "cashier2", roles = {"facturacion"})
    void isolationPhase2Cashier2SeesOnlyOwnLine() {
        seedCashier2User();
        cartSessionStore.remove("cashier2");
        isolationCashierItem = seedExemptArticulo("T37CSH" + uniqueSuffix(), "222");

        Map<String, String> jar = testIdentityJar();
        testAuthed(jar)
                .contentType(ContentType.JSON)
                .body("{\"codigoBarra\":\"" + isolationCashierItem.getCodigoBarra() + "\"}")
                .when().post(POS + "/scan")
                .then().statusCode(200)
                .body("data.status", equalTo("ARTICULO_AGREGADO"));

        // cashier2's cart holds ONLY their own scan: phase-1's admin article
        // never bleeds across the username key.
        testAuthed(jar)
                .when().get(POS + "/cart")
                .then()
                .statusCode(200)
                .body("data.items", hasSize(1))
                .body("data.items[0].articuloCodigo",
                        equalTo(isolationCashierItem.getCodigo().intValue()));
    }

    @Test
    @Order(13)
    void isolationPhase3AdminNeverSeesCashier2Line() {
        Map<String, String> session = adminSession();

        // No reset here on purpose: admin's entry still holds exactly the
        // phase-1 scan, proving cashier2's phase-2 article stayed isolated.
        Response snapshot = authed(session)
                .when().get(POS + "/cart");
        snapshot.then()
                .statusCode(200)
                .body("data.items", hasSize(1))
                .body("data.items[0].articuloCodigo",
                        equalTo(isolationAdminItem.getCodigo().intValue()));
        String firstCodigo = snapshot.then().extract()
                .jsonPath().getString("data.items[0].articuloCodigo");
        assertThat(String.valueOf(isolationCashierItem.getCodigo()))
                .as("cashier2's article must not appear anywhere in admin's cart")
                .isNotEqualTo(firstCodigo);
    }

    @Test
    @Order(14)
    void facturarWithInsufficientPaymentIsRejected409AndKeepsCart() {
        ensureAppSettings();
        Map<String, String> session = adminSession();
        cartSessionStore.remove("admin");
        Articulos articulo = seedExemptArticulo("T37FAL" + uniqueSuffix(), "1000");

        authed(session)
                .contentType(ContentType.JSON)
                .body("{\"codigoBarra\":\"" + articulo.getCodigoBarra() + "\"}")
                .when().post(POS + "/scan")
                .then().statusCode(200);

        authed(session)
                .contentType(ContentType.JSON)
                .body("{\"tipoDocumento\":\"04\","
                        + "\"pagos\":[{\"metodoPago\":\"01\",\"monto\":999}]}")
                .when().post(POS + "/facturar")
                .then()
                .statusCode(409)
                .body("error.code", equalTo("FALTANTE_DE_PAGO"));

        // verificarPago parity: the sale stays intact for correction.
        authed(session)
                .when().get(POS + "/cart")
                .then()
                .body("data.items", hasSize(1))
                .body("data.totalCarrito", equalTo(1000F));
    }

    @Test
    @Order(15)
    void facturarHappyPathYieldsPdfUrlStreamingPdfBytes() {
        ensureAppSettings();
        Map<String, String> session = adminSession();
        cartSessionStore.remove("admin");
        Articulos articulo = seedExemptArticulo("T37OK" + uniqueSuffix(), "1000");

        authed(session)
                .contentType(ContentType.JSON)
                .body("{\"codigoBarra\":\"" + articulo.getCodigoBarra() + "\"}")
                .when().post(POS + "/scan")
                .then().statusCode(200);

        Response facturado = authed(session)
                .contentType(ContentType.JSON)
                .body("{\"tipoDocumento\":\"04\","
                        + "\"pagos\":[{\"metodoPago\":\"01\",\"monto\":1000}],"
                        + "\"puntosARedimir\":0}")
                .when().post(POS + "/facturar");
        facturado.then()
                .statusCode(200)
                .body("data.pdfUrl", notNullValue())
                .body("data.comprobanteId", notNullValue())
                .body("data.haciendaEstado", notNullValue());
        String pdfUrl = facturado.then().extract().jsonPath().getString("data.pdfUrl");
        assertThat(pdfUrl).startsWith("/Mercurius/api/app/pos/facturas/tiqueteElectronico_");

        // The URL streams the generated PDF bytes (octet-stream, %PDF magic).
        byte[] bytes = authed(session)
                .when().get(pdfUrl)
                .then()
                .statusCode(200)
                .contentType(containsString("octet-stream"))
                .extract().asByteArray();
        assertThat(bytes.length).isGreaterThan(4);
        assertThat(new String(bytes, 0, 4, StandardCharsets.US_ASCII))
                .isEqualTo("%PDF");

        // Pipeline cleanup parity: clearPago + carritoService.clear.
        authed(session)
                .when().get(POS + "/cart")
                .then()
                .body("data.items", hasSize(0))
                .body("data.totalPagado", equalTo(0));
    }

    @Test
    @Order(16)
    void priceOverrideRequiresSupervisorAuthorization() {
        ensureAppSettings();
        Map<String, String> session = adminSession();
        cartSessionStore.remove("admin");
        Articulos articulo = seedExemptArticulo("T37OVR" + uniqueSuffix(), "1000");

        // Supervisor-priced line (₡500 instead of ₡1000).
        authed(session)
                .contentType(ContentType.JSON)
                .body("{\"articuloId\":" + articulo.getCodigo()
                        + ",\"cantidad\":1,\"precioPersonalizado\":500}")
                .when().post(POS + "/add")
                .then().statusCode(200);

        authed(session)
                .contentType(ContentType.JSON)
                .body("{\"tipoDocumento\":\"04\","
                        + "\"pagos\":[{\"metodoPago\":\"01\",\"monto\":500}]}")
                .when().post(POS + "/facturar")
                .then()
                .statusCode(403)
                .body("error.code", equalTo("SUPERVISOR_REQUIRED"));

        // Wrong supervisor credentials → 401 envelope (AppAuthResource shape).
        authed(session)
                .contentType(ContentType.JSON)
                .body("{\"username\":\"admin\",\"password\":\"wrong-pass\"}")
                .when().post(POS + "/override-authorize")
                .then()
                .statusCode(401)
                .body("error.code", equalTo("INVALID_CREDENTIALS"));

        // Correct credentials record the authorization on the caller's session.
        authed(session)
                .contentType(ContentType.JSON)
                .body("{\"username\":\"admin\",\"password\":\"admin123\"}")
                .when().post(POS + "/override-authorize")
                .then()
                .statusCode(200)
                .body("data.authorizedBy", equalTo("admin"))
                .body("data.roles", hasSize(6));

        authed(session)
                .when().get(POS + "/cart")
                .then()
                .body("data.hasOverrides", is(true))
                .body("data.authorizedBy", equalTo("admin"));

        // Authorized facturar now flows through the whole pipeline.
        authed(session)
                .contentType(ContentType.JSON)
                .body("{\"tipoDocumento\":\"04\","
                        + "\"pagos\":[{\"metodoPago\":\"01\",\"monto\":500}]}")
                .when().post(POS + "/facturar")
                .then()
                .statusCode(200)
                .body("data.pdfUrl", notNullValue());
    }

    @Test
    @Order(17)
    void cancelClearsCartPaymentsAndOverrideState() {
        Map<String, String> session = adminSession();
        cartSessionStore.remove("admin");
        Articulos articulo = seedExemptArticulo("T37CAN" + uniqueSuffix(), "800");

        authed(session)
                .contentType(ContentType.JSON)
                .body("{\"articuloId\":" + articulo.getCodigo()
                        + ",\"cantidad\":1,\"precioPersonalizado\":100}")
                .when().post(POS + "/add")
                .then().statusCode(200);

        authed(session)
                .contentType(ContentType.JSON)
                .body("[{\"metodoPago\":\"01\",\"monto\":900}]")
                .when().post(POS + "/payment-entries")
                .then().statusCode(200);

        authed(session)
                .contentType(ContentType.JSON)
                .body("{\"username\":\"admin\",\"password\":\"admin123\"}")
                .when().post(POS + "/override-authorize")
                .then().statusCode(200);

        authed(session)
                .contentType(ContentType.JSON)
                .when().post(POS + "/cancel")
                .then()
                .statusCode(200)
                .body("data.status", equalTo("CANCELADO"));

        authed(session)
                .when().get(POS + "/cart")
                .then()
                .statusCode(200)
                .body("data.items", hasSize(0))
                .body("data.totalPagado", equalTo(0))
                .body("data.authorizedBy", nullValue());
    }
}
