package Controllers.AppAuth;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.Header;
import io.restassured.response.Response;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T15 — real form-cookie authentication journey over RestAssured (NO browser
 * automation, hard rule). Drives the exact curl sequence documented in
 * .omo/evidence/t14/curl-journey.md against the LIVE Quarkus 3.36.2 stack:
 *
 * <pre>
 * curl-equivalent transcript (BASE = http://localhost:8081):
 *   # challenge (secured page = endpoint with security annotations)
 *   curl -s -D - -o /dev/null "$BASE/app/dashboard"
 *     -> 302, Location: .../Mercurius/login,
 *        Set-Cookie: quarkus-redirect-location=<original absolute URI>
 *   # login page (public)
 *   curl -s "$BASE/login"                       -> 200, j_security_check form
 *   # fresh login (no prior jar -> landing page)
 *   curl -s -D - -o /dev/null -c jar -d "j_username=admin" -d "j_password=admin123" \
 *        "$BASE/j_security_check"
 *     -> 302, Location: .../Mercurius/app,
 *        Set-Cookie: quarkus-credential=<encrypted>; Path=/; HttpOnly; SameSite=Strict
 *   # authenticated probe
 *   curl -s -b jar "$BASE/api/app/auth/me"      -> 200 {"data":{"username":"admin",
 *        roles":["admin","facturacion","inventario","usuario","tributacion","registro"]}}
 *   # bad credentials
 *   curl -s -D - -o /dev/null -d "j_username=admin" -d "j_password=wrongpw" \
 *        "$BASE/j_security_check"
 *     -> 302, Location: .../Mercurius/login?error, NO quarkus-credential issued
 *   # logout (CSRF dance: X-CSRF-TOKEN must match the csrf-token cookie)
 *   curl -s -D - -o /dev/null -b jar -c jar -X POST \
 *        -H "X-CSRF-TOKEN: <csrf-token cookie value>" "$BASE/api/app/auth/logout"
 *     -> 303 See Other, Location: .../Mercurius/login,
 *        Set-Cookie: quarkus-credential=; Max-Age=0  (jar drops the session cookie)
 *   # replay after logout (jar semantics = browser semantics): the /me API
 *   # surface answers its documented 401 UNAUTHENTICATED envelope
 *   curl -s -D - -o /dev/null -b jar "$BASE/api/app/auth/me" -> 401 {"error":{...}}
 * </pre>
 *
 * <p><b>Verified mechanics this suite pins (Quarkus 3.36.2):</b></p>
 * <ul>
 *   <li>Form-auth redirects are assembled as {@code scheme://authority + raw
 *       configured value}; the configured pages carry the
 *       {@code quarkus.http.root-path=/Mercurius} prefix (T14 deviation D1),
 *       so every Location assertion below matches on the path substring.</li>
 *   <li>{@code POST /Mercurius/j_security_check} is served by the Vert.x-level
 *       FormAuthenticationMechanism route, NOT by JAX-RS — therefore the
 *       quarkus-rest-csrf request filter never runs for it and no CSRF token
 *       is needed to log in.</li>
 *   <li>The {@code quarkus-credential} token is a stateless AES-GCM encrypted identity
 *       ({@code PersistentLoginManager}); server-side there is no revocation
 *       list. "Old cookie rejected" below is asserted with REAL client-jar
 *       semantics (curl -b/-c): the logout response's {@code Set-Cookie:
 *       quarkus-credential=; Max-Age=0} removes the cookie from the jar, so the next
 *       request carries no credential and gets challenged. Manually replaying
 *       the raw stale token string would still authenticate until timeout —
 *       documented current mechanics, expected-change-in-future if server-side
 *       revocation is ever added (behavior PIN, not a bug).</li>
 *   <li>Mutating calls to JAX-RS endpoints require the CSRF double-submit
 *       token (quarkus-rest-csrf active by default since T11 added the
 *       extension; see CsrfEnforcementTest for the enforcement matrix), so the
 *       logout step sends {@code X-CSRF-TOKEN} matching the {@code csrf-token}
 *       cookie obtained from the preceding GET.</li>
 * </ul>
 */
@QuarkusTest
@Tag("auth-matrix")
class AuthJourneyTest {

    private static final String BASE = "http://localhost:8081/Mercurius";
    private static final String SESSION_COOKIE = "quarkus-credential";
    private static final String CSRF_COOKIE = "csrf-token";
    private static final String CSRF_HEADER = "X-CSRF-TOKEN";

    /** Seed credentials from src/test/resources/import-test.sql (T14). */
    private static final String SEED_USER = "admin";
    private static final String SEED_PASSWORD = "admin123";

    // ── helpers ─────────────────────────────────────────────────────────

    /**
     * Minimal curl-style cookie jar: applies every Set-Cookie header of the
     * response, honoring deletions ({@code Max-Age=0} or empty value) exactly
     * like a browser/curl {@code -b jar -c jar} round-trip would.
     */
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

    /** Raw Set-Cookie headers carrying the given cookie name (flags included). */
    private static String setCookieHeader(Response response, String cookieName) {
        return response.getHeaders().getList("Set-Cookie").stream()
                .map(Header::getValue)
                .filter(v -> v.toLowerCase(Locale.ROOT).startsWith(cookieName.toLowerCase(Locale.ROOT) + "="))
                .findFirst()
                .orElse(null);
    }

    /** Successful form login from a FRESH client (no prior challenge). */
    private static Response freshLogin() {
        return given().redirects().follow(false)
                .contentType(io.restassured.http.ContentType.URLENC)
                .formParam("j_username", SEED_USER)
                .formParam("j_password", SEED_PASSWORD)
                .when().post(BASE + "/j_security_check");
    }

    // ── scenarios ───────────────────────────────────────────────────────

    @Test
    void unauthenticatedSecuredPageChallengesToLogin() {
        // curl -s -D - -o /dev/null "$BASE/app/dashboard"
        // Secured page = endpoint carrying security annotations (lazy auth:
        // only those trigger the HTTP-level form challenge). Verified live:
        // 302 + quarkus-redirect-location cookie holding the original URL.
        given().redirects().follow(false)
                .when().get(BASE + "/app/dashboard")
                .then()
                .statusCode(302)
                .header("Location", containsString("/login"))
                .cookie("quarkus-redirect-location", notNullValue());
    }

    @Test
    void unauthenticatedMeEndpointSurfacesApi401Envelope() {
        // AppAuthResource.me() documents 401 (not a redirect) for anonymous
        // callers: unannotated REST endpoints self-check and answer with the
        // ApiResponse error envelope.
        given().redirects().follow(false)
                .when().get(BASE + "/api/app/auth/me")
                .then()
                .statusCode(401)
                .body("error.code", equalTo("UNAUTHENTICATED"));
    }

    @Test
    void loginPageIsPublicAndCarriesFormMarkers() {
        // curl -s "$BASE/login" | grep -c j_security_check
        given().redirects().follow(false)
                .when().get(BASE + "/login")
                .then()
                .statusCode(200)
                .body(containsString("action=\"j_security_check\""))
                .body(containsString("name=\"j_username\""))
                .body(containsString("name=\"j_password\""));
    }

    @Test
    void freshLoginIssuesHardenedSessionCookieAndLandsOnLandingPage() {
        Response login = freshLogin();
        login.then()
                .statusCode(302)
                .header("Location", endsWith("/Mercurius/app"));

        String session = setCookieHeader(login, SESSION_COOKIE);
        org.junit.jupiter.api.Assertions.assertNotNull(session,
                "successful login must issue the " + SESSION_COOKIE + " cookie");
        org.junit.jupiter.api.Assertions.assertTrue(
                session.toLowerCase(Locale.ROOT).contains("httponly"),
                "quarkus-credential must be HttpOnly, got: " + session);
        org.junit.jupiter.api.Assertions.assertTrue(
                session.toLowerCase(Locale.ROOT).contains("samesite=strict"),
                "quarkus-credential must be SameSite=Strict, got: " + session);
    }

    @Test
    void meReplaysSessionCookieWithPrincipalAndExpandedAdminRoles() {
        Response login = freshLogin();
        login.then().statusCode(302);
        Map<String, String> jar = new LinkedHashMap<>();
        applySetCookies(jar, login);
        org.junit.jupiter.api.Assertions.assertTrue(jar.containsKey(SESSION_COOKIE),
                "login must put quarkus-credential into the jar");

        // UserRoleMapper parity contract: groupName "admin" expands to ALL six
        // legacy role tokens (Services/auth/UserRoleMapper.java:63-71).
        given().redirects().follow(false)
                .cookies(jar)
                .when().get(BASE + "/api/app/auth/me")
                .then()
                .statusCode(200)
                .contentType(io.restassured.http.ContentType.JSON)
                .body("data.username", equalTo(SEED_USER))
                .body("data.roles", hasItems(
                        "admin", "facturacion", "inventario",
                        "usuario", "tributacion", "registro"))
                .body("error", equalTo(null));
    }

    @Test
    void challengeBounceBackReturnsToOriginallyRequestedPage() {
        Map<String, String> jar = new LinkedHashMap<>();

        // 1. anonymous hit on the secured dashboard -> challenge stores the URL
        Response challenge = given().redirects().follow(false)
                .when().get(BASE + "/app/dashboard");
        challenge.then().statusCode(302);
        applySetCookies(jar, challenge);

        // 2. visit the public login page (issues the csrf-token cookie too)
        Response loginPage = given().redirects().follow(false)
                .cookies(jar)
                .when().get(BASE + "/login");
        loginPage.then().statusCode(200);
        applySetCookies(jar, loginPage);

        // 3. login WITH the saved-location cookie -> bounce back to the dashboard
        given().redirects().follow(false)
                .cookies(jar)
                .contentType(io.restassured.http.ContentType.URLENC)
                .formParam("j_username", SEED_USER)
                .formParam("j_password", SEED_PASSWORD)
                .when().post(BASE + "/j_security_check")
                .then()
                .statusCode(302)
                .header("Location", containsString("/Mercurius/app/dashboard"));
    }

    @Test
    void badPasswordRedirectsToErrorMarkerWithoutIssuingSession() {
        Response bad = given().redirects().follow(false)
                .contentType(io.restassured.http.ContentType.URLENC)
                .formParam("j_username", SEED_USER)
                .formParam("j_password", "wrong-password")
                .when().post(BASE + "/j_security_check");

        bad.then()
                .statusCode(302)
                .header("Location", containsString("/Mercurius/login?error"));

        org.junit.jupiter.api.Assertions.assertNull(setCookieHeader(bad, SESSION_COOKIE),
                "failed login must NOT issue a quarkus-credential cookie");

        // The error-page variant renders the Qute banner (templates/login.html).
        given().redirects().follow(false)
                .queryParam("error", "1")
                .when().get(BASE + "/login")
                .then()
                .statusCode(200)
                .body(containsString("Usuario o contraseña incorrectos"));
    }

    @Test
    void fullJourneyLogoutClearsJarAndNextMeIsChallenged() {
        // -- login ---------------------------------------------------------
        Response login = freshLogin();
        login.then().statusCode(302);
        Map<String, String> jar = new LinkedHashMap<>();
        applySetCookies(jar, login);

        // -- authenticated probe; its response also issues csrf-token -------
        Response me = given().redirects().follow(false)
                .cookies(jar)
                .when().get(BASE + "/api/app/auth/me");
        me.then().statusCode(200);
        applySetCookies(jar, me);
        org.junit.jupiter.api.Assertions.assertNotNull(jar.get(CSRF_COOKIE),
                "a GET reaching a JAX-RS resource must issue the csrf-token cookie "
                        + "(quarkus-rest-csrf create-token default)");

        // -- logout with the CSRF double-submit header ----------------------
        Response logout = given().redirects().follow(false)
                .cookies(jar)
                .contentType(io.restassured.http.ContentType.URLENC)
                .header(CSRF_HEADER, jar.get(CSRF_COOKIE))
                .when().post(BASE + "/api/app/auth/logout");

        logout.then()
                .statusCode(303)
                .header("Location", containsString("/login"));

        String cleared = setCookieHeader(logout, SESSION_COOKIE);
        org.junit.jupiter.api.Assertions.assertNotNull(cleared,
                "logout must clear quarkus-credential via Set-Cookie");
        org.junit.jupiter.api.Assertions.assertTrue(
                cleared.toLowerCase(Locale.ROOT).contains("max-age=0"),
                "logout Set-Cookie must expire quarkus-credential (Max-Age=0), got: " + cleared);

        // -- jar semantics: the expired cookie leaves the jar ----------------
        applySetCookies(jar, logout);
        org.junit.jupiter.api.Assertions.assertFalse(jar.containsKey(SESSION_COOKIE),
                "browser/curl jar must drop quarkus-credential after logout");

        // -- replay: no credential left -> the API surface answers its
        // documented 401 envelope (no user data without a session) -----------
        given().redirects().follow(false)
                .cookies(jar)
                .when().get(BASE + "/api/app/auth/me")
                .then()
                .statusCode(401)
                .body("error.code", equalTo("UNAUTHENTICATED"));
    }

    @Test
    void anonymousLogoutIsHarmlessNoOpRedirect() {
        Map<String, String> jar = new LinkedHashMap<>();
        Response loginPage = given().redirects().follow(false)
                .when().get(BASE + "/login");
        loginPage.then().statusCode(200);
        applySetCookies(jar, loginPage);
        var req = given().redirects().follow(false)
                .cookies(jar)
                .contentType(io.restassured.http.ContentType.URLENC);
        String csrf = jar.get(CSRF_COOKIE);
        if (csrf != null) {
            req.header(CSRF_HEADER, csrf);
        }
        req.when().post(BASE + "/api/app/auth/logout")
                .then()
                .statusCode(303)
                .header("Location", containsString("/login"));
    }

    @Test
    void wrongPasswordSessionCookieNeverGrantsAccess() {
        Response bad = given().redirects().follow(false)
                .contentType(io.restassured.http.ContentType.URLENC)
                .formParam("j_username", SEED_USER)
                .formParam("j_password", "still-wrong")
                .when().post(BASE + "/j_security_check");
        bad.then().statusCode(302);

        Map<String, String> jar = new LinkedHashMap<>();
        applySetCookies(jar, bad);
        org.junit.jupiter.api.Assertions.assertFalse(jar.containsKey(SESSION_COOKIE),
                "no session may exist after a failed login");

        given().redirects().follow(false)
                .cookies(jar)
                .when().get(BASE + "/api/app/auth/me")
                .then()
                .statusCode(401)
                .body("error.code", equalTo("UNAUTHENTICATED"));
    }
}
