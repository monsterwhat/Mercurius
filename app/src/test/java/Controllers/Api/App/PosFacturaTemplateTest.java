package Controllers.Api.App;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * T37 template-phase acceptance suite: the Qute POS page
 * ({@code GET /app/pos}) plus every HTMX fragment/form twin of
 * {@link PosResource}. Auth mirrors {@code PosResourceTest}: real form-cookie
 * login over RestAssured (seed admin/admin123) with the X-CSRF-TOKEN dance,
 * and {@code @TestSecurity} ordered phases for the two-cashier fragment
 * isolation proof.
 *
 * <p>Every {@code -form} endpoint answers text/html fragments, so assertions
 * are marker-based (ids, data-* attributes, server-computed totals rendered by
 * Qute) instead of JSON paths.</p>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Tag("t37-templates")
class PosFacturaTemplateTest extends support.ContextPathIsolation {

    private static final String BASE = "/Mercurius";
    private static final String PAGE_URL = BASE + "/app/pos";
    private static final String POS = BASE + "/api/app/pos";
    private static final String CSRF_COOKIE = "csrf-token";
    private static final String CSRF_HEADER = "X-CSRF-TOKEN";

    private static final String CABYS_CODIGO = "T37TPL0001";

    /** Shared across ordered isolation phases (fresh test instance per method). */
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

    // ── Auth helpers (house style, PosResourceTest recipe) ──────────────

    private static Map<String, String> adminSession() {
        return loginAs("admin", "admin123");
    }

    private static Map<String, String> loginAs(String username, String password) {
        Response loginPage = given().redirects().follow(false)
                .when().get(BASE + "/login");
        loginPage.then().statusCode(200);
        Map<String, String> cookies = new HashMap<>(loginPage.getCookies());

        Response login = given().redirects().follow(false)
                .cookies(cookies)
                .contentType(ContentType.URLENC)
                .formParam("j_username", username)
                .formParam("j_password", password)
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

    private static String csrfTokenOf(Map<String, String> cookies) {
        String token = cookies.get(CSRF_COOKIE);
        return token != null ? token : cookies.get("csrftoken");
    }

    /** CSRF pair for @TestSecurity identities: any GET mints the cookie. */
    private Map<String, String> testIdentityJar() {
        Response probe = authedlessGet(POS + "/cart");
        probe.then().statusCode(200);
        return new HashMap<>(probe.getCookies());
    }

    private static RequestSpecification testAuthed(Map<String, String> jar) {
        return given().redirects().follow(false)
                .cookies(jar)
                .header(CSRF_HEADER, csrfTokenOf(jar));
    }

    private static Response authedlessGet(String url) {
        return given().redirects().follow(false).when().get(url);
    }

    private static String uniqueSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    // ── Fixtures (production-service path, PosResourceTest parity) ──────

    private Cabys ensureCabys() {
        Cabys cabys = cabysService.find(CABYS_CODIGO);
        if (cabys == null) {
            cabys = new Cabys(CABYS_CODIGO, "Articulo plantilla T37",
                    "Pruebas", "13", "https://example.com/cabys", "Activo");
            cabysService.create(cabys);
        }
        return cabys;
    }

    /** Exempt (0% IVA) article so rendered totals equal the price verbatim. */
    private Articulos seedExemptArticulo(String barcode, String precioConUtilidad) {
        Articulos articulo = new Articulos();
        articulo.setNombre("T37TPL " + uniqueSuffix());
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

        Cabys exento = new Cabys("T37TPLEX" + uniqueSuffix(), "Exento T37TPL",
                "Pruebas", "0", "https://example.com/cabys", "Activo");
        cabysService.create(exento);
        articulo.setCodigoCabys(exento);

        articulosService.create(articulo);
        assertThat(articulo.getCodigo()).isNotNull();
        return articulo;
    }

    private Clients seedCliente(BigDecimal puntosAcumulados) {
        return seedNamedClient(puntosAcumulados, uniqueSuffix());
    }

    /** Client whose searchable token is URL-safe (no spaces). */
    private Clients seedNamedClient(BigDecimal puntosAcumulados, String token) {
        Clients cliente = new Clients();
        cliente.setName("T37TPL" + token);
        cliente.setEmail("t37tpl-" + token + "@mercurius.local");
        cliente.setIdType("Cedula Fisica");
        cliente.setIdNumber(token + uniqueSuffix());
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
        settings.setNombre("Cajero T37TPL");
        settings.setNombreNegocio("Mercurius T37TPL SA");
        settings.setTipoIdentificacion("02");
        settings.setIdentificacion("310123456789");
        settings.setTelefono("88888888");
        settings.setCorreoElectronicoTributacion("t37tpl@mercurius.local");
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

    private void scanBarcode(Map<String, String> session, String barcode) {
        authed(session)
                .contentType(ContentType.JSON)
                .body("{\"codigoBarra\":\"" + barcode + "\",\"cantidad\":1}")
                .when().post(POS + "/scan")
                .then().statusCode(200);
    }

    // ── Scenarios ───────────────────────────────────────────────────────

    @Test
    @Order(1)
    void anonymousPageRequestIsChallengedToLogin() {
        given().redirects().follow(false)
                .when().get(PAGE_URL)
                .then()
                .statusCode(302)
                .header("Location", containsString("/login"));
    }

    @Test
    @Order(2)
    void posPageRendersWithCartPanelBarcodeFocusAndDialogMarkers() {
        Map<String, String> session = adminSession();
        cartSessionStore.remove("admin");

        authed(session)
                .when().get(PAGE_URL)
                .then()
                .statusCode(200)
                .contentType(ContentType.HTML)
                .body(containsString("<html"))
                .body(containsString("Facturación POS"))
                // barcode-first capture
                .body(containsString("id=\"barcode-input\""))
                .body(containsString("autofocus"))
                .body(containsString("name=\"codigoBarra\""))
                .body(containsString("hx-post=\"/Mercurius/api/app/pos/scan-form\""))
                // live sale panel + actions
                .body(containsString("id=\"cart-panel\""))
                .body(containsString("id=\"btn-facturar\""))
                .body(containsString("id=\"btn-cancelar\""))
                .body(containsString("hx-post=\"/Mercurius/api/app/pos/facturar-form\""))
                .body(containsString("hx-post=\"/Mercurius/api/app/pos/cancel-form\""))
                // dialogs + tipo-cambio badge + keyboard-first refocus glue
                .body(containsString("id=\"modal-pago\""))
                .body(containsString("id=\"modal-cliente\""))
                .body(containsString("id=\"modal-autorizacion-body\""))
                .body(containsString("id=\"badge-tipo-cambio\""))
                .body(containsString("id=\"forma-tipo-documento\""))
                .body(containsString("htmx:afterSwap"));
    }

    @Test
    @Order(3)
    void cartPanelFragmentRendersStandaloneWithoutLayout() {
        Map<String, String> session = adminSession();
        cartSessionStore.remove("admin");

        authed(session)
                .when().get(POS + "/cart-panel")
                .then()
                .statusCode(200)
                .contentType(ContentType.HTML)
                .body(containsString("id=\"cart-panel\""))
                .body(containsString("El carrito está vacío"))
                .body(not(containsString("<html")));
    }

    @Test
    @Order(4)
    void unauthenticatedFragmentCallSurfacesEnvelope() {
        given().redirects().follow(false)
                .when().get(POS + "/cart-panel")
                .then()
                .statusCode(401);
    }

    @Test
    @Order(5)
    void scanFormAddsLineAndRedrawsPanel() {
        Map<String, String> session = adminSession();
        cartSessionStore.remove("admin");
        Articulos articulo = seedExemptArticulo("T37TPLSCAN" + uniqueSuffix(), "1500");

        authed(session)
                .contentType(ContentType.URLENC)
                .formParam("codigoBarra", articulo.getCodigoBarra())
                .formParam("cantidad", "2")
                .when().post(POS + "/scan-form")
                .then()
                .statusCode(200)
                .contentType(ContentType.HTML)
                .body(containsString("id=\"cart-panel\""))
                .body(containsString(articulo.getNombre()))
                .body(containsString("<strong>Artículo agregado</strong>"));

        // Server truth mirrors what the fragment painted.
        authed(session)
                .when().get(POS + "/cart")
                .then()
                .statusCode(200)
                .body("data.items", hasSize(1))
                .body("data.items[0].cantidad", equalTo(2))
                .body("data.totalCarrito", equalTo(3000.0f));
    }

    @Test
    @Order(6)
    void scanFormUnknownBarcodeShowsErrorWithoutAdding() {
        Map<String, String> session = adminSession();
        cartSessionStore.remove("admin");

        authed(session)
                .contentType(ContentType.URLENC)
                .formParam("codigoBarra", "NO-EXISTE-" + uniqueSuffix())
                .when().post(POS + "/scan-form")
                .then()
                .statusCode(200)
                .body(containsString("<strong>Artículo no encontrado</strong>"))
                .body(containsString("El carrito está vacío"));
    }

    @Test
    @Order(7)
    void qtyFormPlusGrowsAndMinusToZeroRemovesViaRemoveArticulo() {
        Map<String, String> session = adminSession();
        cartSessionStore.remove("admin");
        Articulos articulo = seedExemptArticulo("T37TPLQTY" + uniqueSuffix(), "700");
        scanBarcode(session, articulo.getCodigoBarra());

        authed(session)
                .contentType(ContentType.URLENC)
                .formParam("delta", "2")
                .when().post(POS + "/item/" + articulo.getCodigo() + "/qty-form")
                .then()
                .statusCode(200)
                .body(containsString("<strong>Cantidad actualizada</strong>"));

        authed(session)
                .when().get(POS + "/cart")
                .then()
                .body("data.items[0].cantidad", equalTo(3));

        // Down to zero delegates to CarritoService.removeArticulo.
        authed(session)
                .contentType(ContentType.URLENC)
                .formParam("delta", "-3")
                .when().post(POS + "/item/" + articulo.getCodigo() + "/qty-form")
                .then()
                .statusCode(200)
                .body(containsString("<strong>Artículo eliminado</strong>"))
                .body(containsString("El carrito está vacío"));

        authed(session)
                .when().get(POS + "/cart")
                .then()
                .body("data.items", hasSize(0));
    }

    @Test
    @Order(8)
    void qtyFormInvalidDeltaIsRejectedInFragment() {
        Map<String, String> session = adminSession();
        cartSessionStore.remove("admin");
        Articulos articulo = seedExemptArticulo("T37TPLQTY0" + uniqueSuffix(), "100");
        scanBarcode(session, articulo.getCodigoBarra());

        authed(session)
                .contentType(ContentType.URLENC)
                .formParam("delta", "0")
                .when().post(POS + "/item/" + articulo.getCodigo() + "/qty-form")
                .then()
                .statusCode(200)
                .body(containsString("<strong>Delta inválido</strong>"));
    }

    @Test
    @Order(9)
    void removeFormDeletesLineAndUnknownCodeReportsError() {
        Map<String, String> session = adminSession();
        cartSessionStore.remove("admin");
        Articulos articulo = seedExemptArticulo("T37TPLREM" + uniqueSuffix(), "900");
        scanBarcode(session, articulo.getCodigoBarra());

        authed(session)
                .when().post(POS + "/item/" + articulo.getCodigo() + "/remove-form")
                .then()
                .statusCode(200)
                .body(containsString("<strong>Artículo eliminado</strong>"))
                .body(containsString("El carrito está vacío"));

        authed(session)
                .when().post(POS + "/item/" + articulo.getCodigo() + "/remove-form")
                .then()
                .statusCode(200)
                .body(containsString("<strong>El artículo no está en el carrito</strong>"));
    }

    @Test
    @Order(10)
    void clientSearchTypeaheadReturnsCompactMatchesCappedAtTen() {
        Map<String, String> session = adminSession();
        String token = uniqueSuffix();
        Clients cliente = seedNamedClient(new BigDecimal("40"), token);

        Response matches = authed(session)
                .when().get(POS + "/client-search?q=" + token);
        matches.then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("data[0].code", equalTo(cliente.getCode()))
                .body("data[0].name", equalTo(cliente.getName()));
        List<?> hits = matches.then().extract().jsonPath().getList("data");
        assertThat(hits.size()).isLessThanOrEqualTo(10);

        // Blank q lists the first page (legacy dialog behavior).
        authed(session)
                .when().get(POS + "/client-search")
                .then()
                .statusCode(200)
                .body("data", hasSize(is(not(0))));

        // No garbage matches for nonsense queries.
        authed(session)
                .when().get(POS + "/client-search?q=SIN-COINCIDENCIA-" + uniqueSuffix())
                .then()
                .statusCode(200)
                .body("data", hasSize(0));
    }

    @Test
    @Order(11)
    void clientPickerFragmentListsSelectableRows() {
        Map<String, String> session = adminSession();
        String token = uniqueSuffix();
        Clients cliente = seedNamedClient(new BigDecimal("15"), token);

        authed(session)
                .when().get(POS + "/client-picker?q=" + token)
                .then()
                .statusCode(200)
                .contentType(ContentType.HTML)
                .body(containsString("id=\"client-picker-fragmento\""))
                .body(containsString(cliente.getName()))
                .body(containsString("hx-post=\"/Mercurius/api/app/pos/client-select-form\""))
                .body(containsString("value=\"" + cliente.getCode() + "\""))
                .body(containsString("Seleccionar"))
                .body(not(containsString("<html")));
    }

    @Test
    @Order(12)
    void clientSelectFormAssignsClientAndResetsStagedPoints() {
        Map<String, String> session = adminSession();
        cartSessionStore.remove("admin");
        Clients cliente = seedCliente(new BigDecimal("60"));

        authed(session)
                .contentType(ContentType.URLENC)
                .formParam("clientCode", String.valueOf(cliente.getCode()))
                .when().post(POS + "/client-select-form")
                .then()
                .statusCode(200)
                .body(containsString("id=\"cart-panel\""))
                .body(containsString(cliente.getName()))
                .body(containsString("60.00 pts"))
                .body(containsString("<strong>Cliente asignado</strong>"));

        authed(session)
                .when().get(POS + "/cart")
                .then()
                .body("data.clienteCode", equalTo(cliente.getCode()));

        // Unknown client keeps the panel alive with the error envelope.
        authed(session)
                .contentType(ContentType.URLENC)
                .formParam("clientCode", "999999999")
                .when().post(POS + "/client-select-form")
                .then()
                .statusCode(200)
                .body(containsString("<strong>No existe un cliente con ese código</strong>"));
    }

    @Test
    @Order(13)
    void puntosPreviewClampsToBalanceWithoutMutatingCart() {
        Map<String, String> session = adminSession();
        cartSessionStore.remove("admin");
        Clients cliente = seedCliente(new BigDecimal("25"));
        selectClient(session, cliente.getCode());

        authed(session)
                .when().get(POS + "/puntos-preview?puntos=5")
                .then()
                .statusCode(200)
                .body("data.solicitados", equalTo(5))
                .body("data.aplicado", equalTo(5))
                .body("data.balance", equalTo(25.0f));

        authed(session)
                .when().get(POS + "/puntos-preview?puntos=99999")
                .then()
                .statusCode(200)
                .body("data.aplicado", equalTo(25.0f));

        // Pure preview: nothing staged.
        authed(session)
                .when().get(POS + "/cart")
                .then()
                .body("data.descuentoPuntos", equalTo(0));

        // Without a client the preview refuses (fresh session, never selected).
        cartSessionStore.remove("admin");
        authed(session)
                .when().get(POS + "/puntos-preview?puntos=5")
                .then()
                .statusCode(400)
                .body("error.code", equalTo("PUNTOS_SIN_CLIENTE"));
    }

    @Test
    @Order(14)
    void puntosFormStagesDiscountShownOnPanelAndClearsOnZero() {
        Map<String, String> session = adminSession();
        cartSessionStore.remove("admin");
        Clients cliente = seedCliente(new BigDecimal("30"));
        selectClient(session, cliente.getCode());
        Articulos articulo = seedExemptArticulo("T37TPLPTS" + uniqueSuffix(), "1000");
        scanBarcode(session, articulo.getCodigoBarra());

        authed(session)
                .contentType(ContentType.URLENC)
                .formParam("puntosARedimir", "12")
                .when().post(POS + "/puntos-form")
                .then()
                .statusCode(200)
                .body(containsString("<strong>Descuento por puntos aplicado</strong>"))
                .body(containsString("Puntos redimidos"));

        authed(session)
                .when().get(POS + "/cart")
                .then()
                .body("data.descuentoPuntos", equalTo(12));

        authed(session)
                .contentType(ContentType.URLENC)
                .formParam("puntosARedimir", "0")
                .when().post(POS + "/puntos-form")
                .then()
                .statusCode(200)
                .body(containsString("Descuento por puntos retirado"));

        authed(session)
                .when().get(POS + "/cart")
                .then()
                .body("data.descuentoPuntos", equalTo(0));
    }

    @Test
    @Order(15)
    void tipoCambioEndpointAnswersBadgeEnvelope() {
        Map<String, String> session = adminSession();
        Response badge = authed(session)
                .when().get(POS + "/tipo-cambio");
        badge.then().statusCode(200).contentType(ContentType.JSON);
        String body = badge.then().extract().asString();
        assertThat(body).contains("disponible");
        assertThat(body).contains("venta");
        assertThat(body).contains("compra");
    }

    @Test
    @Order(16)
    void paymentDialogRendersRowsAndServerComputedTotalsAfterStaging() {
        Map<String, String> session = adminSession();
        cartSessionStore.remove("admin");
        Articulos articulo = seedExemptArticulo("T37TPLPAY" + uniqueSuffix(), "1356");
        scanBarcode(session, articulo.getCodigoBarra());

        authed(session)
                .when().get(POS + "/payment-dialog")
                .then()
                .statusCode(200)
                .contentType(ContentType.HTML)
                .body(containsString("id=\"dialog-pago-body\""))
                .body(containsString("name=\"metodoPago\""))
                .body(containsString("name=\"monto\""))
                .body(containsString("js-add-pago"))
                .body(containsString("Calcular vuelto"))
                .body(containsString("Efectivo"))
                .body(containsString("Tarjeta"))
                .body(not(containsString("<html")));

        authed(session)
                .contentType(ContentType.URLENC)
                .formParam("metodoPago", "01").formParam("monto", "1000")
                .formParam("metodoPago", "02").formParam("monto", "356")
                .when().post(POS + "/payment-entries-form")
                .then()
                .statusCode(200)
                .body(containsString("Pagos registrados"))
                .body(containsString("id=\"dialog-total-pagado\""))
                .body(containsString("1356"))
                .body(containsString("Vuelto"));

        authed(session)
                .when().get(POS + "/cart")
                .then()
                .body("data.totalPagado", equalTo(1356))
                .body("data.vuelto", equalTo(0));

        // Empty submission re-renders the dialog with the validation error.
        authed(session)
                .when().post(POS + "/payment-entries-form")
                .then()
                .statusCode(200)
                .body(containsString("Debe enviar al menos una entrada de pago"));
    }

    @Test
    @Order(17)
    void facturarFormInsufficientPaymentShowsErrorAndKeepsSale() {
        ensureAppSettings();
        Map<String, String> session = adminSession();
        cartSessionStore.remove("admin");
        Articulos articulo = seedExemptArticulo("T37TPLFAIL" + uniqueSuffix(), "1000");
        scanBarcode(session, articulo.getCodigoBarra());

        authed(session)
                .contentType(ContentType.URLENC)
                .formParam("tipoDocumento", "04")
                .formParam("metodoPago", "01").formParam("monto", "500")
                .when().post(POS + "/facturar-form")
                .then()
                .statusCode(200)
                .body(containsString("No se pudo facturar"))
                .body(containsString("FALTANTE_DE_PAGO"))
                .body(containsString("hx-swap-oob=\"true\""));

authed(session)
                .when().get(POS + "/cart")
                .then()
                .statusCode(200)
                .body("data.items", hasSize(1))
                .body("data.totalCarrito", equalTo(1000.0f));
    }

    @Test
    @Order(18)
    void facturarFormFacturaElectronicaRequiresSelectedClient() {
        ensureAppSettings();
        Map<String, String> session = adminSession();
        cartSessionStore.remove("admin");
        Articulos articulo = seedExemptArticulo("T37TPLCLI" + uniqueSuffix(), "800");
        scanBarcode(session, articulo.getCodigoBarra());

        authed(session)
                .contentType(ContentType.URLENC)
                .formParam("tipoDocumento", "01")
                .formParam("metodoPago", "01").formParam("monto", "800")
                .when().post(POS + "/facturar-form")
                .then()
                .statusCode(200)
                .body(containsString("CLIENTE_REQUERIDO"));
    }

    @Test
    @Order(19)
    void overrideAuthorizeFormRejectsWrongCredentialsAndAcceptsSupervisor() {
        ensureAppSettings();
        Map<String, String> session = adminSession();
        cartSessionStore.remove("admin");
        Articulos articulo = seedExemptArticulo("T37TPLOVR" + uniqueSuffix(), "1000");

        authed(session)
                .contentType(ContentType.JSON)
                .body("{\"articuloId\":" + articulo.getCodigo()
                        + ",\"cantidad\":1,\"precioPersonalizado\":250}")
                .when().post(POS + "/add")
                .then().statusCode(200);

        authed(session)
                .contentType(ContentType.URLENC)
                .formParam("username", "admin").formParam("password", "wrong-pass")
                .when().post(POS + "/override-authorize-form")
                .then()
                .statusCode(200)
                .body(containsString("Usuario o contraseña incorrectos"))
                .body(not(containsString("Autorización registrada")));

        authed(session)
                .contentType(ContentType.URLENC)
                .formParam("username", "admin").formParam("password", "admin123")
                .when().post(POS + "/override-authorize-form")
                .then()
                .statusCode(200)
                .body(containsString("Autorización registrada"))
                .body(containsString("hx-swap-oob=\"true\""))
                .body(containsString("Autoriza: admin"));

        authed(session)
                .when().get(POS + "/cart")
                .then()
                .body("data.authorizedBy", equalTo("admin"));
    }

    @Test
    @Order(20)
    void facturarFormFailsWithComprobanteErrorAndKeepsSale() {
        ensureAppSettings();
        Map<String, String> session = adminSession();
        cartSessionStore.remove("admin");
        Articulos articulo = seedExemptArticulo("T37TPLE2E" + uniqueSuffix(), "1000");
        Clients cliente = seedCliente(new BigDecimal("50"));

        scanBarcode(session, articulo.getCodigoBarra());
        selectClient(session, cliente.getCode());

        authed(session)
                .contentType(ContentType.URLENC)
                .formParam("puntosARedimir", "10")
                .when().post(POS + "/puntos-form")
                .then().statusCode(200);

        authed(session)
                .contentType(ContentType.URLENC)
                .formParam("metodoPago", "01").formParam("monto", "600")
                .formParam("metodoPago", "02").formParam("monto", "500")
                .when().post(POS + "/payment-entries-form")
                .then().statusCode(200);

        Response facturado = authed(session)
                .contentType(ContentType.URLENC)
                .formParam("tipoDocumento", "04")
                .when().post(POS + "/facturar-form");
        facturado.then()
                .statusCode(200)
                .contentType(ContentType.HTML)
                .body(containsString("No se pudo facturar"))
                .body(containsString("COMPROBANTE_ERROR"))
                .body(containsString("hx-swap-oob=\"true\""));
        // On COMPROBANTE_ERROR the cart is NOT cleared (sale is kept for retry)
        authed(session)
                .when().get(POS + "/cart")
                .then()
                .statusCode(200)
                .body("data.items", hasSize(1));
    }

    @Test
    @Order(21)
    void cancelFormReturnsFreshEmptyPanel() {
        Map<String, String> session = adminSession();
        cartSessionStore.remove("admin");
        Articulos articulo = seedExemptArticulo("T37TPLCAN" + uniqueSuffix(), "450");
        scanBarcode(session, articulo.getCodigoBarra());

        authed(session)
                .when().post(POS + "/cancel-form")
                .then()
                .statusCode(200)
                .body(containsString("El carrito está vacío"));

        authed(session)
                .when().get(POS + "/cart")
                .then()
                .body("data.items", hasSize(0));
    }

    // ── Two-cashier fragment isolation (ordered phases, PosResourceTest
    //    recipe: @TestSecurity owns the whole method, so the proof spans
    //    three methods) ───────────────────────────────────────────────────

    @Test
    @Order(22)
    void isolationPhase1AdminScansIntoOwnPanel() {
        Map<String, String> session = adminSession();
        cartSessionStore.remove("admin");
        isolationAdminItem = seedExemptArticulo("T37TPLADM" + uniqueSuffix(), "111");

        scanBarcode(session, isolationAdminItem.getCodigoBarra());

        authed(session)
                .when().get(POS + "/cart-panel")
                .then()
                .statusCode(200)
                .body(containsString(isolationAdminItem.getNombre()));
    }

    @Test
    @Order(23)
    @TestSecurity(user = "cashier2", roles = {"facturacion"})
    void isolationPhase2Cashier2PanelNeverShowsAdminLine() {
        seedCashier2User();
        cartSessionStore.remove("cashier2");
        isolationCashierItem = seedExemptArticulo("T37TPLCSH" + uniqueSuffix(), "222");

        Map<String, String> jar = testIdentityJar();
        testAuthed(jar)
                .contentType(ContentType.URLENC)
                .formParam("codigoBarra", isolationCashierItem.getCodigoBarra())
                .formParam("cantidad", "1")
                .when().post(POS + "/scan-form")
                .then().statusCode(200)
                .body(containsString(isolationCashierItem.getNombre()));

        testAuthed(jar)
                .when().get(POS + "/cart-panel")
                .then()
                .statusCode(200)
                .body(containsString(isolationCashierItem.getNombre()))
                .body(not(containsString(isolationAdminItem.getNombre())));
    }

    @Test
    @Order(24)
    void isolationPhase3AdminPanelStillHoldsOnlyOwnLine() {
        Map<String, String> session = adminSession();

        authed(session)
                .when().get(POS + "/cart-panel")
                .then()
                .statusCode(200)
                .body(containsString(isolationAdminItem.getNombre()))
                .body(not(containsString(isolationCashierItem.getNombre())));
    }

    // ── Small helpers ───────────────────────────────────────────────────

    private void selectClient(Map<String, String> session, int clientCode) {
        authed(session)
                .contentType(ContentType.URLENC)
                .formParam("clientCode", String.valueOf(clientCode))
                .when().post(POS + "/client-select-form")
                .then().statusCode(200);
    }

    /** First href value in the fragment (the PDF link is the only anchor). */
    private static String extractHref(String html) {
        int marker = html.indexOf("href=\"/Mercurius/api/app/pos/facturas/");
        if (marker < 0) {
            return "";
        }
        int start = marker + "href=\"".length();
        int end = html.indexOf('"', start);
        return end < 0 ? "" : html.substring(start, end);
    }
}
