package Controllers.Api.App.Reportes;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;

/**
 * T19 smoke suite for {@link MermaResource} (Control de Mermas y Pérdidas,
 * read-only): page + HX fragment renders, section switch, date-range
 * round-trip, role gate.
 */
@QuarkusTest
class MermaResourceTest {

    private static final String PAGE = "/app/reportes/inventario/merma";

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
                .body(containsString("reporte-merma"))
                .body(containsString("Total Mermas"));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "inventario"})
    void seccionDetalleCambiaColumnas() {
        given()
                .queryParam("seccion", "detalle")
                .when().get(PAGE)
                .then()
                .statusCode(200)
                .body(containsString("Artículo"));
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
    @TestSecurity(user = "vendedor", roles = {"facturacion"})
    void rolSinPermisoRecibe403() {
        given()
                .redirects().follow(false)
                .when().get(PAGE)
                .then()
                .statusCode(403);
    }
}
