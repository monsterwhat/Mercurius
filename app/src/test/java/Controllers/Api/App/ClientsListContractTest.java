package Controllers.Api.App;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Pin for the JSON contract of {@code GET /api/app/clientes} (P3 §3.4 step 4).
 *
 * <p>The SQL-paging refactor of {@link ClientsResource} — {@code countByStatus()},
 * {@code pageByNameLike(...)} and {@code zoneUsage()} in {@code ClientService} —
 * moves filtering/counting/sorting out of the JVM. This test asserts the
 * default page still answers 200 with the seeded client row
 * ({@code 'Cliente Contado'}, code 1 in {@code import-test.sql}) inside the
 * {@code data} array, so the refactor cannot silently return an empty or
 * differently-shaped page. It is assertion-only on purpose: no fixtures are
 * created and no other test is touched.</p>
 */
@QuarkusTest
@Tag("w4b-views")
class ClientsListContractTest extends support.ContextPathIsolation {

    private static final String BASE = "/Mercurius";
    private static final String LIST_URL = BASE + "/api/app/clientes";
    private static final String SEEDED_CLIENT = "Cliente Contado";
    private static final String CSRF_COOKIE = "csrf-token";
    private static final String CSRF_HEADER = "X-CSRF-TOKEN";

    /** Same form-cookie login dance as {@link ClientesPageTest}. */
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
    void listReturns200AndContainsFirstSeededClient() {
        Map<String, String> session = adminSession();
        authed(session)
                .when().get(LIST_URL)
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("error", nullValue())
                .body("total", greaterThanOrEqualTo(1))
                .body("data.size()", greaterThanOrEqualTo(1))
                .body("data.name", hasItem(SEEDED_CLIENT));
    }
}
