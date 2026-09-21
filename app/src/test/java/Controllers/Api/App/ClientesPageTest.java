package Controllers.Api.App;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import Models.Clients;
import Services.ClientService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * W4B view-half acceptance suite for {@link ClientsResource}: authenticated
 * page GET carries the kit markers, HX-Request returns ONLY the table
 * fragment, the pre-existing JSON CRUD contract stays green (including the
 * 409 duplicate-name guard), the HTMX form twin creates through the same
 * guards, and hx-delete answers with the refreshed table fragment.
 *
 * <p>Auth mirrors CategoriaResourceTest: real form-cookie login over
 * RestAssured (seed admin/admin123) + {@code X-CSRF-TOKEN} dance.</p>
 */
@QuarkusTest
@Tag("w4b-views")
class ClientesPageTest extends support.ContextPathIsolation {

    private static final String BASE = "/Mercurius";
    private static final String TABLE_URL = BASE + "/api/app/clientes/table";
    private static final String CSRF_COOKIE = "csrf-token";
    private static final String CSRF_HEADER = "X-CSRF-TOKEN";

    @Inject
    ClientService clientService;

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

    private Clients seedCliente(String nombre) {
        Clients cliente = new Clients();
        cliente.setName(nombre);
        cliente.setEmail(nombre.replace(" ", "") + "@mercurius.local");
        cliente.setAddress("Barrio W4B");
        cliente.setPhoneNumber("8888-0000");
        cliente.setIdType("Cedula Fisica");
        cliente.setIdNumber("W4B-" + System.nanoTime());
        cliente.setDiscount(0.0);
        cliente.setTaxpayer(false);
        cliente.setZoneCode(1);
        cliente.setStatus(Boolean.TRUE);
        clientService.create(cliente);
        return cliente;
    }

    @Test
    void pageRendersKitMarkersAndStatCards() {
        Map<String, String> session = adminSession();
        authed(session)
                .when().get(TABLE_URL)
                .then()
                .statusCode(200)
                .contentType(ContentType.HTML)
                .body(containsString("<html"))
                .body(containsString("Gestión de Clientes"))
                .body(containsString("data-kit-table"))
                .body(containsString("id=\"tabla-clientes\""))
                .body(containsString("toast-container"));
    }

    @Test
    void hxRequestReturnsFragmentWithoutLayout() {
        Map<String, String> session = adminSession();
        authed(session)
                .header("HX-Request", "true")
                .when().get(TABLE_URL)
                .then()
                .statusCode(200)
                .contentType(ContentType.HTML)
                .body(containsString("data-kit-table"))
                .body(containsString("id=\"tabla-clientes\""))
                .body(not(containsString("<html")))
                .body(not(containsString("toast-container")));
    }

    @Test
    void searchFilterRoundTripNarrowsToFixture() {
        Map<String, String> session = adminSession();
        String marker = "W4B Cliente " + System.nanoTime();
        Clients cliente = seedCliente(marker);
        try {
            authed(session)
                    .header("HX-Request", "true")
                    .queryParam("q", marker)
                    .when().get(TABLE_URL)
                    .then()
                    .statusCode(200)
                    .body(containsString(marker));
        } finally {
            clientService.delete(cliente);
        }
    }

    @Test
    void jsonCrudRoundTripStaysGreen() {
        Map<String, String> session = adminSession();
        String marker = "W4B JSON " + System.nanoTime();

        authed(session)
                .contentType(ContentType.JSON)
                .body(Map.of("name", marker, "email", marker.replace(" ", "") + "@mercurius.local",
                        "taxpayer", false, "zoneCode", 0))
                .when().post(BASE + "/api/app/clientes")
                .then()
                .statusCode(201)
                .body("data.name", equalTo(marker));

        Integer code = authed(session)
                .queryParam("q", marker)
                .when().get(BASE + "/api/app/clientes")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("total", greaterThanOrEqualTo(1))
                .extract().jsonPath().getInt("data[0].code");

        authed(session)
                .when().delete(BASE + "/api/app/clientes/" + code)
                .then()
                .statusCode(200)
                .body("data.status", equalTo(false));

        Clients archived = clientService.find(code);
        assertNotNull(archived);
        assertFalse(archived.getStatus(), "DELETE must archive (soft-disable) the client");

        clientService.delete(archived);
    }

    @Test
    void duplicateNameStillSurfaces409OnJsonContract() {
        Map<String, String> session = adminSession();
        authed(session)
                .contentType(ContentType.JSON)
                .body(Map.of("name", "Cliente Contado"))
                .when().post(BASE + "/api/app/clientes")
                .then()
                .statusCode(409)
                .body("error.code", equalTo("NAME_TAKEN"));
    }

    @Test
    void formTwinCreateRedirectsAndPersists() {
        Map<String, String> session = adminSession();
        String marker = "W4B Form " + System.nanoTime();

        authed(session)
                .header("HX-Request", "true")
                .contentType(ContentType.URLENC)
                .formParam("name", marker)
                .formParam("email", marker.replace(" ", "") + "@mercurius.local")
                .formParam("address", "Barrio W4B")
                .formParam("phoneNumber", "8888-0000")
                .formParam("zoneCode", "1")
                .formParam("idType", "Cédula Física")
                .formParam("idNumber", "W4B-" + System.nanoTime())
                .formParam("birthDate", "1992-07-01")
                .formParam("actividad", "561110")
                .when().post(BASE + "/api/app/clientes")
                .then()
                .statusCode(200)
                .header("HX-Redirect", equalTo("/api/app/clientes/table"));

        Integer code = authed(session)
                .queryParam("q", marker)
                .when().get(BASE + "/api/app/clientes")
                .then()
                .statusCode(200)
                .extract().jsonPath().getInt("data[0].code");

        authed(session)
                .when().get(BASE + "/api/app/clientes/" + code)
                .then()
                .statusCode(200)
                .body("data.name", equalTo(marker))
                .body("data.actividades.size()", greaterThanOrEqualTo(1));

        clientService.delete(clientService.find(code));
    }

    @Test
    void hxDeleteReturnsRefreshedTableFragment() {
        Map<String, String> session = adminSession();
        String marker = "W4B HXDel " + System.nanoTime();
        Clients cliente = seedCliente(marker);
        try {
            authed(session)
                    .header("HX-Request", "true")
                    .when().delete(BASE + "/api/app/clientes/" + cliente.getCode())
                    .then()
                    .statusCode(200)
                    .contentType(ContentType.HTML)
                    .body(containsString("data-kit-table"))
                    .body(containsString("hx-swap-oob"))
                    .body(containsString("fue archivado"));
        } finally {
            Clients archived = clientService.find(cliente.getCode());
            clientService.delete(archived != null ? archived : cliente);
        }
    }
}
