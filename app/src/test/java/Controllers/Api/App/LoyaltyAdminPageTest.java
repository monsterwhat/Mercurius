package Controllers.Api.App;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import Models.Clients;
import Models.PuntosTransaccion;
import Services.ClientService;
import Services.LoyaltyService;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T25 acceptance suite for the loyalty ADMIN VIEW-HALF: real endpoints over
 * {@link LoyaltyResource}'s additive HTML surface ({@code /table},
 * {@code /{code}/view}, {@code /settings/form}) with the frozen JSON
 * contracts asserted untouched.
 *
 * <p>Auth conventions follow {@link RoleMatrixTest} ({@code @TestSecurity}
 * bypasses form auth and isolates the {@code @RolesAllowed({admin,
 * facturacion})} layer) combined with {@link CorreosAdminPagesTest}'s
 * anonymous-cookie trick so mutating calls satisfy the quarkus-rest-csrf
 * filter (X-CSRF-TOKEN header matching the csrftoken cookie).</p>
 */
@QuarkusTest
@Tag("loyalty-pages")
class LoyaltyAdminPageTest extends support.ContextPathIsolation {

    private static final String BASE = "/Mercurius";
    private static final String PAGE_URL = BASE + "/api/app/loyalty/table";
    private static final String SETTINGS_FORM_URL = BASE + "/api/app/loyalty/settings/form";
    private static String drawerUrl(int clientCode) {
        return BASE + "/api/app/loyalty/" + clientCode + "/view";
    }
    private static final String CSRF_COOKIE = "csrf-token";
    private static final String CSRF_HEADER = "X-CSRF-TOKEN";

    @Inject
    LoyaltyService loyaltyService;

    @Inject
    ClientService clientService;

    // ── Page markers ─────────────────────────────────────────────────────

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void pageRendersKitMarkersForAdmin() {
        given()
                .when().get(PAGE_URL)
                .then()
                .statusCode(200)
                .contentType(ContentType.HTML)
                .body(containsString("<html"))
                .body(containsString("Programa de Lealtad"))
                .body(containsString("Top 10 Clientes con Puntos"))
                .body(containsString("Porcentaje de Cashback"))
                .body(containsString("Meses de Inactividad"))
                .body(containsString("data-kit-table"))
                .body(containsString("data-kit-modal"))
                .body(containsString("toast-container"));
    }

    @Test
    @TestSecurity(user = "cajero", roles = {"facturacion"})
    void pageRendersKitMarkersForFacturacion() {
        given()
                .when().get(PAGE_URL)
                .then()
                .statusCode(200)
                .contentType(ContentType.HTML)
                .body(containsString("<html"))
                .body(containsString("Programa de Lealtad"))
                .body(containsString("data-kit-table"));
    }

    @Test
    @TestSecurity(user = "invitado", roles = {"usuario"})
    void usuarioRoleIsForbiddenFromLoyaltyAdminSurface() {
        given().when().get(PAGE_URL).then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void fragmentModeIsTableOnly() {
        given()
                .header("HX-Request", "true")
                .when().get(PAGE_URL)
                .then()
                .statusCode(200)
                .contentType(ContentType.HTML)
                .body(containsString("data-kit-table"))
                .body(containsString("id=\"loyalty-top-tabla\""))
                .body(not(containsString("<html")))
                .body(not(containsString("toast-container")));
    }

    // ── Seeded-points reflection ─────────────────────────────────────────

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void seededPointsReflectInTopTableWithTierBadge() {
        Clients cliente = null;
        try {
            cliente = seedClientWithPoints("IT-T25 Top " + UUID.randomUUID(), 50);

            String html = given()
                    .header("HX-Request", "true")
                    .queryParam("size", 100)
                    .when().get(PAGE_URL)
                    .then()
                    .statusCode(200)
                    .extract().asString();

            assertTrue(html.contains(cliente.getName()),
                    "seeded client must appear in the top table");
            assertTrue(html.contains("data-row-key=\"" + cliente.getCode() + "\""),
                    "seeded client row must carry its code as data-row-key");
            assertTrue(html.contains("#cd7f32") && html.contains("Bronce"),
                    "50 points must render the Bronce tier badge");
            assertEquals(loyaltyService.getTopLoyaltyCustomers(10).size(),
                    html.split("data-row-key=", -1).length - 1,
                    "top table rows must equal getTopLoyaltyCustomers(10).size()");
        } finally {
            if (cliente != null) {
                clientService.delete(cliente);
            }
        }
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void drawerShowsSummaryCardAndPaginatesSeededHistory() {
        Clients cliente = null;
        List<PuntosTransaccion> sembradas = new ArrayList<>();
        try {
            cliente = seedClientWithPoints("IT-T25 Drawer " + UUID.randomUUID(), 50);
            sembradas.add(seedTransaccion(cliente, "IT-T25 mov viejo",
                    Date.from(Instant.now().minusSeconds(7200))));
            sembradas.add(seedTransaccion(cliente, "IT-T25 mov nuevo",
                    Date.from(Instant.now().minusSeconds(3600))));

            String primera = given()
                    .header("HX-Request", "true")
                    .queryParam("page", 0)
                    .queryParam("size", 1)
                    .when().get(drawerUrl(cliente.getCode()))
                    .then()
                    .statusCode(200)
                    .contentType(ContentType.HTML)
                    .body(containsString(cliente.getName()))
                    .body(containsString("Bronce"))
                    .body(containsString("#cd7f32"))
                    .body(containsString("Activo"))
                    .body(containsString("IT-T25 mov nuevo"))
                    .body(containsString("earn"))
                    .body(not(containsString("IT-T25 mov viejo")))
                    .body(containsString("1 de 2"))
                    .body(containsString("2 movimientos"))
                    .extract().asString();
            assertTrue(primera.contains("Puntos Acumulados"),
                    "drawer must open with the client summary card");

            given()
                    .header("HX-Request", "true")
                    .queryParam("page", 1)
                    .queryParam("size", 1)
                    .when().get(drawerUrl(cliente.getCode()))
                    .then()
                    .statusCode(200)
                    .body(containsString("IT-T25 mov viejo"))
                    .body(not(containsString("IT-T25 mov nuevo")))
                    .body(containsString("2 de 2"));

            assertEquals(2, loyaltyService.getCustomerPointsHistory(cliente).size(),
                    "service history must hold exactly the two seeded transactions");

            given()
                    .when().get(drawerUrl(999999999))
                    .then()
                    .statusCode(404);
        } finally {
            for (PuntosTransaccion transaccion : sembradas) {
                loyaltyService.delete(transaccion);
            }
            if (cliente != null) {
                clientService.delete(cliente);
            }
        }
    }

    // ── Settings guard rejection (form twin mirrors PUT /settings) ───────

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void settingsFormRejectsNegativeCashbackLikeJsonGuard() {
        authed(anonymousCookies())
                .header("HX-Request", "true")
                .contentType(ContentType.URLENC)
                .formParam("cashbackPercentage", "-1")
                .when().put(SETTINGS_FORM_URL)
                .then()
                .statusCode(400)
                .contentType(ContentType.HTML)
                .body(containsString("id=\"panel-ajustes\""))
                .body(containsString("El porcentaje de cashback no puede ser negativo"))
                .body(containsString("hx-swap-oob"));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void settingsFormRejectsZeroInactivityMonthsLikeJsonGuard() {
        authed(anonymousCookies())
                .header("HX-Request", "true")
                .contentType(ContentType.URLENC)
                .formParam("puntosInactivityMonths", "0")
                .when().put(SETTINGS_FORM_URL)
                .then()
                .statusCode(400)
                .contentType(ContentType.HTML)
                .body(containsString("Los meses de inactividad deben ser mayores a cero"));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void settingsFormRejectsEmptyPayloadLikeJsonGuard() {
        authed(anonymousCookies())
                .header("HX-Request", "true")
                .contentType(ContentType.URLENC)
                .formParam("cashbackPercentage", "")
                .when().put(SETTINGS_FORM_URL)
                .then()
                .statusCode(400)
                .contentType(ContentType.HTML)
                .body(containsString("Debe indicar al menos un campo"));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void jsonSettingsContractStaysUntouchedByTheFormTwin() {
        authed(anonymousCookies())
                .contentType(ContentType.JSON)
                .body(Map.of("cashbackPercentage", -1))
                .when().put(BASE + "/api/app/loyalty/settings")
                .then()
                .statusCode(400)
                .contentType(ContentType.JSON)
                .body("error.code", equalTo("VALIDATION_ERROR"))
                .body("error.message", containsString("negativo"));
    }

    // ── Helpers (CategoriaResourceTest/LoyaltyReportesPageTest conventions) ─

    /** Anonymous visitor cookies: enough to satisfy the CSRF filter. */
    private static Map<String, String> anonymousCookies() {
        Response loginPage = given().redirects().follow(false)
                .when().get(BASE + "/login");
        loginPage.then().statusCode(200);
        return new HashMap<>(loginPage.getCookies());
    }

    private static RequestSpecification authed(Map<String, String> cookies) {
        RequestSpecification spec = given().redirects().follow(false).cookies(cookies);
        String token = cookies.get(CSRF_COOKIE);
        if (token != null) {
            spec.header(CSRF_HEADER, token);
        }
        return spec;
    }

    private Clients seedClientWithPoints(String name, double points) {
        Clients cliente = new Clients();
        cliente.setName(name);
        cliente.setAddress("Barrio IT-T25");
        cliente.setProvincia("1");
        cliente.setEmail(name.toLowerCase().replace(' ', '.') + "@mercurius.local");
        cliente.setBirthDate(Date.from(
                LocalDate.of(1990, 1, 31).atStartOfDay(ZoneId.systemDefault()).toInstant()));
        cliente.setIdType("Cedula Fisica");
        cliente.setIdNumber("IT-" + UUID.randomUUID().toString().substring(0, 8));
        cliente.setDiscount(0.0);
        cliente.setPhoneNumber("8888-2020");
        cliente.setTaxpayer(true);
        cliente.setZoneCode(1);
        cliente.setTipoIdentificacion("01");
        cliente.setStatus(Boolean.TRUE);
        cliente.setPuntosAcumulados(java.math.BigDecimal.valueOf(points));
        cliente.setStatusPuntos("active");
        clientService.create(cliente);
        return cliente;
    }

    private PuntosTransaccion seedTransaccion(Clients cliente, String descripcion, Date fecha) {
        PuntosTransaccion transaccion = new PuntosTransaccion();
        transaccion.setCliente(cliente);
        transaccion.setTipoTransaccion("earn");
        transaccion.setPuntos(java.math.BigDecimal.valueOf(10));
        transaccion.setSaldoPuntos(java.math.BigDecimal.valueOf(50));
        transaccion.setDescripcion(descripcion);
        transaccion.setFechaCreacion(fecha);
        loyaltyService.create(transaccion);
        return transaccion;
    }
}
