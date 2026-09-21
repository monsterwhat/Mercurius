package Controllers.Api.App;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * W4B-CORREOS acceptance suite for the view half of
 * {@link EmailTemplateResource} and {@link ReporteProgramadoResource}:
 * authenticated page GETs carry the kit markers, the HX-Request fragment mode
 * renders ONLY the data-table include (docs/ui-kit.md §2.9), the
 * PUT /{id}/toggle round-trip flips the enabled flag over REST and the page
 * reflects it, and the frecuencia ("cron") whitelist failure re-renders the
 * dialog form fragment with the field error (ui-kit.md Pattern A).
 *
 * <p>Real form-cookie login over RestAssured (POST /Mercurius/j_security_check,
 * seed admin/admin123), mirroring {@link CategoriaResourceTest}. The
 * X-CSRF-TOKEN header dance is reproduced defensively even though no
 * quarkus-rest-csrf properties are currently set.</p>
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CorreosAdminPagesTest extends support.ContextPathIsolation {

    private static final String BASE = "/Mercurius";
    private static final String TEMPLATES_PAGE = BASE + "/api/app/email-templates/table";
    private static final String REPORTES_PAGE = BASE + "/api/app/reportes-programados/table";
    private static final String CSRF_COOKIE = "csrf-token";
    private static final String CSRF_HEADER = "X-CSRF-TOKEN";

    // ── Auth helpers (CategoriaResourceTest conventions) ────────────────

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
    void unauthenticatedPageGetsAreRedirectedToLogin() {
        given().redirects().follow(false)
                .when().get(TEMPLATES_PAGE)
                .then()
                .statusCode(302)
                .header("Location", containsString("/Mercurius/login"));
        given().redirects().follow(false)
                .when().get(REPORTES_PAGE)
                .then()
                .statusCode(302)
                .header("Location", containsString("/Mercurius/login"));
    }

    @Test
    @Order(2)
    void templatesPageRendersKitMarkers() {
        Map<String, String> session = adminSession();
        authed(session)
                .when().get(TEMPLATES_PAGE)
                .then()
                .statusCode(200)
                .contentType(ContentType.HTML)
                .body(containsString("<html"))
                .body(containsString("Plantillas de Correo Electronico"))
                .body(containsString("data-kit-table"))
                .body(containsString("data-kit-modal"))
                .body(containsString("data-kit-confirm-modal"))
                .body(containsString("toast-container"));
    }

    @Test
    @Order(3)
    void reportesPageRendersKitMarkers() {
        Map<String, String> session = adminSession();
        authed(session)
                .when().get(REPORTES_PAGE)
                .then()
                .statusCode(200)
                .contentType(ContentType.HTML)
                .body(containsString("<html"))
                .body(containsString("Reportes Programados X Correo Electronico"))
                .body(containsString("data-kit-table"))
                .body(containsString("data-kit-modal"))
                .body(containsString("data-kit-confirm-modal"))
                .body(containsString("toast-container"));
    }

    @Test
    @Order(4)
    void templatesFragmentModeIsTableOnly() {
        Map<String, String> session = adminSession();
        authed(session)
                .header("HX-Request", "true")
                .queryParam("q", "inexistente-w4b")
                .when().get(TEMPLATES_PAGE)
                .then()
                .statusCode(200)
                .contentType(ContentType.HTML)
                .body(containsString("data-kit-table"))
                .body(containsString("id=\"tabla-plantillas\""))
                .body(not(containsString("<html")))
                .body(not(containsString("toast-container")));
    }

    @Test
    @Order(5)
    void reportesFragmentModeIsTableOnly() {
        Map<String, String> session = adminSession();
        authed(session)
                .header("HX-Request", "true")
                .queryParam("q", "inexistente-w4b")
                .when().get(REPORTES_PAGE)
                .then()
                .statusCode(200)
                .contentType(ContentType.HTML)
                .body(containsString("data-kit-table"))
                .body(containsString("id=\"tabla-reportes\""))
                .body(not(containsString("<html")))
                .body(not(containsString("toast-container")));
    }

    @Test
    @Order(6)
    void toggleRoundTripFlipsEnabledOverRestAndPageReflectsIt() {
        Map<String, String> session = adminSession();
        String perfil = uniqueName("W4B Toggle");

        Integer id = authed(session)
                .contentType(ContentType.JSON)
                .body(Map.of("perfil", perfil))
                .when().post(BASE + "/api/app/reportes-programados")
                .then()
                .statusCode(201)
                .body("data.status", equalTo(true))
                .extract().jsonPath().getInt("data.id");

        try {
            authed(session)
                    .header("HX-Request", "true")
                    .queryParam("q", perfil)
                    .when().get(REPORTES_PAGE)
                    .then()
                    .statusCode(200)
                    .body(containsString(perfil))
                    .body(containsString("Habilitado"))
                    .body(not(containsString("Deshabilitado")));

            authed(session)
                    .when().put(BASE + "/api/app/reportes-programados/" + id + "/toggle")
                    .then()
                    .statusCode(200)
                    .body("data.status", equalTo(false));

            authed(session)
                    .header("HX-Request", "true")
                    .queryParam("q", perfil)
                    .when().get(REPORTES_PAGE)
                    .then()
                    .statusCode(200)
                    .body(containsString(perfil))
                    .body(containsString("Deshabilitado"))
                    .body(not(containsString("Habilitado")));
        } finally {
            authed(session)
                    .when().delete(BASE + "/api/app/reportes-programados/" + id)
                    .then()
                    .statusCode(200);
        }
    }

    @Test
    @Order(7)
    void reporteFormRejectsInvalidCronValueWithFragmentRedisplay() {
        Map<String, String> session = adminSession();
        String perfil = uniqueName("W4B Cron");

        Response redisplay = authed(session)
                .header("HX-Request", "true")
                .contentType(ContentType.URLENC)
                .formParam("perfil", perfil)
                .formParam("frecuencia", "Semanal")
                .formParam("frecuencia", "0 0 * * * *")
                .formParam("correos", "w4b@mercurius.local")
                .when().post(BASE + "/api/app/reportes-programados");
        assertTrue(redisplay.getStatusCode() == 422 || redisplay.getStatusCode() == 400,
                "invalid frecuencia must answer 422 or 400, got " + redisplay.getStatusCode());
        String html = redisplay.getBody().asString();
        assertTrue(html.contains("id=\"forma-reporte\""),
                "the re-rendered form fragment must be swapped back");
        assertTrue(html.contains("Frecuencia inválida"),
                "the field error must be displayed inside the fragment");
        assertTrue(html.contains("hx-swap-oob"),
                "an out-of-band toast must accompany the redisplay");

        authed(session)
                .queryParam("q", perfil)
                .when().get(BASE + "/api/app/reportes-programados")
                .then()
                .statusCode(200)
                .body("total", equalTo(0));
    }

    @Test
    @Order(8)
    void plantillaFormDuplicateNameSurfacesLegacyWarningAsFragment() {
        Map<String, String> session = adminSession();
        String nombre = uniqueName("W4B Dup");

        Integer id = authed(session)
                .contentType(ContentType.JSON)
                .body(Map.of("nombre", nombre, "tipo", "REPORTES",
                        "cuerpoHtml", "<p>x</p>"))
                .when().post(BASE + "/api/app/email-templates")
                .then()
                .statusCode(201)
                .extract().jsonPath().getInt("data.id");

        try {
            authed(session)
                    .header("HX-Request", "true")
                    .contentType(ContentType.URLENC)
                    .formParam("nombre", nombre)
                    .formParam("tipo", "REPORTES")
                    .formParam("cuerpoHtml", "<p>y</p>")
                    .when().post(BASE + "/api/app/email-templates")
                    .then()
                    .statusCode(org.hamcrest.Matchers.anyOf(equalTo(422), equalTo(400)))
                    .body(containsString("id=\"forma-plantilla\""));
        } finally {
            authed(session)
                    .when().delete(BASE + "/api/app/email-templates/" + id)
                    .then()
                    .statusCode(200);
        }
    }

    @Test
    @Order(9)
    void plantillaHxDeleteReturnsRefreshedTableFragmentAndRemovesRow() {
        Map<String, String> session = adminSession();
        String nombre = uniqueName("W4B Del");

        Integer id = authed(session)
                .contentType(ContentType.JSON)
                .body(Map.of("nombre", nombre, "tipo", "NOTIFICACIONES",
                        "cuerpoHtml", "<p>del</p>"))
                .when().post(BASE + "/api/app/email-templates")
                .then()
                .statusCode(201)
                .extract().jsonPath().getInt("data.id");

        authed(session)
                .header("HX-Request", "true")
                .when().delete(BASE + "/api/app/email-templates/" + id)
                .then()
                .statusCode(200)
                .contentType(ContentType.HTML)
                .body(containsString("data-kit-table"))
                .body(containsString("Plantilla eliminada exitosamente"))
                .body(not(containsString("<html")));

        authed(session)
                .when().get(BASE + "/api/app/email-templates/" + id)
                .then()
                .statusCode(404);
    }

    @Test
    @Order(10)
    void jsonListContractsStayUntouched() {
        Map<String, String> session = adminSession();

        authed(session)
                .queryParam("page", 0)
                .queryParam("size", 5)
                .when().get(BASE + "/api/app/email-templates")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("page", equalTo(0))
                .body("size", equalTo(5))
                .body("total", greaterThanOrEqualTo(0))
                .body("data.size()", greaterThanOrEqualTo(0));

        authed(session)
                .queryParam("page", 0)
                .queryParam("size", 5)
                .when().get(BASE + "/api/app/reportes-programados")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("page", equalTo(0))
                .body("size", equalTo(5))
                .body("total", greaterThanOrEqualTo(0))
                .body("data.size()", greaterThanOrEqualTo(0));
    }

    @Test
    @Order(11)
    @TestSecurity(user = "tributacion-admin", roles = "tributacion")
    void tributacionRoleMayOpenBothAdminPages() {
        Map<String, String> cookies = anonymousCookies();
        authed(cookies).when().get(TEMPLATES_PAGE).then().statusCode(200);
        authed(cookies).when().get(REPORTES_PAGE).then().statusCode(200);
    }

    @Test
    @Order(12)
    @TestSecurity(user = "invitado", roles = "usuario")
    void usuarioRoleIsForbiddenFromBothAdminPages() {
        Map<String, String> cookies = anonymousCookies();
        authed(cookies).when().get(TEMPLATES_PAGE).then().statusCode(403);
        authed(cookies).when().get(REPORTES_PAGE).then().statusCode(403);
    }
}
