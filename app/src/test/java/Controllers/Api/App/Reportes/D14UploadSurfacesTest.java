package Controllers.Api.App.Reportes;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * D14 — the received-invoices board is the only visible XML upload surface.
 *
 * <p>Pins the new page route, its role gate, and the removal of the two
 * misplaced upload cards from the emitted board and the inventory board.</p>
 */
@QuarkusTest
@Tag("reportes-pages")
class D14UploadSurfacesTest extends support.ContextPathIsolation {

    private static final String BASE = "/Mercurius";

    @Test
    @TestSecurity(user = "facturador", roles = {"facturacion"})
    void receivedBoardPageRendersTheOnlyUploadForm() {
        given()
                .when().get(BASE + "/app/facturas-recibidas")
                .then()
                .statusCode(200)
                .body(containsString("Facturas Recibidas"))
                .body(containsString("Subir Facturas Electrónicas"))
                .body(containsString("/api/app/facturas-recibidas/upload"));
    }

    @Test
    @TestSecurity(user = "inventario", roles = {"inventario"})
    void receivedBoardPageIsForbiddenForInventoryRole() {
        given()
                .when().get(BASE + "/app/facturas-recibidas")
                .then()
                .statusCode(403);
    }

    @Test
    @TestSecurity(user = "facturador", roles = {"facturacion"})
    void emittedBoardNoLongerShowsTheUploadForm() {
        given()
                .when().get(BASE + "/app/facturas")
                .then()
                .statusCode(200)
                .body(containsString("Facturas Emitidas"))
                .body(not(containsString("/api/app/facturas-recibidas/upload")));
    }

    @Test
    @TestSecurity(user = "inventario", roles = {"inventario"})
    void inventoryBoardNoLongerShowsTheUploadForm() {
        given()
                .when().get(BASE + "/app/inventarios")
                .then()
                .statusCode(200)
                .body(containsString("Gestión de Inventario"))
                .body(not(containsString("/api/app/inventario/upload")));
    }
}
