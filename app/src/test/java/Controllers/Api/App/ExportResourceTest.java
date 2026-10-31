package Controllers.Api.App;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.ws.rs.core.HttpHeaders;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;

/**
 * D15 — export integrity: the three datasets that used to 404
 * ({@code reportes-movimientos}, {@code inventario-resumen},
 * {@code promociones}), the tab-aware Articles export, and the sales export
 * that used to download the article catalogue.
 */
@QuarkusTest
class ExportResourceTest {

    private static final String PAGE = "/app/reportes/inventario/alertas";
    private static final String EXPORT = "/api/app/export";

    private byte[] export(String dataset, String type, Map<String, String> params) {
        String token = given()
                .when().get(PAGE)
                .then().statusCode(200)
                .extract().cookie("csrf-token");
        assertThat(token).as("csrf-token minted by the safe GET").isNotBlank();

        io.restassured.specification.RequestSpecification request = given()
                .cookie("csrf-token", token)
                .header("X-CSRF-TOKEN", token)
                .contentType(ContentType.URLENC)
                .formParam("dataset", dataset)
                .formParam("type", type);
        params.forEach(request::formParam);

        Response response = request.when().post(EXPORT);
        response.then()
                .statusCode(200)
                .header(HttpHeaders.CONTENT_DISPOSITION, notNullValue());
        return response.asByteArray();
    }

    private static void assertXlsx(byte[] bytes) {
        assertThat(bytes.length).isGreaterThan(4);
        assertThat(new String(bytes, 0, 2, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("PK");
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void promocionesDatasetStreamsWorkbook() {
        assertXlsx(export("promociones", "xlsx", Map.of()));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void movimientosDatasetStreamsWorkbook() {
        assertXlsx(export("reportes-movimientos", "xlsx", Map.of()));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void inventarioResumenDatasetStreamsWorkbook() {
        assertXlsx(export("inventario-resumen", "xlsx", Map.of("vista", "todos")));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void articulosExportHonoursTheTab() {
        assertXlsx(export("articulos", "xlsx", Map.of("tab", "inactivos")));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void ventasDatasetNoLongerDownloadsTheArticleCatalogue() {
        assertXlsx(export("reportes-ventas", "xlsx", Map.of()));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void unknownDatasetStillAnswers404() {
        String token = given().when().get(PAGE).then().statusCode(200)
                .extract().cookie("csrf-token");
        given()
                .cookie("csrf-token", token)
                .header("X-CSRF-TOKEN", token)
                .contentType(ContentType.URLENC)
                .formParam("dataset", "no-such-dataset")
                .formParam("type", "xlsx")
                .when().post(EXPORT)
                .then().statusCode(404);
    }
}
