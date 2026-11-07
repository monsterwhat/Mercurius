package Controllers.Api.App.Reportes;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Page contract for {@code GET /app/reportes/proveedores}: role gate and the
 * D5 regression that the ranking is a report, not management.
 */
@QuarkusTest
@Tag("reportes-pages")
class ProveedoresReportesPageTest extends support.ContextPathIsolation {

    private static final String PAGE_URL = "/app/reportes/proveedores";

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void adminCanViewRankingPage() {
        given()
                .when().get(PAGE_URL)
                .then()
                .statusCode(200)
                .body(containsString("Ranking de Rendimiento de Proveedores"))
                .body(containsString("Score promedio"))
                .body(containsString("Compras totales"));
    }

    @Test
    @TestSecurity(user = "almacenero", roles = {"inventario"})
    void inventarioRoleCanViewRankingPage() {
        given()
                .when().get(PAGE_URL)
                .then()
                .statusCode(200)
                .body(containsString("Ranking de Rendimiento de Proveedores"));
    }

    @Test
    @TestSecurity(user = "facturador", roles = {"facturacion"})
    void facturacionRoleIsForbidden() {
        given()
                .when().get(PAGE_URL)
                .then()
                .statusCode(403);
    }

    @Test
    @TestSecurity(user = "gestor", roles = {"usuario"})
    void usuarioRoleIsForbidden() {
        given()
                .when().get(PAGE_URL)
                .then()
                .statusCode(403);
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void pageDoesNotContainManagementForms() {
        given()
                .when().get(PAGE_URL)
                .then()
                .statusCode(200)
                .body(not(containsString("Nuevo Departamento")))
                .body(not(containsString("formularios/departamento")));
    }
}
