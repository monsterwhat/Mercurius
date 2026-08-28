package Controllers;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.Header;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import support.AppBase;

/**
 * Pins the GET /app routing contract: the bare application root route
 * (RootRedirectResource.app()) MUST dispatch — anonymous callers are bounced
 * to the form login (302) or straight to the dashboard (303) depending on the
 * {@code secured.paths=/app/*} permission match, authenticated callers land on
 * {@code /app/dashboard} (303). A 404 here means the route never reached the
 * live router, which is exactly the reported production symptom
 * ({@code /Mercurius/app} shows "not found" after login).
 *
 * <p>Written as a fresh-deployment probe: a {@code @QuarkusTest} boots a clean
 * application model (no dev-mode not-found listing, no long-lived router), so
 * it distinguishes "dev-server router corruption" from a deterministic
 * deployment-time routing bug.
 */
@QuarkusTest
@Tag("auth-matrix")
class RootRedirectAppRouteTest extends AppBase {

    /** Seed credentials from src/test/resources/import-test.sql (T14). */
    private static final String SEED_USER = "admin";
    private static final String SEED_PASSWORD = "admin123";
    private static final String SESSION_COOKIE = "quarkus-credential";

    // ── helpers (mirror Controllers/AppAuth/AuthJourneyTest) ─────────────

    /** Successful form login from a FRESH client (no prior challenge). */
    private static Response freshLogin() {
        return given().redirects().follow(false)
                .contentType(io.restassured.http.ContentType.URLENC)
                .formParam("j_username", SEED_USER)
                .formParam("j_password", SEED_PASSWORD)
                .when().post("/j_security_check");
    }

    /** Minimal jar: applies every Set-Cookie, honoring Max-Age=0 deletions. */
    private static void applySetCookies(Map<String, String> jar, Response response) {
        List<Header> setCookies = response.getHeaders().getList("Set-Cookie");
        for (Header header : setCookies) {
            String raw = header.getValue();
            String pair = raw.split(";", 2)[0].trim();
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            boolean cleared = value.isEmpty()
                    || raw.toLowerCase(Locale.ROOT).contains("max-age=0");
            if (cleared) {
                jar.remove(name);
            } else {
                jar.put(name, value);
            }
        }
    }

    // ── scenarios ─────────────────────────────────────────────────────────

    @Test
    void anonymousAppRouteNever404s() {
        // Either the permission matcher covers bare /app -> form challenge
        // (302 /login), or the route short-circuits to the dashboard (303).
        // ANY other status (404!) is the reported routing bug.
        given().redirects().follow(false)
                .when().get("/app")
                .then()
                .statusCode(anyOf(is(302), is(303)))
                .header("Location", anyOf(containsString("/login"),
                        containsString("/app/dashboard")));
    }

    @Test
    void authenticatedAppRouteRedirectsToDashboard() {
        Response login = freshLogin();
        login.then().statusCode(302);
        Map<String, String> jar = new LinkedHashMap<>();
        applySetCookies(jar, login);
        org.junit.jupiter.api.Assertions.assertTrue(jar.containsKey(SESSION_COOKIE),
                "login must put quarkus-credential into the jar");

        given().redirects().follow(false)
                .cookies(jar)
                .when().get("/app")
                .then()
                .statusCode(303)
                .header("Location", containsString("/app/dashboard"));
    }

    @Test
    void siblingRootRoutesStillDispatch() {
        given().redirects().follow(false)
                .when().get("/")
                .then()
                .statusCode(303)
                .header("Location", containsString("/app/dashboard"));

        given().redirects().follow(false)
                .when().get("/index.xhtml")
                .then()
                .statusCode(303)
                .header("Location", containsString("/app/dashboard"));
    }

    @Test
    void anonymousUnknownPageRedirectsToLogin() {
        given().redirects().follow(false)
                .when().get("/xyzzy-notfound-123")
                .then()
                .statusCode(anyOf(is(302), is(303)))
                .header("Location", containsString("/login"));
    }

    @Test
    void authenticatedUnknownPageRedirectsToApp() {
        Response login = freshLogin();
        login.then().statusCode(302);
        Map<String, String> jar = new LinkedHashMap<>();
        applySetCookies(jar, login);

        given().redirects().follow(false)
                .cookies(jar)
                .when().get("/xyzzy-notfound-123")
                .then()
                .statusCode(anyOf(is(302), is(303)))
                .header("Location", containsString("/app"));
    }

    @Test
    void apiUnknownReturnsJson404() {
        given().redirects().follow(false)
                .when().get("/api/unknown-notfound-xyz")
                .then()
                .statusCode(404)
                .contentType(containsString("application/json"));
    }

    @Test
    void authenticatedApiUnknownReturnsJson404() {
        Response login = freshLogin();
        login.then().statusCode(302);
        Map<String, String> jar = new LinkedHashMap<>();
        applySetCookies(jar, login);

        given().redirects().follow(false)
                .cookies(jar)
                .when().get("/api/unknown-notfound-xyz")
                .then()
                .statusCode(404)
                .contentType(containsString("application/json"));
    }
}