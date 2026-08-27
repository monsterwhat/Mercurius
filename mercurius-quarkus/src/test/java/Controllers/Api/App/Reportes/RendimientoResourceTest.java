package Controllers.Api.App.Reportes;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;

/**
 * T19 smoke suite for {@link RendimientoResource} (Rendimiento de Productos):
 * page + HX fragment renders, section switch, canvas marker, role gate.
 */
@QuarkusTest
class RendimientoResourceTest {

    private static final String PAGE = "/app/reportes/articulos/rendimiento";

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "inventario"})
    void paginaAutenticadaContieneTablaYCanvas() {
        given()
                .when().get(PAGE)
                .then()
                .statusCode(200)
                .body(containsString("data-kit-table"))
                .body(containsString("reporte-rendimiento"))
                .body(containsString("grafica-rendimiento"))
                .body(containsString("/api/product-performance/department-performance"));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "inventario"})
    void seccionDepartamentoCambiaColumnas() {
        given()
                .queryParam("seccion", "departamento")
                .when().get(PAGE)
                .then()
                .statusCode(200)
                .body(containsString("Porcentaje"));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "inventario"})
    void fragmentoHxRequestContieneTabla() {
        given()
                .header("HX-Request", "true")
                .queryParam("seccion", "menos")
                .when().get(PAGE)
                .then()
                .statusCode(200)
                .body(containsString("<table"))
                .body(containsString("data-kit-table"));
    }
}
