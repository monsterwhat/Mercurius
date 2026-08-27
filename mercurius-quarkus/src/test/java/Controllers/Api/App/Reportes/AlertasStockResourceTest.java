package Controllers.Api.App.Reportes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.ws.rs.core.HttpHeaders;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/**
 * T19 smoke suite for {@link AlertasStockResource} (Alertas de Stock,
 * read-only): page + HX fragment renders, section switch, and the export
 * button's dataset (stock-alerts/xlsx) streaming real workbook bytes —
 * the Inventario-family pipeline proof.
 */
@QuarkusTest
class AlertasStockResourceTest {

    private static final String PAGE = "/app/reportes/inventario/alertas";

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "inventario"})
    void paginaAutenticadaContieneTabla() {
        given()
                .when().get(PAGE)
                .then()
                .statusCode(200)
                .body(containsString("data-kit-table"))
                .body(containsString("reporte-alertas"))
                .body(containsString("Sin Stock"));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "inventario"})
    void seccionSugerenciasCambiaColumnas() {
        given()
                .queryParam("seccion", "sugerencias")
                .when().get(PAGE)
                .then()
                .statusCode(200)
                .body(containsString("Cantidad Sugerida"));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "inventario"})
    void fragmentoHxRequestContieneTabla() {
        given()
                .header("HX-Request", "true")
                .when().get(PAGE)
                .then()
                .statusCode(200)
                .body(containsString("<table"))
                .body(containsString("data-kit-table"));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "inventario"})
    void exportacionStreamsBytesXlsx() {
        byte[] bytes = given()
                .contentType(io.restassured.http.ContentType.URLENC)
                .formParam("dataset", "stock-alerts")
                .formParam("type", "xlsx")
                .when().post("/api/app/export")
                .then()
                .statusCode(200)
                .header(HttpHeaders.CONTENT_DISPOSITION, notNullValue())
                .extract().asByteArray();
        assertThat(bytes.length).isGreaterThan(4);
        assertThat(bytes[0]).isEqualTo((byte) 'P');
        assertThat(bytes[1]).isEqualTo((byte) 'K');
        assertThat(bytes[2]).isEqualTo((byte) 0x03);
        assertThat(bytes[3]).isEqualTo((byte) 0x04);
        assertThat(new String(bytes, 0, 2, StandardCharsets.US_ASCII)).isEqualTo("PK");
    }
}
