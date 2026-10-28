package Controllers.Api.App.Reportes;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;

/**
 * Page contract for {@code GET /app/reportes/articulos/ventas-fecha-historica}.
 *
 * <p>Pins the report after its filter options moved from hydrating every
 * article entity to a name/family/department projection: the page must still
 * render the filter bar and the result surfaces for an allowed role.</p>
 */
@QuarkusTest
class VentasFechaHistoricaPageTest {

    private static final String PAGE = "/app/reportes/articulos/ventas-fecha-historica";

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "inventario"})
    void paginaRenderizaFiltrosYResultados() {
        given()
                .when().get(PAGE)
                .then()
                .statusCode(200)
                .body(containsString("filtros-ventas-historicas"))
                .body(containsString("Ventas por Fecha"));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "inventario"})
    void filtrosDeDimensionAceptanSeleccion() {
        given()
                .queryParam("mes", "12")
                .queryParam("dia", "25")
                .queryParam("anos", "3")
                .queryParam("dimension", "familias")
                .when().get(PAGE)
                .then()
                .statusCode(200)
                .body(containsString("filtros-ventas-historicas"));
    }
}
