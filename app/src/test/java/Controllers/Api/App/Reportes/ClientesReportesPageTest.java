package Controllers.Api.App.Reportes;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import Models.Clients;
import Services.ClientService;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T20 — page contract for {@code GET /app/reportes/clientes}: stat cards,
 * HTMX fragment and row-count parity against {@link ClientService#listAll()}.
 */
@QuarkusTest
@Tag("reportes-pages")
class ClientesReportesPageTest extends support.ContextPathIsolation {

    private static final String PAGE_URL = "/Mercurius/app/reportes/clientes";

    @Inject
    ClientService clientService;

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void pageRendersStatCardsAndTable() {
        given()
                .when().get(PAGE_URL)
                .then()
                .statusCode(200)
                .body(containsString("Reportes de Clientes"))
                .body(containsString("Clientes Activos"))
                .body(containsString("Clientes Inactivos"))
                .body(containsString("Total Clientes"))
                .body(containsString("data-kit-table"));
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
    void rowCountMatchesDirectServiceCall() {
        String html = given()
                .queryParam("size", 500)
                .when().get(PAGE_URL)
                .then()
                .statusCode(200)
                .extract().asString();

        assertEquals(clientService.listAll().size(), FacturasReportesPageTest.countRows(html),
                "clientes page rows must equal ClientService.listAll().size()");
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void textFilterRoundTripNarrowsToOneFixture() {
        Clients cliente = null;
        String marker = "IT-T20-Filtro-" + UUID.randomUUID();
        try {
            cliente = new Clients();
            cliente.setName(marker);
            cliente.setAddress("Barrio IT-T20");
            cliente.setProvincia("1");
            cliente.setEmail(marker.toLowerCase() + "@mercurius.local");
            cliente.setBirthDate(Date.from(
                    LocalDate.of(1992, 7, 1).atStartOfDay(ZoneId.systemDefault()).toInstant()));
            cliente.setIdType("Cedula Fisica");
            cliente.setIdNumber("IT-" + UUID.randomUUID().toString().substring(0, 8));
            cliente.setDiscount(0.0);
            cliente.setPhoneNumber("8888-7070");
            cliente.setTaxpayer(false);
            cliente.setZoneCode(1);
            cliente.setTipoIdentificacion("01");
            cliente.setStatus(Boolean.TRUE);
            clientService.create(cliente);

            String html = given()
                    .queryParam("size", 500)
                    .queryParam("q", marker)
                    .when().get(PAGE_URL)
                    .then()
                    .statusCode(200)
                    .extract().asString();

            assertEquals(1, FacturasReportesPageTest.countRows(html),
                    "the unique marker filter must leave exactly the fixture row");
        } finally {
            if (cliente != null) {
                clientService.delete(cliente);
            }
        }
    }
}
