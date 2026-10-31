package Controllers.Api.App;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.ws.rs.core.HttpHeaders;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
        return exportFrom(PAGE, dataset, type, params);
    }

    private byte[] exportFrom(String mintPage, String dataset, String type, Map<String, String> params) {
        Response response = postExport(mintPage, dataset, type, params);
        response.then()
                .statusCode(200)
                .header(HttpHeaders.CONTENT_DISPOSITION, notNullValue());
        return response.asByteArray();
    }

    private Response postExport(String mintPage, String dataset, String type, Map<String, String> params) {
        String token = given()
                .when().get(mintPage)
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
        return request.when().post(EXPORT);
    }

    private void forbiddenFrom(String mintPage, String dataset, String type) {
        postExport(mintPage, dataset, type, Map.of()).then().statusCode(403);
    }

    private static void assertXlsx(byte[] bytes) {
        assertThat(bytes.length).isGreaterThan(4);
        assertThat(new String(bytes, 0, 2, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("PK");
    }

    /** Opens the first sheet of an exported workbook; caller closes it. */
    private static XSSFWorkbook workbook(byte[] bytes) throws IOException {
        return new XSSFWorkbook(new ByteArrayInputStream(bytes));
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
    @TestSecurity(user = "facturador", roles = {"facturacion"})
    void facturacionCanExportFacturas() {
        assertXlsx(exportFrom("/app/facturas", "facturas", "xlsx", Map.of()));
    }

    @Test
    @TestSecurity(user = "facturador", roles = {"facturacion"})
    void facturacionCannotExportUsuarios() {
        forbiddenFrom("/app/facturas", "usuarios", "xlsx");
    }

    @Test
    @TestSecurity(user = "inventario", roles = {"inventario"})
    void inventarioCanExportArticulos() {
        assertXlsx(exportFrom("/app/reportes/inventario/alertas", "articulos", "xlsx", Map.of()));
    }

    @Test
    @TestSecurity(user = "inventario", roles = {"inventario"})
    void inventarioCannotExportFacturas() {
        forbiddenFrom("/app/reportes/inventario/alertas", "facturas", "xlsx");
    }

    @Test
    @TestSecurity(user = "usuario", roles = {"usuario"})
    void usuarioCanExportClientes() {
        assertXlsx(exportFrom("/app/usuarios", "clientes", "xlsx", Map.of()));
    }

    @Test
    @TestSecurity(user = "usuario", roles = {"usuario"})
    void usuarioCannotExportUsuarios() {
        forbiddenFrom("/app/usuarios", "usuarios", "xlsx");
    }

    @Test
    @TestSecurity(user = "operador", roles = {"registro"})
    void registroCanExportArticulos() {
        assertXlsx(exportFrom("/app/reportes", "articulos", "xlsx", Map.of()));
    }

    @Test
    @TestSecurity(user = "operador", roles = {"registro"})
    void registroCannotExportUsuarios() {
        forbiddenFrom("/app/reportes", "usuarios", "xlsx");
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void usuariosExportHonoursQ() throws IOException {
        try (XSSFWorkbook wb = workbook(export("usuarios", "xlsx", Map.of("q", "admin")))) {
            Sheet sheet = wb.getSheetAt(0);
            // The test DB seeds "admin" and LoginService bootstraps "Admin", so
            // the assertion is on the predicate, not on a row count.
            assertThat(sheet.getLastRowNum()).isGreaterThanOrEqualTo(1);
            for (int fila = 1; fila <= sheet.getLastRowNum(); fila++) {
                assertThat(sheet.getRow(fila).getCell(0).getStringCellValue().toLowerCase())
                        .contains("admin");
            }
        }
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void usuariosExportWithUnmatchedQIsHeaderOnly() throws IOException {
        try (XSSFWorkbook wb = workbook(export("usuarios", "xlsx", Map.of("q", "zzz-no-such-user-zzz")))) {
            assertThat(wb.getSheetAt(0).getLastRowNum()).isZero();
        }
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void clientesExportHonoursQ() throws IOException {
        try (XSSFWorkbook wb = workbook(export("clientes", "xlsx", Map.of("q", "Contado")))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getLastRowNum()).isEqualTo(1);
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("Cliente Contado");
        }
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void clientesExportWithUnmatchedQIsHeaderOnly() throws IOException {
        try (XSSFWorkbook wb = workbook(export("clientes", "xlsx", Map.of("q", "zzz-no-such-client-zzz")))) {
            assertThat(wb.getSheetAt(0).getLastRowNum()).isZero();
        }
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void correosExportAcceptsQWithoutBreakingTheWorkbook() throws IOException {
        try (XSSFWorkbook wb = workbook(export("correos", "xlsx", Map.of("q", "zzz-no-such-report-zzz")))) {
            assertThat(wb.getSheetAt(0).getLastRowNum()).isZero();
        }
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
