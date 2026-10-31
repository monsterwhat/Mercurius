package Controllers.Api.App.FacturasRecibidas;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.ws.rs.core.HttpHeaders;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.not;

/**
 * D2 — the received-invoices board is the canonical place for the
 * pendientes/vencidas buckets and carries its own XLSX export.
 */
@QuarkusTest
@Tag("facturas-recibidas")
class D2ReceivedBoardExportTest extends support.ContextPathIsolation {

    private static final String BASE = "/Mercurius";

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void adminGetsPendientesPageWithExportForm() {
        given()
                .queryParam("bucket", "pendientes")
                .when().get(BASE + "/app/facturas-recibidas")
                .then()
                .statusCode(200)
                .body(containsString("dataset\" value=\"recibos-pendientes\""));
    }

    @Test
    @TestSecurity(user = "facturador", roles = {"facturacion"})
    void facturacionGetsPendientesPageWithExportForm() {
        given()
                .queryParam("bucket", "pendientes")
                .when().get(BASE + "/app/facturas-recibidas")
                .then()
                .statusCode(200)
                .body(containsString("dataset\" value=\"recibos-pendientes\""));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void adminGetsVencidasPageWithExportForm() {
        given()
                .queryParam("bucket", "vencidas")
                .when().get(BASE + "/app/facturas-recibidas")
                .then()
                .statusCode(200)
                .body(containsString("dataset\" value=\"recibos-vencidos\""));
    }

    @Test
    @TestSecurity(user = "facturador", roles = {"facturacion"})
    void facturacionGetsVencidasPageWithExportForm() {
        given()
                .queryParam("bucket", "vencidas")
                .when().get(BASE + "/app/facturas-recibidas")
                .then()
                .statusCode(200)
                .body(containsString("dataset\" value=\"recibos-vencidos\""));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void todasBucketHasNoExportForm() {
        given()
                .queryParam("bucket", "todas")
                .when().get(BASE + "/app/facturas-recibidas")
                .then()
                .statusCode(200)
                .body(not(containsString("/api/app/export")));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void postExportRecibosPendientesStreamsXlsx() {
        String token = given()
                .queryParam("bucket", "pendientes")
                .when().get(BASE + "/app/facturas-recibidas")
                .then().statusCode(200)
                .extract().cookie("csrf-token");
        assertThat(token).isNotBlank();

        Response response = given()
                .cookie("csrf-token", token)
                .header("X-CSRF-TOKEN", token)
                .contentType(ContentType.URLENC)
                .formParam("dataset", "recibos-pendientes")
                .formParam("type", "xlsx")
                .when().post(BASE + "/api/app/export");

        response.then()
                .statusCode(200)
                .header(HttpHeaders.CONTENT_DISPOSITION, notNullValue());

        byte[] bytes = response.asByteArray();
        assertThat(bytes.length).isGreaterThan(4);
        assertThat(new String(bytes, 0, 2, StandardCharsets.US_ASCII)).isEqualTo("PK");
    }
}
