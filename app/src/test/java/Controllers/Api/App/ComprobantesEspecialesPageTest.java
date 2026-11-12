package Controllers.Api.App;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Dedicated entry screen for the special comprobantes: ND (03), FEE (05),
 * FEC (08) and REP (10). Each card opens the POS with the type preselected.
 */
@QuarkusTest
class ComprobantesEspecialesPageTest extends support.ContextPathIsolation {

    private static final String BASE = "";
    private static final String PAGE = BASE + "/app/comprobantes";

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

    @Test
    void pageRendersTheFourSpecialDocuments() {
        given().cookies(adminSession())
                .when().get(PAGE)
                .then()
                .statusCode(200)
                .body(containsString("Nota de débito (03)"))
                .body(containsString("Factura de exportación (05)"))
                .body(containsString("Factura de compra (08)"))
                .body(containsString("Recibo de pago (10)"))
                .body(containsString("tipoDocumento=03"))
                .body(containsString("tipoDocumento=05"))
                .body(containsString("tipoDocumento=08"))
                .body(containsString("tipoDocumento=10"));
    }

    @Test
    void posPreselectsTheRequestedType() {
        given().cookies(adminSession())
                .when().get(BASE + "/app/pos?tipoDocumento=08")
                .then()
                .statusCode(200)
                .body(containsString("value=\"08\" selected"));
    }

    @Test
    void posDefaultsToTiquete() {
        given().cookies(adminSession())
                .when().get(BASE + "/app/pos")
                .then()
                .statusCode(200)
                .body(containsString("value=\"04\" selected"));
    }
}
