package Controllers.Api.App;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * T18 acceptance suite for {@link CategoriaResource}: real form-cookie login
 * over RestAssured (POST /Mercurius/j_security_check, seed admin/admin123),
 * CRUD happy paths, the legacy duplicate/blank-nombre messages, the
 * soft-delete toggle round-trip (T3 {@code Tipo_SoftDelete} mapping), the
 * role matrix (@TestSecurity where simpler) and the /table fragment
 * dual-mode contract (docs/ui-kit.md §2.9).
 *
 * <p><b>CSRF note:</b> quarkus-rest-csrf is active with defaults, so every
 * mutating call must carry the {@code X-CSRF-TOKEN} header matching the
 * {@code csrftoken} cookie issued by any prior GET (the browser gets both
 * automatically via the layout hx-headers pattern). The helpers below
 * reproduce that dance.</p>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CategoriaResourceTest extends support.ContextPathIsolation {

    private static final String BASE = "/Mercurius";
    private static final String CSRF_COOKIE = "csrf-token";
    private static final String CSRF_HEADER = "X-CSRF-TOKEN";

    // ── Auth helpers ────────────────────────────────────────────────────

    /** Full browser-equivalent session: GET /login → POST j_security_check. */
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

    private static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    // ── Scenarios ───────────────────────────────────────────────────────

    @Test
    @Order(1)
    void unauthenticatedListIsRedirectedToLogin() {
        given().redirects().follow(false)
                .when().get(BASE + "/api/app/categorias/familias")
                .then()
                .statusCode(302)
                .header("Location", containsString("/Mercurius/login"));
    }

    @Test
    @Order(2)
    void adminListsFamiliasWithPagedEnvelopeAndSeedRow() {
        Map<String, String> session = adminSession();
        authed(session)
                .queryParam("page", 1)
                .queryParam("size", 5)
                .queryParam("sort", "nombre")
                .queryParam("dir", "asc")
                .when().get(BASE + "/api/app/categorias/familias")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("page", equalTo(1))
                .body("size", equalTo(5))
                .body("total", greaterThanOrEqualTo(1))
                .body("data.size()", greaterThanOrEqualTo(1))
                .body("data.findAll{ it.nombre == 'Familia General' }.size()", greaterThanOrEqualTo(1));
    }

    @Test
    @Order(3)
    void createFamiliaHappyPathPersistsActiveRow() {
        Map<String, String> session = adminSession();
        String nombre = uniqueName("Familia T18");

        authed(session)
                .contentType(ContentType.JSON)
                .body(Map.of("nombre", nombre))
                .when().post(BASE + "/api/app/categorias/familias")
                .then()
                .statusCode(201)
                .body("data.nombre", equalTo(nombre))
                .body("data.status", equalTo(true));

        authed(session)
                .queryParam("q", nombre)
                .when().get(BASE + "/api/app/categorias/familias")
                .then()
                .statusCode(200)
                .body("total", greaterThanOrEqualTo(1))
                .body("data[0].nombre", equalTo(nombre))
                .body("data[0].status", equalTo(true));
    }

    @Test
    @Order(4)
    void createFamiliaDuplicateNameSurfacesLegacyWarningAs409() {
        Map<String, String> session = adminSession();
        authed(session)
                .contentType(ContentType.JSON)
                .body(Map.of("nombre", "Familia General"))
                .when().post(BASE + "/api/app/categorias/familias")
                .then()
                .statusCode(409)
                .body("error.code", equalTo("DUPLICATE_NAME"))
                .body("error.message", equalTo("Ya existe una familia con ese nombre!"));
    }

    @Test
    @Order(5)
    void createFamiliaBlankNombreRejectedWithLegacyMessage() {
        Map<String, String> session = adminSession();
        authed(session)
                .contentType(ContentType.JSON)
                .body(Map.of("nombre", "   "))
                .when().post(BASE + "/api/app/categorias/familias")
                .then()
                .statusCode(400)
                .body("error.code", equalTo("VALIDATION_ERROR"))
                .body("error.message", equalTo("El nombre no puede estar vacío"));
    }

    @Test
    @Order(6)
    void updateDepartamentoPersistsContactChanges() {
        Map<String, String> session = adminSession();
        String nombre = uniqueName("Dep T18");

        authed(session)
                .contentType(ContentType.JSON)
                .body(Map.of("nombre", nombre, "contactoNombre", "Original"))
                .when().post(BASE + "/api/app/categorias/departamentos")
                .then()
                .statusCode(201);

        Integer id = authed(session)
                .queryParam("q", nombre)
                .when().get(BASE + "/api/app/categorias/departamentos")
                .then()
                .statusCode(200)
                .extract().jsonPath().getInt("data[0].id");

        authed(session)
                .contentType(ContentType.JSON)
                .body(Map.of("nombre", nombre,
                        "contactoNombre", "Actualizado",
                        "contactoEmail", "t18@mercurius.local",
                        "plazoPagoDias", 30,
                        "tiempoEntregaDias", 5,
                        "notas", "nota de prueba"))
                .when().put(BASE + "/api/app/categorias/departamentos/" + id)
                .then()
                .statusCode(200)
                .body("data.contactoNombre", equalTo("Actualizado"))
                .body("data.contactoEmail", equalTo("t18@mercurius.local"))
                .body("data.plazoPagoDias", equalTo(30))
                .body("data.notas", equalTo("nota de prueba"));

        authed(session)
                .queryParam("q", nombre)
                .when().get(BASE + "/api/app/categorias/departamentos")
                .then()
                .statusCode(200)
                .body("data[0].contactoNombre", equalTo("Actualizado"));
    }

    @Test
    @Order(7)
    void softDeleteToggleRoundTripMapsTipoSoftDeleteEnum() {
        Map<String, String> session = adminSession();
        String nombre = uniqueName("Familia Toggle");

        authed(session)
                .contentType(ContentType.JSON)
                .body(Map.of("nombre", nombre))
                .when().post(BASE + "/api/app/categorias/familias")
                .then().statusCode(201);
        Integer id = authed(session)
                .queryParam("q", nombre)
                .when().get(BASE + "/api/app/categorias/familias")
                .then().statusCode(200)
                .extract().jsonPath().getInt("data[0].id");

        // First toggle: ACTIVO → INACTIVO (legacy "Se desactivo la familia!")
        authed(session)
                .when().delete(BASE + "/api/app/categorias/familias/" + id)
                .then()
                .statusCode(200)
                .body("data.resultado", equalTo("DEACTIVATED"))
                .body("data.mensaje", equalTo("Se desactivo la familia!"));
        authed(session)
                .queryParam("q", nombre)
                .when().get(BASE + "/api/app/categorias/familias")
                .then().statusCode(200)
                .body("data[0].status", equalTo(false));

        // Second toggle: back to active (legacy "Se activo la familia!")
        authed(session)
                .when().delete(BASE + "/api/app/categorias/familias/" + id)
                .then()
                .statusCode(200)
                .body("data.resultado", equalTo("ACTIVATED"))
                .body("data.mensaje", equalTo("Se activo la familia!"));
        authed(session)
                .queryParam("q", nombre)
                .when().get(BASE + "/api/app/categorias/familias")
                .then().statusCode(200)
                .body("data[0].status", equalTo(true));
    }

    @Test
    @Order(8)
    void unauthenticatedMutationIsChallengedNotProcessed() {
        given().redirects().follow(false)
                .contentType(ContentType.JSON)
                .body(Map.of("nombre", uniqueName("Prohibida")))
                .when().post(BASE + "/api/app/categorias/familias")
                .then()
                .statusCode(302)
                .header("Location", containsString("/Mercurius/login"));
    }

    @Test
    @Order(9)
    @TestSecurity(user = "invitado", roles = "usuario")
    void testSecurityUsuarioRoleIsForbiddenFromCreate() {
        Map<String, String> cookies = anonymousCookies();
        authed(cookies)
                .contentType(ContentType.JSON)
                .body(Map.of("nombre", uniqueName("Invitado")))
                .when().post(BASE + "/api/app/categorias/familias")
                .then()
                .statusCode(403);
    }

    @Test
    @Order(10)
    @TestSecurity(user = "bodeguero", roles = "inventario")
    void inventarioRoleMayCreateFamilias() {
        Map<String, String> cookies = anonymousCookies();
        String nombre = uniqueName("Familia Bodega");
        authed(cookies)
                .contentType(ContentType.JSON)
                .body(Map.of("nombre", nombre))
                .when().post(BASE + "/api/app/categorias/familias")
                .then()
                .statusCode(201)
                .body("data.nombre", equalTo(nombre));
    }

    @Test
    @Order(11)
    void tableEndpointServesFragmentOnHxRequestAndFullPageOtherwise() {
        Map<String, String> session = adminSession();

        // Fragment mode: ONLY the data-table include, never the layout shell.
        authed(session)
                .header("HX-Request", "true")
                .queryParam("tab", "familias")
                .when().get(BASE + "/api/app/categorias/table")
                .then()
                .statusCode(200)
                .contentType(ContentType.HTML)
                .body(containsString("data-kit-table"))
                .body(containsString("id=\"tabla-familias\""))
                .body(not(containsString("<html")));

        // Full-page mode: the whole categorías page through the layout.
        authed(session)
                .when().get(BASE + "/api/app/categorias/table")
                .then()
                .statusCode(200)
                .contentType(ContentType.HTML)
                .body(containsString("<html"))
                .body(containsString("Gestión de Categorías"))
                .body(containsString("toast-container"))
                .body(containsString("Ranking de Rendimiento de Proveedores"));
    }

    @Test
    @Order(12)
    void metricasEndpointExposesResumenAndRows() {
        Map<String, String> session = adminSession();
        authed(session)
                .when().get(BASE + "/api/app/categorias/metricas")
                .then()
                .statusCode(200)
                .body("data.resumen.totalProveedores", notNullValue())
                .body("data.resumen.scorePromedio", notNullValue())
                .body("data.resumen.comprasTotales", notNullValue())
                .body("data.metricas", notNullValue());
    }
}
