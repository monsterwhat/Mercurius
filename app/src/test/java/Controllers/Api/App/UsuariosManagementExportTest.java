package Controllers.Api.App;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.ws.rs.core.HttpHeaders;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * D3 — the Users report was absorbed by the management screen: the admin-only
 * XLSX moved to /app/usuarios and the old report route is retired.
 */
@QuarkusTest
@Tag("gestion-pages")
class UsuariosManagementExportTest extends support.ContextPathIsolation {

    private static final String PAGE = "/Mercurius/app/usuarios";
    private static final String OLD_REPORT = "/Mercurius/app/reportes/usuarios";
    private static final String EXPORT = "/Mercurius/api/app/export";

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void adminSeesTheExportOnTheManagementScreen() {
        given().when().get(PAGE).then().statusCode(200)
                .body(containsString("dataset\" value=\"usuarios\""));
    }

    @Test
    @TestSecurity(user = "gestor", roles = {"usuario"})
    void usuarioSeesTheScreenWithoutTheAdminOnlyExport() {
        given().when().get(PAGE).then().statusCode(200)
                .body(containsString("data-kit-table"))
                .body(not(containsString("dataset\" value=\"usuarios\"")));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void oldReportRouteIsRetired() {
        given().when().get(OLD_REPORT).then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void managementExportHonoursQ() throws Exception {
        String token = given().when().get(PAGE).then().statusCode(200)
                .extract().cookie("csrf-token");
        assertThat(token).isNotBlank();

        Response response = given()
                .cookie("csrf-token", token)
                .header("X-CSRF-TOKEN", token)
                .contentType(ContentType.URLENC)
                .formParam("dataset", "usuarios")
                .formParam("type", "xlsx")
                .formParam("q", "zzz-no-such-user-zzz")
                .when().post(EXPORT);

        response.then().statusCode(200)
                .header(HttpHeaders.CONTENT_DISPOSITION, notNullValue());
        byte[] bytes = response.asByteArray();
        assertThat(new String(bytes, 0, 2, StandardCharsets.US_ASCII)).isEqualTo("PK");
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(wb.getSheetAt(0).getLastRowNum()).isZero();
        }
    }
}
