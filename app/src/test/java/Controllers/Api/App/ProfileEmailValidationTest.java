package Controllers.Api.App;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * El formulario "Cambiar correo electrónico" del perfil debe rechazar un
 * texto que no es un correo. Antes aceptaba cualquier cadena no vacía y la
 * guardaba como email del usuario.
 */
@QuarkusTest
class ProfileEmailValidationTest extends support.ContextPathIsolation {

    private static final String BASE = "/Mercurius";
    private static final String CSRF_COOKIE = "csrf-token";
    private static final String CSRF_HEADER = "X-CSRF-TOKEN";

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
    void invalidEmailIsRejected() {
        Map<String, String> session = adminSession();
        authed(session)
                .contentType(ContentType.URLENC)
                .formParam("nuevoEmail", "no-es-correo")
                .when().put(BASE + "/api/app/perfil/email")
                .then()
                .statusCode(400)
                .body("error.code", equalTo("VALIDATION_ERROR"));
    }
}
