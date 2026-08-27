package Controllers.AppAuth;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.isOneOf;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T15 — declarative role matrix over REAL endpoints (no temporary probe
 * resources). {@code @TestSecurity} bypasses form authentication entirely and
 * injects the identity directly, so each row isolates the AUTHORIZATION layer
 * ({@code @RolesAllowed}) from the authentication machinery.
 *
 * <p><b>Matrix (class-level gates verified in source):</b></p>
 *
 * <pre>
 * Endpoint (GET)              @RolesAllowed            Second role   Wrong-role probe
 * /api/app/cabys              {admin, inventario}      inventario    facturacion
 * /api/app/users              {admin, usuario}         usuario       inventario
 * /api/app/settings           {admin}                  —             usuario (+all others)
 * /api/app/loyalty/top        {admin, facturacion}     facturacion   tributacion
 * </pre>
 *
 * <p>Loyalty leg note: {@code LoyaltyResource} exposes NO bare GET at
 * {@code /api/app/loyalty}; its read surface starts at sub-paths, so the
 * matrix uses {@code GET /api/app/loyalty/top} — the class-level
 * {@code @RolesAllowed({"admin","facturacion"})} gate applies identically to
 * every method of that resource.</p>
 *
 * <p><b>Admin-sees-all proof:</b> an identity carrying ONLY {@code ["admin"]}
 * reaches all four endpoints because {@code RolesAllowedHttpSecurityPolicy}
 * requires a non-empty intersection between allowed and granted roles —
 * mirroring the legacy {@code groupName.contains(token) || isAdmin()} parity
 * contract of {@code Services.auth.UserRoleMapper}.</p>
 *
 * <p>curl-equivalent transcript (with a real admin cookie instead of
 * &#64;TestSecurity):</p>
 *
 * <pre>
 * curl -s -o /dev/null -w "%{http_code}\n" -b jar "$BASE/api/app/cabys"       # 200
 * curl -s -o /dev/null -w "%{http_code}\n" -b jar "$BASE/api/app/users"       # 200
 * curl -s -o /dev/null -w "%{http_code}\n" -b jar "$BASE/api/app/settings"    # 200
 * curl -s -o /dev/null -w "%{http_code}\n" -b jar "$BASE/api/app/loyalty/top" # 200
 * </pre>
 */
@QuarkusTest
@Tag("auth-matrix")
class RoleMatrixTest extends support.ContextPathIsolation {

    private static final String BASE = "/Mercurius";

    // ── admin-sees-all expansion proof ──────────────────────────────────

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void adminOnlyRoleReachesAllFourGatedEndpoints() {
        given().when().get(BASE + "/api/app/cabys")
                .then().statusCode(200).contentType(ContentType.JSON);
        given().when().get(BASE + "/api/app/users")
                .then().statusCode(200).contentType(ContentType.JSON);
        // Settings GET never creates rows (SettingsResource.current javadoc):
        // with no appsettings row seeded, the AUTHORIZED request surfaces the
        // data condition 404 instead of 200. Both prove the gate passed —
        // only a wrong role yields 403.
        given().when().get(BASE + "/api/app/settings")
                .then().statusCode(isOneOf(200, 404)).contentType(ContentType.JSON);
        given().when().get(BASE + "/api/app/loyalty/top")
                .then().statusCode(200).contentType(ContentType.JSON);
    }

    // ── second authorized role per endpoint ─────────────────────────────

    @Test
    @TestSecurity(user = "bodeguero", roles = {"inventario"})
    void inventarioRoleReachesCabys() {
        given().when().get(BASE + "/api/app/cabys")
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .body("total", notNullValue());
    }

    @Test
    @TestSecurity(user = "digitador", roles = {"usuario"})
    void usuarioRoleReachesUsers() {
        given().when().get(BASE + "/api/app/users")
                .then().statusCode(200)
                .contentType(ContentType.JSON)
                .body("total", notNullValue());
    }

    @Test
    @TestSecurity(user = "cajero", roles = {"facturacion"})
    void facturacionRoleReachesLoyaltyTop() {
        given().when().get(BASE + "/api/app/loyalty/top")
                .then().statusCode(200)
                .contentType(ContentType.JSON);
    }

    // ── wrong-role denials ──────────────────────────────────────────────

    @Test
    @TestSecurity(user = "cajero", roles = {"facturacion"})
    void facturacionRoleIsDeniedCabys() {
        given().when().get(BASE + "/api/app/cabys").then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "bodeguero", roles = {"inventario"})
    void inventarioRoleIsDeniedUsers() {
        given().when().get(BASE + "/api/app/users").then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "tributario", roles = {"tributacion"})
    void tributacionOnlyIdentityIsBlockedFromInventarioSurface() {
        // Plan T15 wording: "tributacion-only blocked from inventarios probe".
        given().when().get(BASE + "/api/app/cabys").then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "digitador", roles = {"usuario"})
    void usuarioRoleIsDeniedSettings() {
        given().when().get(BASE + "/api/app/settings").then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "archivista", roles = {"registro"})
    void registroRoleIsDeniedLoyaltyTop() {
        given().when().get(BASE + "/api/app/loyalty/top").then().statusCode(403);
    }

    @Test
    void anonymousRequestIsChallengedBeforeRolesAreEvaluated() {
        // No @TestSecurity: the /api/app/* authenticated permission policy
        // challenges (302 to the form login page) before any @RolesAllowed
        // check could even produce a 403.
        given().redirects().follow(false)
                .when().get(BASE + "/api/app/cabys")
                .then()
                .statusCode(302)
                .header("Location", containsString("/Mercurius/login"));
    }
}
