package Controllers.Api.App;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * W4B view-half acceptance suite for {@link CabysResource}: authenticated
 * page GET carries the kit markers, HX-Request returns ONLY the table
 * fragment (docs/ui-kit.md §2.9 dual-mode contract), the edit-dialog form
 * fragment renders, and the pre-existing REST CRUD contract stays green
 * while the new form-urlencoded twin answers HTMX dialogs.
 *
 * <p>Auth mirrors CategoriaResourceTest: real form-cookie login over
 * RestAssured (POST /Mercurius/j_security_check, seed admin/admin123); every
 * mutating call carries the {@code X-CSRF-TOKEN} header matching the
 * {@code csrftoken} cookie.</p>
 */
@QuarkusTest
@Tag("w4b-views")
class CabysPageTest extends support.ContextPathIsolation {

    private static final String BASE = "/Mercurius";
    private static final String TABLE_URL = BASE + "/api/app/cabys/table";
    private static final String CSRF_COOKIE = "csrf-token";
    private static final String CSRF_HEADER = "X-CSRF-TOKEN";

    @Inject
    Services.CabysService cabysService;

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

    private Models.Cabys seedCabys(String codigo) {
        Models.Cabys cabys = new Models.Cabys(codigo, "Descripción " + codigo,
                "Categoría prueba", "0", "https://example.com/" + codigo, "A");
        cabysService.create(cabys);
        return cabys;
    }

    @Test
    void pageRendersKitMarkersAndStatCard() {
        Map<String, String> session = adminSession();
        authed(session)
                .when().get(TABLE_URL)
                .then()
                .statusCode(200)
                .contentType(ContentType.HTML)
                .body(containsString("<html"))
                .body(containsString("Catálogo de Bienes y Servicios"))
                .body(containsString("data-kit-table"))
                .body(containsString("id=\"tabla-cabys\""))
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
                .body(containsString("id=\"tabla-cabys\""))
                .body(not(containsString("<html")))
                .body(not(containsString("toast-container")));
    }

    @Test
    void editFormFragmentRendersModalBody() {
        Map<String, String> session = adminSession();
        String codigo = "W4B-" + System.nanoTime();
        Models.Cabys cabys = seedCabys(codigo);
        try {
            authed(session)
                    .when().get(BASE + "/api/app/cabys/formularios/" + codigo)
                    .then()
                    .statusCode(200)
                    .contentType(ContentType.HTML)
                    .body(containsString("id=\"forma-cabys\""))
                    .body(containsString("name=\"descripcion\""))
                    .body(containsString(codigo));
        } finally {
            cabysService.delete(cabys);
        }
    }

    @Test
    void jsonCrudRoundTripStaysGreen() {
        Map<String, String> session = adminSession();
        String codigo = "W4B-" + System.nanoTime();
        Models.Cabys cabys = seedCabys(codigo);
        try {
            authed(session)
                    .queryParam("q", codigo)
                    .when().get(BASE + "/api/app/cabys")
                    .then()
                    .statusCode(200)
                    .contentType(ContentType.JSON)
                    .body("total", greaterThanOrEqualTo(1))
                    .body("data[0].codigo", equalTo(codigo));

            authed(session)
                    .contentType(ContentType.JSON)
                    .body(Map.of("descripcion", "Actualizada W4B", "estado", "I"))
                    .when().put(BASE + "/api/app/cabys/" + codigo)
                    .then()
                    .statusCode(200)
                    .body("data.descripcion", equalTo("Actualizada W4B"))
                    .body("data.estado", equalTo("I"));
        } finally {
            cabysService.delete(cabys);
        }
    }

    @Test
    void formTwinUpdateRedirectsToTable() {
        Map<String, String> session = adminSession();
        String codigo = "W4B-" + System.nanoTime();
        Models.Cabys cabys = seedCabys(codigo);
        try {
            authed(session)
                    .header("HX-Request", "true")
                    .contentType(ContentType.URLENC)
                    .formParam("descripcion", "Vía formulario HTMX")
                    .formParam("estado", "A")
                    .when().put(BASE + "/api/app/cabys/" + codigo)
                    .then()
                    .statusCode(200)
                    .header("HX-Redirect", equalTo("/api/app/cabys/table"));

            Models.Cabys updated = cabysService.find(codigo);
            assertNotNull(updated);
            assertEquals("Vía formulario HTMX", updated.getDescripcion());
        } finally {
            cabysService.delete(cabys);
        }
    }
}
