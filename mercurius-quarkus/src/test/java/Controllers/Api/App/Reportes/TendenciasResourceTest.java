package Controllers.Api.App.Reportes;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;

/**
 * T19 smoke suite for {@link TendenciasResource} (Tendencias de Ventas):
 * page + HX fragment renders, section switch, canvas marker, role gate.
 */
@QuarkusTest
class TendenciasResourceTest {

    private static final String PAGE = "/app/reportes/articulos/tendencias";

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "inventario"})
    void paginaAutenticadaContieneTablaYCanvas() {
        given()
                .when().get(PAGE)
                .then()
                .statusCode(200)
                .body(containsString("data-kit-table"))
                .body(containsString("reporte-tendencias"))
                .body(containsString("grafica-tendencias"))
                .body(containsString("/api/sales-trend/daily"));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "inventario"})
    void seccionIndicadoresMuestraConceptos() {
        given()
                .queryParam("seccion", "indicadores")
                .when().get(PAGE)
                .then()
                .statusCode(200)
                .body(containsString("Concepto"));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "inventario"})
    void fragmentoHxRequestContieneTabla() {
        given()
                .header("HX-Request", "true")
                .queryParam("seccion", "semanales")
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
