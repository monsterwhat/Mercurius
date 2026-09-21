package Controllers.Api.App.Reportes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.CoreMatchers.containsString;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.ws.rs.core.HttpHeaders;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/**
 * T19 smoke suite for {@link VentasResource} (Reporte de Ventas por Cajero):
 * authenticated page GET renders the kit table, the HX-Request variant renders
 * only the table fragment, the wired export endpoint streams real attachment
 * bytes (Articulos-family pipeline proof), and the admin/inventario gate holds.
 */
@QuarkusTest
class VentasResourceTest {

    private static final String PAGE = "/app/reportes/articulos/ventas";

    @Test
    @TestSecurity(user = "admin", roles = {"admin", "inventario"})
    void paginaAutenticadaContieneTabla() {
        given()
                .redirects().follow(false)
                .queryParam("usuario", "1")
                .queryParam("desde", "2026-01-01")
                .queryParam("hasta", "2026-01-31")
                .when().get(PAGE)
                .then()
                .statusCode(200)
                .body(containsString("data-kit-table"))
                .body(containsString("reporte-ventas"))
                .body(containsString("Cajero"));
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
    void exportacionStreamsBytesPdf() {
        // quarkus-rest-csrf rejects token-less mutating calls with 400: mint
        // the cookie with a prior safe request, then replay header + cookie.
        io.restassured.response.Response mint = given()
                .when().get(PAGE);
        org.junit.jupiter.api.Assertions.assertNotNull(mint.getCookie("csrf-token"),
                "safe GET must mint the csrf-token cookie");
        byte[] bytes = given()
                .cookie("csrf-token", mint.getCookie("csrf-token"))
                .header("X-CSRF-TOKEN", mint.getCookie("csrf-token"))
                .contentType(io.restassured.http.ContentType.URLENC)
                .formParam("dataset", "articulos")
                .formParam("type", "pdf")
                .when().post("/api/app/export")
                .then()
                .statusCode(200)
                .header(HttpHeaders.CONTENT_DISPOSITION, org.hamcrest.CoreMatchers.notNullValue())
                .extract().asByteArray();
        assertThat(bytes.length).isGreaterThan(4);
        assertThat(new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII))
                .isEqualTo("%PDF-");
    }

    @Test
    @TestSecurity(user = "cajero", roles = {"usuario"})
    void rolSinPermisoRecibe403() {
        given()
                .redirects().follow(false)
                .when().get(PAGE)
                .then()
                .statusCode(403);
    }

    @Test
    void anonimoEsRedirigidoAlLogin() {
        given()
                .redirects().follow(false)
                .when().get(PAGE)
                .then()
                .statusCode(302);
    }
}
