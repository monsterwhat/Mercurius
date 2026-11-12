package Controllers.Api.App;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasKey;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import Models.ImportacionMasivaEntidad;
import Services.ImportacionMasivaService;
import org.junit.jupiter.api.Test;

/**
 * HTTP surface of the bulk import (Importacion Masiva): schema, templates and
 * the multipart upload. The row-level parser/validator is covered by
 * {@link Services.ImportacionMasivaServiceTest}; here we prove the endpoint
 * wiring, auth/CSRF path and response envelope.
 */
@QuarkusTest
class ImportacionMasivaResourceTest extends support.ContextPathIsolation {

    private static final String BASE = "";
    private static final String API = BASE + "/api/app/importacion-masiva";
    private static final String CSRF_COOKIE = "csrf-token";
    private static final String CSRF_HEADER = "X-CSRF-TOKEN";

    @Inject
    ImportacionMasivaService importacionMasivaService;

    private static Map<String, String> adminSession() {
        Response loginPage = given().redirects().follow(false)
                .when().get(BASE + "/login");
        loginPage.then().statusCode(200);
        Map<String, String> cookies = new HashMap<>(loginPage.getCookies());

        Response login = given().redirects().follow(false)
                .cookies(cookies)
                .contentType(ContentType.URLENC)
                .formParam("j_username", "admin")
                .formParam("j_password", "admin123")
                .when().post(BASE + "/j_security_check");
        login.then().statusCode(302);
        cookies.putAll(login.getCookies());
        return cookies;
    }

    private static RequestSpecification authed(Map<String, String> cookies) {
        RequestSpecification spec = given().redirects().follow(false).cookies(cookies);
        String token = cookies.get(CSRF_COOKIE);
        if (token != null) {
            spec.header(CSRF_HEADER, token);
        }
        return spec;
    }

    @Test
    void esquemaDevuelveLasCuatroEntidades() {
        Map<String, String> session = adminSession();
        authed(session)
                .when().get(API + "/esquema")
                .then()
                .statusCode(200)
                .body("data", hasKey("clientes"))
                .body("data", hasKey("articulos"))
                .body("data", hasKey("cabys"))
                .body("data", hasKey("precios"));
    }

    @Test
    void plantillaCsvDescargaAttachment() {
        Map<String, String> session = adminSession();
        authed(session)
                .queryParam("entidad", "clientes")
                .queryParam("formato", "csv")
                .when().get(API + "/plantilla")
                .then()
                .statusCode(200)
                .header("Content-Disposition", containsString("attachment"))
                .header("Content-Type", containsString("text/csv"))
                .body(containsString("Nombre"));
    }

    @Test
    void importacionCsvEnSimulacionNoPersiste() {
        Map<String, String> session = adminSession();
        String csv = importacionMasivaService
                .plantillaCsv(ImportacionMasivaEntidad.CLIENTES)
                .replace(" *", "");

        authed(session)
                .multiPart("entidad", "clientes")
                .multiPart("simulacion", "true")
                .multiPart("archivo", "clientes.csv",
                        csv.getBytes(StandardCharsets.UTF_8), "text/csv")
                .when().post(API)
                .then()
                .statusCode(200)
                .body("data.entidad", equalTo("clientes"))
                .body("data.simulacion", equalTo(true))
                .body("data.totalFilas", greaterThanOrEqualTo(1));
    }

    @Test
    void entidadDesconocidaResponde400() {
        Map<String, String> session = adminSession();
        authed(session)
                .multiPart("entidad", "inexistente")
                .multiPart("simulacion", "true")
                .multiPart("archivo", "x.csv",
                        "a;b".getBytes(StandardCharsets.UTF_8), "text/csv")
                .when().post(API)
                .then()
                .statusCode(400)
                .body("error.message", containsString("entidad"));
    }
}
