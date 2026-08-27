package Controllers.Api.App.Reportes;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import Services.SeasonalityService;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T20 — page contract for {@code GET /app/reportes/estacionalidad}: the four
 * aggregate tables (monthly, day-of-week, department, family) plus daily
 * sales, with row-count parity of the daily table against
 * {@link SeasonalityService#getDailySales(Date, Date)} using the same legacy
 * 12-month default window. Charts are deferred to T29.
 */
@QuarkusTest
@Tag("reportes-pages")
class EstacionalidadReportesPageTest extends support.ContextPathIsolation {

    private static final String PAGE_URL = "/Mercurius/app/reportes/estacionalidad";

    @Inject
    SeasonalityService seasonalityService;

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void pageRendersFourTablesAndDayNames() {
        given()
                .when().get(PAGE_URL)
                .then()
                .statusCode(200)
                .body(containsString("Análisis de Estacionalidad"))
                .body(containsString("Tendencia Mensual de Ventas"))
                .body(containsString("Ventas por Día de la Semana"))
                .body(containsString("Ventas por Departamento"))
                .body(containsString("Ventas por Familia"))
                .body(containsString("Datos Diarios"))
                // legacy day labels preserved
                .body(containsString("Lunes"))
                .body(containsString("Domingo"));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void hxRequestReturnsFragmentWithoutLayout() {
        given()
                .header("HX-Request", "true")
                .when().get(PAGE_URL)
                .then()
                .statusCode(200)
                .body(containsString("estacionalidad-mensual"))
                .body(not(containsString("<footer")));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void explicitDateRangeRoundTrips() {
        String inicio = LocalDate.now().minusMonths(3).toString();
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

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void dailyRowsMatchDirectServiceCallWithLegacyDefaults() {
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusMonths(12);
        Date startDate = Date.from(start.atStartOfDay(ZoneId.systemDefault()).toInstant());
        Date endDate = Date.from(end.atStartOfDay(ZoneId.systemDefault()).toInstant());
        int expected = seasonalityService.getDailySales(startDate, endDate).size();

        String html = given()
                .when().get(PAGE_URL)
                .then()
                .statusCode(200)
                .extract().asString();

        int diariosStart = html.indexOf("id=\"estacionalidad-diarios\"");
        org.junit.jupiter.api.Assertions.assertTrue(diariosStart > 0, "daily table must render");
        int actual = html.substring(diariosStart).split("data-row-key=", -1).length - 1;

        assertEquals(expected, actual,
                "daily table rows must equal getDailySales(last12m).size()");
    }
}
