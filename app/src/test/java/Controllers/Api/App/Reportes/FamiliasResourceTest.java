package Controllers.Api.App.Reportes;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;

/**
 * T19 smoke suite for {@link FamiliasResource} (Reporte de Ventas por Familia):
 * date-range round-trip, page + HX fragment renders, role gate.
 */
@QuarkusTest
class FamiliasResourceTest {

    private static final String PAGE = "/app/reportes/articulos/familias";

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "inventario"})
    void paginaAutenticadaContieneTabla() {
        given()
                .queryParam("desde", "2026-01-01")
                .queryParam("hasta", "2026-01-31")
                .when().get(PAGE)
                .then()
                .statusCode(200)
                .body(containsString("data-kit-table"))
                .body(containsString("reporte-familias"))
                .body(containsString("Familia"));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "inventario"})
    void rangoVacioMuestraMensaje() {
        given()
                .when().get(PAGE)
                .then()
                .statusCode(200)
                .body(containsString("Seleccione el rango de fechas"));
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
}
