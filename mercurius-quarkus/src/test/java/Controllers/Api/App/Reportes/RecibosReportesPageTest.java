package Controllers.Api.App.Reportes;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import Models.Encabezado.Encabezado;
import Models.ComprobantesRecibidos;
import Services.ComprobantesRecibidosService;

import java.time.LocalDateTime;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T20 — page contract for the Recibos report pages, including the vencido
 * filter correctness scenario: an unpaid received invoice whose due date
 * (fechaEmision + plazoCredito days) is past must appear ONLY on
 * /app/reportes/recibos/vencidos, while one with a future due date must
 * appear ONLY on /app/reportes/recibos/pendientes. Row counts must match the
 * direct service calls ({@link ComprobantesRecibidosService#listPendientes()}
 * / {@link ComprobantesRecibidosService#listVencidas()}).
 *
 * <p>Fixtures are committed outside @TestTransaction so the HTTP request
 * thread can see them, and removed in a finally block; %test boots against a
 * drop-and-create schema so a failed assertion cannot poison later runs.</p>
 */
@QuarkusTest
@Tag("reportes-pages")
class RecibosReportesPageTest extends support.ContextPathIsolation {

    private static final String PENDIENTES_URL = "/Mercurius/app/reportes/recibos/pendientes";
    private static final String VENCIDOS_URL = "/Mercurius/app/reportes/recibos/vencidos";

    @Inject
    ComprobantesRecibidosService recibidosService;

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "facturacion"})
    void pendientesPageRenders() {
        given()
                .when().get(PENDIENTES_URL)
                .then()
                .statusCode(200)
                .body(containsString("Recibos Pendientes"))
                .body(containsString("data-kit-table"));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "facturacion"})
    void vencidosPageRenders() {
        given()
                .when().get(VENCIDOS_URL)
                .then()
                .statusCode(200)
                .body(containsString("Recibos Vencidos"))
                .body(containsString("data-kit-table"));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "facturacion"})
    void hxRequestReturnsFragmentWithoutLayout() {
        given()
                .header("HX-Request", "true")
                .when().get(PENDIENTES_URL)
                .then()
                .statusCode(200)
                .body(containsString("data-kit-table"))
                .body(not(containsString("<footer")));
        given()
                .header("HX-Request", "true")
                .when().get(VENCIDOS_URL)
                .then()
                .statusCode(200)
                .body(containsString("data-kit-table"))
                .body(not(containsString("<footer")));
    }

    @Test
    @TestSecurity(user = "cajero", roles = {"inventario"})
    void nonFacturacionRoleIsForbidden() {
        given().when().get(PENDIENTES_URL).then().statusCode(403);
        given().when().get(VENCIDOS_URL).then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "facturacion"})
    void vencidoFilterSeparatesBucketsAndCountsMatchService() {
        String vencidoConsecutivo = "IT-T20-V-" + UUID.randomUUID();
        String pendienteConsecutivo = "IT-T20-P-" + UUID.randomUUID();
        ComprobantesRecibidos vencida = null;
        ComprobantesRecibidos pendiente = null;
        try {
            vencida = seed(vencidoConsecutivo, LocalDateTime.now().minusDays(30), "5");
            pendiente = seed(pendienteConsecutivo, LocalDateTime.now().minusDays(2), "30");

            String pendientesHtml = given()
                    .queryParam("size", 500)
                    .when().get(PENDIENTES_URL)
                    .then()
                    .statusCode(200)
                    .extract().asString();
            String vencidosHtml = given()
                    .queryParam("size", 500)
                    .when().get(VENCIDOS_URL)
                    .then()
                    .statusCode(200)
                    .extract().asString();

            assertTrue(pendientesHtml.contains(pendienteConsecutivo),
                    "future-due invoice must be listed under pendientes");
            assertTrue(!pendientesHtml.contains(vencidoConsecutivo),
                    "past-due invoice must NOT be listed under pendientes");
            assertTrue(vencidosHtml.contains(vencidoConsecutivo),
                    "past-due invoice must be listed under vencidos");
            assertTrue(!vencidosHtml.contains(pendienteConsecutivo),
                    "future-due invoice must NOT be listed under vencidos");

            assertEquals(recibidosService.listPendientes().size(), countRows(pendientesHtml),
                    "pendientes page rows must equal listPendientes().size()");
            assertEquals(recibidosService.listVencidas().size(), countRows(vencidosHtml),
                    "vencidos page rows must equal listVencidas().size()");
        } finally {
            if (vencida != null) {
                recibidosService.delete(vencida);
            }
            if (pendiente != null) {
                recibidosService.delete(pendiente);
            }
        }
    }

    /**
     * Minimal unpaid received invoice: encabezado cascades from the comprobante
     * (@OneToOne cascade ALL); paid defaults to false, which is what both
     * bucket queries filter on.
     */
    private ComprobantesRecibidos seed(String consecutivo, LocalDateTime fechaEmision, String plazoCredito) {
        Encabezado encabezado = new Encabezado();
        encabezado.setNumeroConsecutivo(consecutivo);
        encabezado.setFechaEmision(fechaEmision);
        encabezado.setPlazoCredito(plazoCredito);

        ComprobantesRecibidos comprobante = new ComprobantesRecibidos();
        comprobante.setEncabezado(encabezado);
        comprobante.setStatus(true);
        comprobante.setProcessed(false);
        recibidosService.create(comprobante);
        return comprobante;
    }

    static int countRows(String html) {
        return html.split("data-row-key=", -1).length - 1;
    }
}
