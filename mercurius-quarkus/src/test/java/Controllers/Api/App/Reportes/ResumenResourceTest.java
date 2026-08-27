package Controllers.Api.App.Reportes;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;

/**
 * T19 smoke suite for {@link ResumenResource} (Resumen de Inventario,
 * read-only): page + HX fragment renders, vista switch (todos/cero/negativos),
 * filter round-trip, role gate.
 */
@QuarkusTest
class ResumenResourceTest {

    private static final String PAGE = "/app/reportes/inventario/resumen";

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "inventario"})
    void paginaAutenticadaContieneTabla() {
        given()
                .when().get(PAGE)
                .then()
                .statusCode(200)
                .body(containsString("data-kit-table"))
                .body(containsString("reporte-resumen"))
                .body(containsString("Stock Actual"));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "inventario"})
    void vistaCeroRondaTrip() {
        given()
                .queryParam("vista", "cero")
                .when().get(PAGE)
                .then()
                .statusCode(200)
                .body(containsString("value=\"cero\""));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "inventario"})
    void filtroTextoRondaTrip() {
        given()
                .queryParam("q", "zzz-sin-coincidencias")
                .queryParam("vista", "negativos")
                .when().get(PAGE)
                .then()
                .statusCode(200)
                .body(containsString("zzz-sin-coincidencias"));
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
    @TestSecurity(user = "cajero", roles = {"usuario"})
    void rolSinPermisoRecibe403() {
        given()
                .redirects().follow(false)
                .when().get(PAGE)
                .then()
                .statusCode(403);
    }
}
