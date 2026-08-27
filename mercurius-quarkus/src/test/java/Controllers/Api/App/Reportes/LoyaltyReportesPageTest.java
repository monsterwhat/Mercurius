package Controllers.Api.App.Reportes;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import Models.Clients;
import Models.PuntosTransaccion;
import Services.ClientService;
import Services.LoyaltyService;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T20 — page contract for {@code GET /app/reportes/loyalty}: top-customers
 * table, all-clients table and the per-client points-history fragment, with
 * row-count parity against {@link LoyaltyService} / {@link ClientService}.
 */
@QuarkusTest
@Tag("reportes-pages")
class LoyaltyReportesPageTest extends support.ContextPathIsolation {

    private static final String PAGE_URL = "/Mercurius/app/reportes/loyalty";

    @Inject
    LoyaltyService loyaltyService;

    @Inject
    ClientService clientService;

    @Inject
    jakarta.transaction.UserTransaction utx;

    /**
     * GService.delete requires a MANAGED entity (documented quirk) and each
     * service call runs in its own transaction, so cleanup reloads the row and
     * deletes it inside one explicit transaction.
     */
    private void cleanupTransaccion(PuntosTransaccion transaccion) throws Exception {
        if (transaccion == null || transaccion.getId() <= 0) {
            return;
        }
        utx.begin();
        try {
            PuntosTransaccion managed = loyaltyService.find(transaccion.getId());
            if (managed != null) {
                loyaltyService.delete(managed);
            }
        } finally {
            utx.commit();
        }
    }

    private void cleanupCliente(Clients cliente) throws Exception {
        if (cliente == null || cliente.getCode() <= 0) {
            return;
        }
        utx.begin();
        try {
            Clients managed = clientService.find(cliente.getCode());
            if (managed != null) {
                clientService.delete(managed);
            }
        } finally {
            utx.commit();
        }
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void pageRendersStatsAndTables() {
        given()
                .when().get(PAGE_URL)
                .then()
                .statusCode(200)
                .body(containsString("Programa de Lealtad - Reportes"))
                .body(containsString("Top Clientes por Puntos"))
                .body(containsString("% Cashback"))
                .body(containsString("Meses Inactividad"));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void historyFragmentRendersSeededTransaction() throws Exception {
        Clients cliente = null;
        PuntosTransaccion transaccion = null;
        try {
            cliente = seedClientWithPoints("IT-T20-Loyal-" + UUID.randomUUID(), 50);
            transaccion = new PuntosTransaccion();
            transaccion.setCliente(cliente);
            transaccion.setTipoTransaccion("earn");
            transaccion.setPuntos(java.math.BigDecimal.valueOf(50));
            transaccion.setSaldoPuntos(java.math.BigDecimal.valueOf(50));
            transaccion.setDescripcion("IT-T20 historial seed");
            loyaltyService.create(transaccion);

            given()
                    .when().get(PAGE_URL + "/" + cliente.getCode() + "/historial")
                    .then()
                    .statusCode(200)
                    .body(containsString("Historial de Puntos"))
                    .body(containsString("IT-T20 historial seed"))
                    .body(containsString("earn"));

            String html = given()
                    .when().get(PAGE_URL + "/" + cliente.getCode() + "/historial")
                    .then().statusCode(200).extract().asString();
            assertEquals(loyaltyService.getCustomerPointsHistory(cliente).size(),
                    FacturasReportesPageTest.countRows(html),
                    "history fragment rows must equal getCustomerPointsHistory().size()");
        } finally {
            cleanupTransaccion(transaccion);
            cleanupCliente(cliente);
        }
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void rowCountMatchesDirectServiceCalls() throws Exception {
        Clients cliente = null;
        try {
            cliente = seedClientWithPoints("IT-T20-Top-" + UUID.randomUUID(), 25);

            String html = given()
                    .queryParam("size", 500)
                    .when().get(PAGE_URL)
                    .then()
                    .statusCode(200)
                    .extract().asString();

            int topRows = html.split("loyalty-top-tabla", -1).length - 1 > 0
                    ? countSection(html, "loyalty-top-tabla", "loyalty-tabla-container")
                    : 0;
            List<Clients> top = loyaltyService.getTopLoyaltyCustomers(10);
            final Clients seededCliente = cliente;
            assertTrue(top.stream().anyMatch(c -> c.getCode() == seededCliente.getCode()),
                    "client with points must appear in the top table");
            assertEquals(top.size(), topRows,
                    "top table rows must equal getTopLoyaltyCustomers(10).size()");

            int allRowsStart = html.indexOf("id=\"loyalty-tabla\"");
            assertTrue(allRowsStart > 0, "all-clients kit table must render");
            String allTableHtml = html.substring(allRowsStart);
            assertEquals(clientService.listAll().size(),
                    allTableHtml.split("data-row-key=", -1).length - 1,
                    "all-clients table rows must equal ClientService.listAll().size()");
        } finally {
            cleanupCliente(cliente);
        }
    }

    private Clients seedClientWithPoints(String name, double points) {
        Clients cliente = new Clients();
        cliente.setName(name);
        cliente.setAddress("Barrio IT-T20");
        cliente.setProvincia("1");
        cliente.setEmail(name.toLowerCase().replace(' ', '.') + "@mercurius.local");
        cliente.setBirthDate(Date.from(
                LocalDate.of(1990, 1, 31).atStartOfDay(ZoneId.systemDefault()).toInstant()));
        cliente.setIdType("Cedula Fisica");
        cliente.setIdNumber("IT-" + UUID.randomUUID().toString().substring(0, 8));
        cliente.setDiscount(0.0);
        cliente.setPhoneNumber("8888-2020");
        cliente.setTaxpayer(true);
        cliente.setZoneCode(1);
        cliente.setTipoIdentificacion("01");
        cliente.setStatus(Boolean.TRUE);
        cliente.setPuntosAcumulados(java.math.BigDecimal.valueOf(points));
        cliente.setStatusPuntos("active");
        clientService.create(cliente);
        return cliente;
    }

    /** Counts data-row-key occurrences between two section markers. */
    private static int countSection(String html, String startMarker, String endMarker) {
        int start = html.indexOf(startMarker);
        int end = html.indexOf(endMarker, start);
        if (start < 0 || end < 0) {
            return 0;
        }
        return html.substring(start, end).split("data-row-key=", -1).length - 1;
    }
}
