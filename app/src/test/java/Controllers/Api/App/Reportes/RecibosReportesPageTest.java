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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Las dos vistas de reportes "pendientes de pago" y "vencidas" se fusionaron
 * como PESTAÑAS del buzón de facturas recibidas: sus rutas viejas redirigen y
 * la regla del crédito vencido se comprueba sobre la tabla del buzón.
 *
 * <p>Un comprobante recibido sin pagar cuyo vencimiento (fechaEmision +
 * plazoCredito días) ya pasó debe aparecer SOLO en la bandeja
 * {@code vencidas}; uno con vencimiento futuro, SOLO en {@code pendientes}. Los
 * conteos deben coincidir con {@link ComprobantesRecibidosService#listPendientes()}
 * y {@link ComprobantesRecibidosService#listVencidas()}.</p>
 *
 * <p>Las fixtures se confirman fuera de @TestTransaction para que el hilo HTTP
 * las vea, y se borran en un finally.</p>
 */
@QuarkusTest
@Tag("reportes-pages")
class RecibosReportesPageTest extends support.ContextPathIsolation {

    /** Rutas viejas: ahora son redirecciones al buzón. */
    private static final String PENDIENTES_URL = "/app/reportes/recibos/pendientes";
    private static final String VENCIDOS_URL = "/app/reportes/recibos/vencidos";

    /** Tabla del buzón de facturas recibidas (fragmento). */
    private static final String TABLA_URL = "/api/app/facturas-recibidas/tabla";

    @Inject
    ComprobantesRecibidosService recibidosService;

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "facturacion"})
    void rutasViejasRedirigenAlBuzon() {
        // La raíz también: antes devolvía "Not Found" pelado.
        given().redirects().follow(false)
                .when().get("/app/reportes/recibos")
                .then()
                .statusCode(302)
                .header("Location", containsString("/app/facturas-recibidas?bucket=pendientes"));
        given().redirects().follow(false)
                .when().get(PENDIENTES_URL)
                .then()
                .statusCode(302)
                .header("Location", containsString("/app/facturas-recibidas?bucket=pendientes"));
        given().redirects().follow(false)
                .when().get(VENCIDOS_URL)
                .then()
                .statusCode(302)
                .header("Location", containsString("/app/facturas-recibidas?bucket=vencidas"));
    }

    @Test
    @TestSecurity(user = "cajero", roles = {"inventario"})
    void nonFacturacionRoleIsForbidden() {
        given().redirects().follow(false).when().get(PENDIENTES_URL).then().statusCode(403);
        given().redirects().follow(false).when().get(VENCIDOS_URL).then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "facturacion"})
    void vencidoFilterSeparatesBucketsAndCountsMatchService() {
        String vencidoConsecutivo = "IT-V-" + UUID.randomUUID().toString().substring(0, 8);
        String pendienteConsecutivo = "IT-P-" + UUID.randomUUID().toString().substring(0, 8);
        ComprobantesRecibidos vencida = null;
        ComprobantesRecibidos pendiente = null;
        try {
            vencida = seed(vencidoConsecutivo, LocalDateTime.now().minusDays(30), "5");
            pendiente = seed(pendienteConsecutivo, LocalDateTime.now().minusDays(2), "30");

            String pendientesHtml = given()
                    .header("HX-Request", "true")
                    .queryParam("bucket", "pendientes")
                    .queryParam("size", 500)
                    .when().get(TABLA_URL)
                    .then()
                    .statusCode(200)
                    .extract().asString();
            String vencidosHtml = given()
                    .header("HX-Request", "true")
                    .queryParam("bucket", "vencidas")
                    .queryParam("size", 500)
                    .when().get(TABLA_URL)
                    .then()
                    .statusCode(200)
                    .extract().asString();

            assertTrue(pendientesHtml.contains(pendienteConsecutivo),
                    "future-due invoice must be listed under pendientes");
            assertTrue(!pendientesHtml.contains(vencidoConsecutivo),
                    "past-due invoice must NOT be listed under pendientes");
            assertTrue(vencidosHtml.contains(vencidoConsecutivo),
                    "past-due invoice must be listed under vencidas");
            assertTrue(!vencidosHtml.contains(pendienteConsecutivo),
                    "future-due invoice must NOT be listed under vencidas");

            assertEquals(recibidosService.listPendientes().size(), countRows(pendientesHtml),
                    "pendientes rows must equal listPendientes().size()");
            assertEquals(recibidosService.listVencidas().size(), countRows(vencidosHtml),
                    "vencidas rows must equal listVencidas().size()");
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
