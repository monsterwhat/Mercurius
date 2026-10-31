package Controllers.Api.App.Reportes;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Regression guard for the reports hub ({@code GET /app/reportes}).
 *
 * <p>The "Facturas emitidas" entry used to point at {@code /app/recibos}, which
 * is a legacy redirect to the <b>received</b> invoices inbox. The emitted board
 * lives at {@code /app/facturas}. This test pins the corrected destination and
 * keeps the redirect itself intact (the redirect is intentional legacy naming).</p>
 */
@QuarkusTest
@Tag("reportes-pages")
class ReportsHubLinksTest extends support.ContextPathIsolation {

    private static final String HUB = "/Mercurius/app/reportes";

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void emittedInvoicesEntryPointsAtTheEmittedBoard() {
        given()
                .when().get(HUB)
                .then()
                .statusCode(200)
                .body(containsString(
                        "<a class=\"menu-link py-3 px-3\" href=\"/Mercurius/app/facturas\""
                                + " title=\"Lista completa de facturas emitidas\">"))
                .body(not(containsString("href=\"/Mercurius/app/recibos\"")));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void familyAndDepartmentEntriesCarryTheirTab() {
        given()
                .when().get(HUB)
                .then()
                .statusCode(200)
                .body(containsString(
                        "href=\"/Mercurius/app/categorias?tab=departamentos\""
                                + " title=\"Reporte de artículos por departamento\""))
                .body(containsString(
                        "href=\"/Mercurius/app/categorias?tab=familias\""
                                + " title=\"Reporte de artículos por familia\""));
    }
}
