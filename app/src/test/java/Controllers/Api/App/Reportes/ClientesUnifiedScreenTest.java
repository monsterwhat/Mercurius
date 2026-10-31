package Controllers.Api.App.Reportes;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import Models.Clientes;
import Services.ClientService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

@QuarkusTest
@Tag("reportes-pages")
class ClientesUnifiedScreenTest extends support.ContextPathIsolation {

    private static final String PAGE_URL = "/Mercurius/app/reportes/clientes";
    private static final String BASE = "/Mercurius";
    private static final String SEEDED_CLIENT = "Cliente Contado";

    @Inject
    ClientService clientService;

    @Test
    @TestSecurity(user = "usuario", roles = {"usuario"})
    void usuarioSeesArchiveControl() {
        given()
                .when().get(PAGE_URL)
                .then()
                .statusCode(200)
                .body(containsString("data-archivar"));
    }

    @Test
    @TestSecurity(user = "facturacion", roles = {"facturacion"})
    void facturacionSeesNoMutationControlsButKeepsTableAndExport() {
        given()
                .when().get(PAGE_URL)
                .then()
                .statusCode(200)
                .body(containsString("data-kit-table"))
                .body(containsString("Exportar Excel"))
                .body(not(containsString("Nuevo Cliente")))
                .body(not(containsString("Editar cliente")))
                .body(not(containsString("data-archivar")));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void adminCanArchiveSeededClient() {
        archiveSeededClient();
    }

    @Test
    @TestSecurity(user = "usuario", roles = {"usuario"})
    void usuarioCanArchiveSeededClient() {
        archiveSeededClient();
    }

    private void archiveSeededClient() {
        Clientes seeded = findSeeded();
        int code = seeded.getCode();
        boolean originalStatus = Boolean.TRUE.equals(seeded.getStatus());
        try {
            String token = given()
                    .when().get(PAGE_URL)
                    .then().statusCode(200)
                    .extract().cookie("csrf-token");
            org.junit.jupiter.api.Assertions.assertNotNull(token);

            given()
                    .cookie("csrf-token", token)
                    .header("X-CSRF-TOKEN", token)
                    .when().delete(BASE + "/api/app/clientes/" + code)
                    .then()
                    .statusCode(org.hamcrest.Matchers.anyOf(
                            org.hamcrest.Matchers.is(200), org.hamcrest.Matchers.is(204)));

            Clientes after = clientService.find(code);
            org.junit.jupiter.api.Assertions.assertNotNull(after);
            org.junit.jupiter.api.Assertions.assertFalse(
                    Boolean.TRUE.equals(after.getStatus()),
                    "archived client must no longer be active");
        } finally {
            Clientes c = clientService.find(code);
            if (c != null) {
                c.setStatus(originalStatus);
                clientService.update(c);
            }
        }
    }

    private Clientes findSeeded() {
        List<Clientes> all = clientService.listAll();
        for (Clientes c : all) {
            if (SEEDED_CLIENT.equals(c.getName())) {
                return c;
            }
        }
        throw new IllegalStateException("seeded client not found");
    }
}