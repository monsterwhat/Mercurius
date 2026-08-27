package Controllers.Api.App.Reportes;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;

/**
 * T19 smoke suite for {@link FechasResource} (Reporte de Movimientos de
 * Inventario): page + HX fragment renders and the admin/inventario gate.
 */
@QuarkusTest
class FechasResourceTest {

    private static final String PAGE = "/app/reportes/articulos/fechas";

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "inventario"})
    void paginaAutenticadaContieneTabla() {
        given()
                .queryParam("usuario", "1")
                .queryParam("desde", "2026-01-01")
                .queryParam("hasta", "2026-01-31")
                .when().get(PAGE)
                .then()
                .statusCode(200)
                .body(containsString("data-kit-table"))
                .body(containsString("reporte-fechas"));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "inventario"})
    void fragmentoHxRequestContieneTabla() {
        given()
                .header("HX-Request", "true")
                .when().get(PAGE)
                .then()
                .statusCode(200)
                .body(containsString("<table"))
                .body(containsString("data-kit-table"));
    }

    @Test
    @TestSecurity(user = "bodeguero", roles = {"inventario"})
    void rolInventarioTieneAcceso() {
        given()
                .when().get(PAGE)
                .then()
                .statusCode(200);
    }
}
