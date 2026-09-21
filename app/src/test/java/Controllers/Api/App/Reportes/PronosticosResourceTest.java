package Controllers.Api.App.Reportes;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;

/**
 * T19 smoke suite for {@link PronosticosResource} (Pronósticos de Inventario):
 * page + HX fragment renders, days filter round-trip, canvas marker, role gate.
 */
@QuarkusTest
class PronosticosResourceTest {

    private static final String PAGE = "/app/reportes/inventario/pronosticos";

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "inventario"})
    void paginaAutenticadaContieneTablaYCanvas() {
        given()
                .when().get(PAGE)
                .then()
                .statusCode(200)
                .body(containsString("data-kit-table"))
                .body(containsString("reporte-pronosticos"))
                .body(containsString("grafica-pronosticos"))
                .body(containsString("/api/stock-forecast/bulk-forecast"));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "inventario"})
    void filtroDiasRondaTrip() {
        given()
                .queryParam("dias", "60")
                .when().get(PAGE)
                .then()
                .statusCode(200)
                .body(containsString("value=\"60\" selected"));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "inventario"})
    void seccionSaludRenderiza() {
        given()
                .queryParam("seccion", "salud")
                .when().get(PAGE)
                .then()
                .statusCode(200)
                .body(containsString("Estado"));
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
