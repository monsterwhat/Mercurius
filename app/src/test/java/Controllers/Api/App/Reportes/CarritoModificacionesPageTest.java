package Controllers.Api.App.Reportes;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Page contract for {@code GET /app/reportes/carrito-modificaciones}: the
 * internal cart-change log renders as a report with its filter bar, the three
 * counters and the shared data-table, and the HTMX fragment omits the layout.
 */
@QuarkusTest
@Tag("reportes-pages")
class CarritoModificacionesPageTest extends support.ContextPathIsolation {

    private static final String PAGE_URL = "/Mercurius/app/reportes/carrito-modificaciones";

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void pageRendersFiltersCountersAndTable() {
        given()
                .when().get(PAGE_URL)
                .then()
                .statusCode(200)
                .body(containsString("Cambios de carrito"))
                .body(containsString("Líneas eliminadas"))
                .body(containsString("Carritos cancelados"))
                .body(containsString("Precios ajustados"))
                .body(containsString("data-kit-table"))
                .body(containsString("name=\"desde\""))
                .body(containsString("name=\"accion\""));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void hxRequestReturnsFragmentWithoutLayout() {
        given()
                .header("HX-Request", "true")
                .when().get(PAGE_URL)
                .then()
                .statusCode(200)
                .body(containsString("data-kit-table"))
                .body(not(containsString("<footer")));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void filtersAreAccepted() {
        given()
                .queryParam("desde", "2026-01-01")
                .queryParam("hasta", "2026-12-31")
                .queryParam("accion", "PRECIO_AJUSTADO")
                .queryParam("usuario", "nadie")
                .queryParam("q", "sin-resultados")
                .when().get(PAGE_URL)
                .then()
                .statusCode(200)
                .body(containsString("data-kit-table"));
    }
}
