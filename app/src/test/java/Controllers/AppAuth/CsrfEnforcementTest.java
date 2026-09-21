package Controllers.AppAuth;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.http.Header;
import io.restassured.response.Response;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T15 — CSRF &amp; cookie-hardening enforcement matrix.
 *
 * <p><b>Activation status (verified against the pinned stack, NOT assumed):</b>
 * {@code io.quarkus:quarkus-rest-csrf} has been a project dependency since T11
 * (pom.xml) and its build-time flag {@code quarkus.rest-csrf.enabled} defaults
 * to {@code true} (RestCsrfBuildTimeConfig, 3.36.2 jar). With ZERO
 * {@code quarkus.rest-csrf.*} keys configured, the runtime defaults from
 * {@code RestCsrfConfig} (3.36.2) are:</p>
 *
 * <pre>
 * cookieName            = csrf-token      (NOT the old "csrftoken")
 * formFieldName         = csrf-token
 * tokenHeaderName       = X-CSRF-TOKEN
 * cookieMaxAge          = 2H   (7200 s)
 * cookiePath            = /
 * cookieHttpOnly        = true
 * createTokenPath       = empty -> token issued on EVERY safe (GET/HEAD/OPTIONS)
 *                                  request that reaches a JAX-RS resource
 * verifyToken           = true
 * requireFormUrlEncoded = true  -> non-form POST bodies are rejected 400
 * </pre>
 *
 * <p>The filter ({@code CsrfRequestResponseReactiveFilter}, registered as an
 * unremovable bean with {@code @ServerRequestFilter}) therefore runs for EVERY
 * JAX-RS request: safe methods mint+set the token cookie; unsafe methods
 * require an {@code X-CSRF-TOKEN} header or a {@code csrf-token} form field
 * matching the cookie, and reject anything else with 400.</p>
 *
 * <p><b>Behavior PINs (current behavior, expected-change-in-future — not
 * bugs):</b></p>
 * <ul>
 *   <li>PIN-1: {@code POST /Mercurius/j_security_check} is served by the
 *       Vert.x-level FormAuthenticationMechanism route and NEVER reaches the
 *       JAX-RS filter chain, so login is not CSRF-gated. The login template
 *       still carries no hidden token field (its own comment says to add one
 *       "when the extension lands" — it HAS landed since); if someone wires
 *       the token into the form, this PIN must be revisited.</li>
 *   <li>PIN-2: because every GET mints a fresh token when absent, any page
 *       visit hands the browser both halves of the double-submit pair; the
 *       enforcement below asserts the SERVER side of that contract.</li>
 * </ul>
 *
 * <p>curl-equivalent transcripts per scenario are embedded in the tests.</p>
 */
@QuarkusTest
@Tag("auth-matrix")
class CsrfEnforcementTest extends support.ContextPathIsolation {

    private static final String BASE = "/Mercurius";
    private static final String SESSION_COOKIE = "quarkus-credential";
    private static final String CSRF_COOKIE = "csrf-token";
    private static final String CSRF_HEADER = "X-CSRF-TOKEN";
    private static final String CSRF_FORM_FIELD = "csrf-token";

    // ── helpers ─────────────────────────────────────────────────────────

    private static String setCookieHeader(Response response, String cookieName) {
        List<String> matches = response.getHeaders().getList("Set-Cookie").stream()
                .filter(h -> h.getValue().toLowerCase(Locale.ROOT)
                        .startsWith(cookieName.toLowerCase(Locale.ROOT) + "="))
                .map(Header::getValue)
                .toList();
        return matches.isEmpty() ? null : matches.get(0);
    }

    /** Real browser-equivalent session: quarkus-credential + csrf-token in one jar. */
    private static Map<String, String> adminSessionWithCsrfToken() {
        Response loginPage = given().redirects().follow(false)
                .when().get(BASE + "/login");
        loginPage.then().statusCode(200);
        Map<String, String> jar = new LinkedHashMap<>(loginPage.getCookies());

        Response login = given().redirects().follow(false)
                .cookies(jar)
                .contentType(ContentType.URLENC)
                .formParam("j_username", "admin")
                .formParam("j_password", "admin123")
                .when().post(BASE + "/j_security_check");
        org.junit.jupiter.api.Assertions.assertEquals(302, login.getStatusCode());
        jar.putAll(login.getCookies());

        Response me = given().redirects().follow(false)
                .cookies(jar)
                .when().get(BASE + "/api/app/auth/me");
        org.junit.jupiter.api.Assertions.assertEquals(200, me.getStatusCode());
        jar.putAll(me.getCookies());

        assertNotNull(jar.get(CSRF_COOKIE),
                "GET /me must have issued the " + CSRF_COOKIE + " cookie");
        return jar;
    }

    // ── cookie-flag hardening ───────────────────────────────────────────

    @Test
    void loginPageIssuesHttpOnlyCsrfTokenCookie() {
        // curl -s -D - -o /dev/null "$BASE/login" | grep -i set-cookie
        //   -> Set-Cookie: csrf-token=<22-char base64url>; path=/; Max-Age=7200;
        //      HttpOnly     (Secure only when the request is HTTPS)
        Response page = given().redirects().follow(false)
                .when().get(BASE + "/login");

        page.then().statusCode(200);
        String csrf = setCookieHeader(page, CSRF_COOKIE);
        assertNotNull(csrf,
                "quarkus-rest-csrf must mint the token on safe JAX-RS requests "
                        + "(createTokenPath default = all paths)");
        String lower = csrf.toLowerCase(Locale.ROOT);
        assertTrue(lower.contains("httponly"), "csrf cookie must be HttpOnly: " + csrf);
        assertTrue(lower.contains("path=/"), "csrf cookie must be Path=/: " + csrf);
        assertTrue(lower.contains("max-age=7200"),
                "csrf cookie Max-Age must be the 2H default: " + csrf);
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void securedGetAlsoIssuesTheCsrfTokenCookie() {
        // Issuance is not tied to the login page: ANY GET reaching a JAX-RS
        // resource mints the token (here: an authenticated API probe). The
        // identity is injected via @TestSecurity so the request actually
        // reaches the resource instead of being challenged.
        Response me = given()
                .when().get(BASE + "/api/app/auth/me");

        me.then().statusCode(200);
        assertNotNull(setCookieHeader(me, CSRF_COOKIE),
                "authenticated GET /me must also carry the csrf-token Set-Cookie");
    }

    @Test
    void sessionCookieCarriesHttpOnlyAndSameSiteStrictFromRealLogin() {
        // curl -s -D - -o /dev/null -d "j_username=admin" -d "j_password=admin123" \
        //      "$BASE/j_security_check"
        //   -> Set-Cookie: quarkus-credential=<encrypted>; Path=/; HttpOnly; SameSite=Strict
        Response login = given().redirects().follow(false)
                .contentType(ContentType.URLENC)
                .formParam("j_username", "admin")
                .formParam("j_password", "admin123")
                .when().post(BASE + "/j_security_check");

        login.then().statusCode(302);
        String session = setCookieHeader(login, SESSION_COOKIE);
        assertNotNull(session, "successful login must issue quarkus-credential");
        String lower = session.toLowerCase(Locale.ROOT);
        assertTrue(lower.contains("httponly"),
                "T13 config quarkus.http.auth.form.http-only-cookie=true -> HttpOnly: "
                        + session);
        assertTrue(lower.contains("samesite=strict"),
                "T13 config quarkus.http.auth.form.cookie-same-site=strict "
                        + "-> SameSite=Strict: " + session);
    }

    // ── enforcement on mutating calls (filter rejects BEFORE resource) ──

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void postWithoutAnyCsrfTokenIsRejected400() {
        // curl -X POST "$BASE/api/app/auth/logout"   (no cookies, no header)
        //   -> 400: verifyToken=true + requireFormUrlEncoded=true reject a bodyless,
        //      non-form POST before AppAuthResource.logout can run.
        //
        // BEHAVIOR PIN (expected-change-in-future if rest-csrf config ever gets
        // tuned in application.properties; today there are NO quarkus.rest-csrf.*
        // keys, so these defaults ARE the documented current behavior).
        given().redirects().follow(false)
                .when().post(BASE + "/api/app/auth/logout")
                .then()
                .statusCode(400);
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void postWithMismatchedHeaderTokenIsRejected400() {
        // curl -X POST -H "X-CSRF-TOKEN: forged" -b "csrf-token=legit" ... -> 400
        // ("CSRF token value is wrong" branch of CsrfRequestResponseReactiveFilter)
        given().redirects().follow(false)
                .cookie(CSRF_COOKIE, "legit-token-value")
                .header(CSRF_HEADER, "forged-token-value")
                .when().post(BASE + "/api/app/auth/logout")
                .then()
                .statusCode(400);
    }

    @Test
    void postWithMatchingHeaderPassesFilterAndLogsOut303() {
        Map<String, String> jar = adminSessionWithCsrfToken();

        given().redirects().follow(false)
                .cookies(jar)
                .contentType(ContentType.URLENC)
                .header(CSRF_HEADER, jar.get(CSRF_COOKIE))
                .when().post(BASE + "/api/app/auth/logout")
                .then()
                .statusCode(303)
                .header("Location", containsString("/Mercurius/login"));
    }

    @Test
    void postWithMatchingFormFieldPassesToo() {
        // Double-submit via the FORM channel instead of the header:
        // Content-Type: application/x-www-form-urlencoded + entity required
        // (a form POST without entity is also a 400), field name = csrf-token.
        Map<String, String> jar = adminSessionWithCsrfToken();

        given().redirects().follow(false)
                .cookies(jar)
                .contentType(ContentType.URLENC)
                .formParam(CSRF_FORM_FIELD, jar.get(CSRF_COOKIE))
                .when().post(BASE + "/api/app/auth/logout")
                .then()
                .statusCode(303)
                .header("Location", containsString("/Mercurius/login"));
    }

    // ── behavior PIN: j_security_check sits OUTSIDE the JAX-RS chain ────

    @Test
    void jSecurityCheckPostIsNotCsrfGatedBehaviorPin() {
        // PIN-1: bad credentials WITHOUT any prior GET / csrf cookie still get
        // the mechanism's 302 error redirect — proof that the form-auth POST
        // handler runs before/independently of the RESTEasy CSRF filter.
        // If this ever returns 400 instead, someone wired CSRF onto the login
        // form and templates/login.html + this PIN must be updated together.
        given().redirects().follow(false)
                .contentType(ContentType.URLENC)
                .formParam("j_username", "admin")
                .formParam("j_password", "definitely-wrong")
                .when().post(BASE + "/j_security_check")
                .then()
                .statusCode(302)
                .header("Location", containsString("/Mercurius/login?error"));
    }
}
