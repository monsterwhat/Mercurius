package Controllers.Api.App.Reportes;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;

/**
 * T19 smoke suite for {@link EtiquetasResource} (Generar Etiquetas,
 * read-only): page + HX fragment renders with the client-side selector column,
 * text filter round-trip, role gate. No mutation endpoints exist to test.
 */
@QuarkusTest
class EtiquetasResourceTest {

    private static final String PAGE = "/app/reportes/inventario/etiquetas";

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "inventario"})
    void paginaAutenticadaContieneTablaYSelector() {
        given()
                .when().get(PAGE)
                .then()
                .statusCode(200)
                .body(containsString("data-kit-table"))
                .body(containsString("reporte-etiquetas"))
                .body(containsString("js-etiqueta-check"))
                .body(containsString("Vista Previa de Etiquetas"));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "inventario"})
    void filtroTextoRondaTrip() {
        given()
                .queryParam("q", "zzz-sin-coincidencias")
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
}
