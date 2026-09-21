package Controllers.Api.App.Reportes;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import Services.ProfitAnalysisService;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T20 — page contract for {@code GET /app/reportes/margenes}: stat header,
 * tab sections, T17 export form, HTMX fragment and row-count parity of the
 * department-snapshot table against
 * {@link ProfitAnalysisService#getMarginTrend(String, String, Date, Date)}
 * with the same legacy 30-day default window.
 */
@QuarkusTest
@Tag("reportes-pages")
class MargenesReportesPageTest extends support.ContextPathIsolation {

    private static final String PAGE_URL = "/Mercurius/app/reportes/margenes";

    @Inject
    ProfitAnalysisService profitAnalysisService;

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "facturacion"})
    void pageRendersStatsTabsAndExportForm() {
        given()
                .when().get(PAGE_URL)
                .then()
                .statusCode(200)
                .body(containsString("Análisis de Márgenes de Utilidad"))
                .body(containsString("Margen Promedio"))
                .body(containsString("Ingresos Totales"))
                .body(containsString("Resumen por Departamento"))
                .body(containsString("Top Artículos por Margen"))
                .body(containsString("Artículos con Bajo Margen"))
                // T17 export contract: POST /api/app/export with dataset=profit-margins
                .body(containsString("/api/app/export"))
                .body(containsString("profit-margins"));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "facturacion"})
    void hxRequestReturnsFragmentWithoutLayout() {
        given()
                .header("HX-Request", "true")
                .when().get(PAGE_URL)
                .then()
                .statusCode(200)
                .body(containsString("margenes-tabla"))
                .body(not(containsString("<footer")));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "facturacion"})
    void topAndBajoSectionsRender() {
        given()
                .queryParam("seccion", "top")
                .when().get(PAGE_URL)
                .then()
                .statusCode(200)
                .body(containsString("margenes-top-tabla"));
        given()
                .queryParam("seccion", "bajo")
                .when().get(PAGE_URL)
                .then()
                .statusCode(200)
                .body(containsString("margenes-bajo-tabla"));
    }

    @Test
    @TestSecurity(user = "cajero", roles = {"inventario"})
    void nonFacturacionRoleIsForbidden() {
        given().when().get(PAGE_URL).then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "facturacion"})
    void snapshotRowsMatchDirectServiceCallWithLegacyDefaults() {
        Date end = Date.from(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant());
        Date start = Date.from(LocalDate.now().minusDays(30).atStartOfDay(ZoneId.systemDefault()).toInstant());
        int expected = profitAnalysisService.getMarginTrend(null, "department", start, end).size();

        String html = given()
                .queryParam("size", 500)
                .when().get(PAGE_URL)
                .then()
                .statusCode(200)
                .extract().asString();

        assertEquals(expected, FacturasReportesPageTest.countRows(html),
                "snapshot table rows must equal getMarginTrend(null, department, last30d).size()");
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "facturacion"})
    void explicitDateRangeRoundTrips() {
        String inicio = LocalDate.now().minusDays(7).toString();
        String fin = LocalDate.now().toString();
        given()
                .queryParam("inicio", inicio)
                .queryParam("fin", fin)
                .when().get(PAGE_URL)
                .then()
                .statusCode(200)
                .body(containsString("value=\"" + inicio + "\""))
                .body(containsString("value=\"" + fin + "\""));
    }
}
