package Controllers.Api.App.Reportes;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import Models.Correos.ReporteProgramado;
import Services.Correos.ReportesProgramadosService;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T20 — page contract for {@code GET /app/reportes/correos} (read-only
 * listing of scheduled email reports) with row-count parity against
 * {@link ReportesProgramadosService#listAll()}.
 */
@QuarkusTest
@Tag("reportes-pages")
class CorreosReportesPageTest extends support.ContextPathIsolation {

    private static final String PAGE_URL = "/Mercurius/app/reportes/correos";

    @Inject
    ReportesProgramadosService reportesProgramadosService;

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "facturacion"})
    void pageRendersListingOnly() {
        given()
                .when().get(PAGE_URL)
                .then()
                .statusCode(200)
                .body(containsString("Reportes Programados X Correo Electronico"))
                .body(containsString("data-kit-table"))
                // mutation actions belong to T24 — must not appear on this view
                .body(not(containsString("Crear Reporte Programado")));
    }

    @Test
    @TestSecurity(user = "cajero", roles = {"inventario"})
    void nonFacturacionRoleIsForbidden() {
        given().when().get(PAGE_URL).then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "facturacion"})
    void rowCountMatchesDirectServiceCallAndShowsFixture() {
        ReporteProgramado seeded = null;
        try {
            seeded = new ReporteProgramado();
            seeded.setPerfil("IT-T20-Reporte-" + UUID.randomUUID());
            seeded.setCorreos(List.of("it-t20@mercurius.local"));
            seeded.setFrecuencia(List.of("Diario"));
            seeded.setReportes(List.of("Ventas"));
            seeded.setStatus(true);
            reportesProgramadosService.create(seeded);

            String html = given()
                    .queryParam("size", 500)
                    .when().get(PAGE_URL)
                    .then()
                    .statusCode(200)
                    .extract().asString();

            assertEquals(reportesProgramadosService.listAll().size(), FacturasReportesPageTest.countRows(html),
                    "correos page rows must equal ReportesProgramadosService.listAll().size()");
            org.junit.jupiter.api.Assertions.assertTrue(html.contains(seeded.getPerfil()),
                    "seeded report perfil must be visible on the listing");
        } finally {
            if (seeded != null) {
                reportesProgramadosService.delete(seeded);
            }
        }
    }
}
