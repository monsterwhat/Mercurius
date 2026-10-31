package Controllers.Api.App.Reportes;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * Regression guard for the Categorías container: the active tab is read from
 * {@code ?tab=} and written back to the URL, so the hub's "Departamentos" and
 * "Familias" entries can deep-link to their own tab (the hub used to send both
 * to the Familias tab).
 */
@QuarkusTest
@Tag("reportes-pages")
class CategoriasPageTest extends support.ContextPathIsolation {

    @Test
    @TestSecurity(user = "gestor", roles = {"inventario"})
    void tabIsReadFromAndWrittenToTheUrl() {
        given()
                .when().get("/Mercurius/app/categorias?tab=departamentos")
                .then()
                .statusCode(200)
                .body(containsString("new URLSearchParams(location.search).get('tab')"))
                .body(containsString("history.replaceState(null, '', '?tab=' + tab)"));
    }
}
