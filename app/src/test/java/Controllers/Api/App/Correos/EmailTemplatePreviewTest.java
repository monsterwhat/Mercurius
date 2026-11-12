package Controllers.Api.App.Correos;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import support.AppBase;

/**
 * La vista previa debe resolver TODOS los marcadores de las plantillas por
 * caso (comprobantes y reportes), no solo los cinco históricos. Regresión:
 * {{tipoComprobante}}, {{moneda}}, {{negocio}}, etc. salían literales.
 */
@QuarkusTest
class EmailTemplatePreviewTest extends AppBase {

    private static final String BASE = "/api/app/email-templates";

    private Map<String, String> session() {
        Response mint = given().when().get(BASE + "/table");
        mint.then().statusCode(200);
        return new HashMap<>(mint.getCookies());
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "tributacion"})
    void previewResolvesEveryCasePlaceholder() {
        Map<String, String> cookies = session();
        String csrf = cookies.get("csrf-token");

        String cuerpo = "<p>{{cliente}} {{consecutivo}} {{fecha}} {{total}} "
                + "{{tipoComprobante}} {{estado}} {{referencia}} {{motivo}} {{saldo}} "
                + "{{vencimiento}} {{moneda}} {{negocio}} {{resumen}} {{enlace}}</p>";
        Map<String, Object> payload = new HashMap<>();
        payload.put("nombre", "QA preview placeholders");
        payload.put("asunto", "{{tipoComprobante}} {{consecutivo}}");
        payload.put("cuerpoHtml", cuerpo);
        payload.put("tipo", "COMPROBANTES");
        payload.put("status", true);

        Response created = given().cookies(cookies)
                .header("X-CSRF-TOKEN", csrf)
                .contentType(ContentType.JSON)
                .body(payload)
                .when().post(BASE)
                .then().statusCode(201)
                .extract().response();
        int id = created.jsonPath().getInt("data.id");

        String html = given().cookies(cookies)
                .when().get(BASE + "/" + id + "/preview")
                .then().statusCode(200)
                .extract().asString();

        assertThat(html).doesNotContain("{{tipoComprobante}}")
                .doesNotContain("{{estado}}")
                .doesNotContain("{{referencia}}")
                .doesNotContain("{{motivo}}")
                .doesNotContain("{{saldo}}")
                .doesNotContain("{{vencimiento}}")
                .doesNotContain("{{moneda}}")
                .doesNotContain("{{negocio}}")
                .doesNotContain("{{resumen}}");
        assertThat(html).contains("Factura electrónica")
                .contains("ACEPTADO")
                .contains("Devolución de mercancía")
                .contains("CRC");

        given().cookies(cookies)
                .header("X-CSRF-TOKEN", csrf)
                .when().delete(BASE + "/" + id)
                .then().statusCode(200);
    }
}
