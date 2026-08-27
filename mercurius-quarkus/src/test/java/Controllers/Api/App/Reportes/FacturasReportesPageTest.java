package Controllers.Api.App.Reportes;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import Services.ComprobantesEmitidosService;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T20 — page contract for {@code GET /app/reportes/facturas}: authenticated
 * render, HTMX fragment-only swap, role gate and row-count parity against
 * {@link ComprobantesEmitidosService#listAll()}.
 */
@QuarkusTest
@Tag("reportes-pages")
class FacturasReportesPageTest extends support.ContextPathIsolation {

    private static final String PAGE_URL = "/Mercurius/app/reportes/facturas";

    @Inject
    ComprobantesEmitidosService comprobantesService;

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "facturacion"})
    void pageRendersWithTableForFacturacionAdmin() {
        given()
                .when().get(PAGE_URL)
                .then()
                .statusCode(200)
                .body(containsString("Facturas Emitidas"))
                .body(containsString("data-kit-table"))
                .body(containsString("Numero Consecutivo"));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "facturacion"})
    void hxRequestReturnsFragmentWithoutLayout() {
        String fragment = given()
                .header("HX-Request", "true")
                .when().get(PAGE_URL)
                .then()
                .statusCode(200)
                .body(containsString("data-kit-table"))
                .body(not(containsString("<footer")))
                .body(not(containsString("navbar")))
                .extract().asString();
        assertTrue(fragment.trim().startsWith("<div"),
                "fragment response must start with the table container div");
    }

    @Test
    @TestSecurity(user = "cajero", roles = {"inventario"})
    void nonFacturacionRoleIsForbidden() {
        given()
                .when().get(PAGE_URL)
                .then()
                .statusCode(403);
    }

    @Test
    void anonymousIsRedirectedToLogin() {
        given()
                .redirects().follow(false)
                .when().get(PAGE_URL)
                .then()
                .statusCode(302);
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "facturacion"})
    void rowCountMatchesDirectServiceCall() {
        int expected = comprobantesService.listAll().size();

        String html = given()
                .queryParam("size", 500)
                .when().get(PAGE_URL)
                .then()
                .statusCode(200)
                .extract().asString();

        assertEquals(expected, countRows(html),
                "page table rows must equal ComprobantesEmitidosService.listAll().size()");
    }

    static int countRows(String html) {
        return html.split("data-row-key=", -1).length - 1;
    }
}
