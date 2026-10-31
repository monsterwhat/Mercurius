package Controllers.Api.App.Correos;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.ws.rs.core.HttpHeaders;
import java.nio.charset.StandardCharsets;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class CorreosManagementExportTest extends support.ContextPathIsolation {

    private static final String MGMT_PAGE = "/Mercurius/app/correos/reportes";
    private static final String REPORT_PAGE = "/Mercurius/app/reportes/correos";
    private static final String EXPORT = "/Mercurius/api/app/export";

    @Test
    @TestSecurity(user = "tributacion-user", roles = {"tributacion"})
    void tributacionCanOpenManagementPageWithExportForm() {
        given()
                .when().get(MGMT_PAGE)
                .then()
                .statusCode(200)
                .body(containsString("dataset\" value=\"correos\""));
    }

    @Test
    @TestSecurity(user = "facturador", roles = {"facturacion"})
    void facturacionSeesExportFormButNoMutationControls() {
        given()
                .when().get(MGMT_PAGE)
                .then()
                .statusCode(200)
                .body(containsString("dataset\" value=\"correos\""))
                .body(not(containsString("Crear Reporte Programado")))
                .body(not(containsString("Editar reporte programado")))
                .body(not(containsString("Eliminar reporte programado")))
                .body(not(containsString("hx-put=")))
                .body(not(containsString("hx-delete=")));
    }

    @Test
    @TestSecurity(user = "tributacion-user", roles = {"tributacion"})
    void tributacionCanExportCorreosXlsx() {
        String token = given()
                .when().get(MGMT_PAGE)
                .then().statusCode(200)
                .extract().cookie("csrf-token");
        Assertions.assertThat(token).isNotBlank();

        Response response = given()
                .cookie("csrf-token", token)
                .header("X-CSRF-TOKEN", token)
                .contentType(ContentType.URLENC)
                .formParam("dataset", "correos")
                .formParam("type", "xlsx")
                .when().post(EXPORT);

        response.then()
                .statusCode(200)
                .header(HttpHeaders.CONTENT_DISPOSITION, notNullValue());

        byte[] bytes = response.asByteArray();
        Assertions.assertThat(bytes.length).isGreaterThan(4);
        Assertions.assertThat(new String(bytes, 0, 2, StandardCharsets.US_ASCII)).isEqualTo("PK");
    }

    @Test
    @TestSecurity(user = "facturador", roles = {"facturacion"})
    void facturacionCanStillOpenLegacyCorreosReport() {
        given()
                .when().get(REPORT_PAGE)
                .then()
                .statusCode(200)
                .body(containsString("Reportes por Correo"));
    }
}