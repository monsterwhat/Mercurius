package Controllers.Api.App;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.not;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Privilege-escalation regression: mutating another account requires {@code admin}.
 *
 * <p>Behavioral pin for the fix of the vertical escalation in
 * {@code UsersResource}. The class-level gate is
 * {@code @RolesAllowed({"admin","usuario"})}, and these methods previously
 * carried no method-level gate:</p>
 *
 * <ul>
 *   <li>{@code PUT /api/app/users/{id}/permisos} — {@code admin} is one of the
 *       grantable {@code TOKENS_VALIDOS}, so a {@code usuario} caller could set
 *       {@code groupName=admin} on any account, including its own.
 *       {@code TrustedSessionIdentityProvider} re-derives roles from the
 *       database on every request, so the promotion was effective immediately —
 *       a complete vertical escalation from the weakest role.</li>
 *   <li>{@code PUT /api/app/users/{id}} — {@code UpdateUserRequest.status} is
 *       settable, so a {@code usuario} could disable the Admin account
 *       (permanent lockout, since {@code findByUsername} filters
 *       {@code status = true}).</li>
 *   <li>{@code DELETE /api/app/users/{id}} — same, via
 *       {@code LoginService.softDelete}.</li>
 * </ul>
 *
 * <p>These assert the DENIAL (403) for the weak role and the ALLOW (non-403)
 * for {@code admin}, which is what actually pins the fix. No test needs a real
 * target row: {@code @RolesAllowed} is evaluated before the handler body runs,
 * so a non-existent id still yields 403 for {@code usuario} and reaches the
 * 404 branch for {@code admin} — both prove the gate was evaluated.</p>
 */
@QuarkusTest
@DisplayName("UsersResource: la escalada de privilegios vertical esta cerrada")
class UsersResourcePrivilegeEscalationTest extends support.ContextPathIsolation {

    private static final String BASE = "/Mercurius/api/app/users";
    private static final long ID_QUE_NO_EXISTE = 999_999L;

    // ── permiso: un 'usuario' NO puede otorgar roles ────────────────────────

    @Test
    @TestSecurity(user = "digitador", roles = {"usuario"})
    @DisplayName("usuario no puede editar permisos (ni siquiera para otorgar admin)")
    void usuarioNoPuedeEditarPermisos() {
        given().redirects().follow(false)
                .contentType(ContentType.URLENC)
                .formParam("groupName", "admin")
                .when().put(BASE + "/" + ID_QUE_NO_EXISTE + "/permisos")
                .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "digitador", roles = {"usuario"})
    @DisplayName("usuario no puede agregar permisos a su propia cuenta")
    void usuarioNoPuedeAutoPromoverse() {
        given().redirects().follow(false)
                .contentType(ContentType.URLENC)
                .formParam("groupName", "admin")
                .formParam("groupName", "usuario")
                .when().put(BASE + "/1/permisos")
                .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "digitador", roles = {"usuario"})
    @DisplayName("usuario no puede cambiar roles de OTRA cuenta")
    void usuarioNoPuedeCambiarRolesDeOtro() {
        given().redirects().follow(false)
                .contentType(ContentType.URLENC)
                .formParam("groupName", "facturacion")
                .when().put(BASE + "/1/permisos")
                .then().statusCode(403);
    }

    // ── estado de cuenta: solo admin ────────────────────────────────────────

    @Test
    @TestSecurity(user = "digitador", roles = {"usuario"})
    @DisplayName("usuario no puede desactivar cuentas ajenas")
    void usuarioNoPuedeDesactivarCuentas() {
        given().redirects().follow(false)
                .contentType(ContentType.JSON)
                .body("{\"status\": false}")
                .when().put(BASE + "/" + ID_QUE_NO_EXISTE)
                .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "digitador", roles = {"usuario"})
    @DisplayName("usuario no puede renombrar cuentas ajenas")
    void usuarioNoPuedeRenombrarCuentas() {
        given().redirects().follow(false)
                .contentType(ContentType.JSON)
                .body("{\"username\": \"secuestro\"}")
                .when().put(BASE + "/" + ID_QUE_NO_EXISTE)
                .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "digitador", roles = {"usuario"})
    @DisplayName("usuario no puede archivar cuentas ajenas")
    void usuarioNoPuedeArchivarCuentas() {
        given().redirects().follow(false)
                .when().delete(BASE + "/" + ID_QUE_NO_EXISTE)
                .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "digitador", roles = {"usuario"})
    @DisplayName("usuario no puede crear usuarios (gate previo, sin regresion)")
    void usuarioNoPuedeCrearUsuarios() {
        given().redirects().follow(false)
                .contentType(ContentType.JSON)
                .body("{\"username\":\"nuevo\",\"password\":\"clave123\",\"groupName\":\"usuario\"}")
                .when().post(BASE)
                .then().statusCode(403);
    }

    // ── admin conserva el acceso ────────────────────────────────────────────

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    @DisplayName("admin si supera el gate de permisos (no recibe 403)")
    void adminSuperaElGateDePermisos() {
        // Deliberately asserts "not 403" instead of a specific success status.
        // /{id}/permisos is @Consumes(URLENC), and Quarkus CSRF (double submit
        // cookie) rejects a form-urlencoded write with no CSRF token as 400
        // BEFORE the handler runs. So an admin with no token gets 400, not the
        // 404 this would produce if the request reached the body.
        //
        // "Not 403" is still a sound proof, and the suite demonstrates the
        // ordering it relies on: the @TestSecurity "usuario" cases above send
        // the same URLENC request with the same absent token and get 403, not
        // 400. Role evaluation therefore happens first, so 403-vs-not-403
        // isolates exactly the gate this fix adds.
        given().redirects().follow(false)
                .contentType(ContentType.URLENC)
                .formParam("groupName", "inventario")
                .when().put(BASE + "/" + ID_QUE_NO_EXISTE + "/permisos")
                .then().statusCode(not(403));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    @DisplayName("admin si supera el gate de desactivar cuentas")
    void adminSuperaElGateDeDesactivar() {
        // JSON is not CSRF-gated, so this one reaches the body and gets the
        // handler's own 404 for the non-existent id.
        given().redirects().follow(false)
                .contentType(ContentType.JSON)
                .body("{\"status\": false}")
                .when().put(BASE + "/" + ID_QUE_NO_EXISTE)
                .then().statusCode(not(403));
    }

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    @DisplayName("admin si puede archivar cuentas (404, no 403)")
    void adminSuperaElGateDeArchivar() {
        given().redirects().follow(false)
                .when().delete(BASE + "/" + ID_QUE_NO_EXISTE)
                .then().statusCode(not(403));
    }
}
