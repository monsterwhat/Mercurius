package Controllers.Api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import Services.JwtTokenUtil;
import Utils.IntentosDeCredencial;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 429 contract of the two marketplace login endpoints:
 *
 * <ul>
 *   <li>{@code POST /api/v1/mercatus/clients/auth/login}</li>
 *   <li>{@code POST /api/marketplace/auth/login}</li>
 * </ul>
 *
 * <p>Both verify a BCrypt cost-12 hash for a caller-supplied email, so unthrottled
 * each was an unlimited credential-stuffing oracle. They now consult
 * {@link IntentosDeCredencial} — per account AND per source address — before the
 * verification, and answer 429 + {@code Retry-After} once the budget is gone.</p>
 *
 * <p>These tests assert the controller's half of the contract: consult the budget
 * first, then answer 429 + {@code Retry-After} once it is gone. The counter is
 * primed directly through the limiter instead of by sending {@code max + 1} real
 * logins, which keeps the suite at zero BCrypt verifications; the accounting that
 * has to happen when a login really fails is covered directly, without HTTP, in
 * {@code Services.ClientAuthServiceThrottleTest}. Both halves matter: the 429 path
 * never touches the database, which is exactly why it stays reachable even for a
 * request that goes on to fail.</p>
 *
 * <p><b>Why the mercatus login carries a Bearer token.</b>
 * {@code PublicApiJwtFilter} classifies every {@code /api/v1/**} path as
 * AUTENTICAR (only {@code /oauth/token} is exempt), so
 * {@code /api/v1/mercatus/clients/auth/login} currently answers 401 to an
 * anonymous caller — it is unreachable without a token it is supposed to issue.
 * That is a pre-existing routing bug in the filter, out of scope for throttling;
 * the token here is minted from the injected {@link JwtTokenUtil} purely so the
 * request gets past the filter and the controller under test actually runs.</p>
 *
 * <p>The limit is NOT lowered here: {@code mercurius.auth.intentos.max=5} is the
 * value an attacker meets in production.</p>
 */
@QuarkusTest
@DisplayName("Login del marketplace: 429 con Retry-After al superar los intentos")
class ClientLoginThrottleTest {

    private static final String MERCATUS_LOGIN = "/api/v1/mercatus/clients/auth/login";
    private static final String MARKETPLACE_LOGIN = "/api/marketplace/auth/login";
    private static final String LOGIN_PAGE = "/login";
    private static final String CSRF_COOKIE = "csrf-token";
    private static final String CSRF_HEADER = "X-CSRF-TOKEN";

    /** El bloqueo se observa en el fallo max+1 ({@code restanteBloqueo}: n > 5). */
    private static final int FALLOS_PARA_BLOQUEAR = 6;

    /** Direccion hipotetica del atacante; la clave de cuenta es la que manda. */
    private static final String DIRECCION_DE_ORIGEN = "10.0.0.1";

    /** Codigo de cliente inexistente: {@code PublicApiJwtFilter} no lo consulta. */
    private static final int CODIGO_CLIENTE_FANTASMA = 999_999;

    @Inject
    IntentosDeCredencial intentosDeCredencial;

    @Inject
    JwtTokenUtil jwtTokenUtil;

    @BeforeEach
    void limpiarContadores() {
        // El limiter es @ApplicationScoped: la clave de cuenta es por correo (cada
        // prueba usa el suyo), pero la clave de DIRECCION la comparten todas las
        // peticiones del JVM. Sin esto, la primera prueba que gasta el presupuesto
        // responderia 429 a todas las demas.
        intentosDeCredencial.limpiar();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /**
     * Any safe request mints the csrf-token cookie (rest-csrf default), and a
     * JSON POST is refused 400 without a matching X-CSRF-TOKEN header:
     * {@code requireFormUrlEncoded=true} turns an {@code application/json} body
     * with no token into the filter's "wrong media type" branch. See
     * CsrfEnforcementTest for the full matrix.
     */
    private static Map<String, String> csrfJar() {
        Response mint = given().when().get(LOGIN_PAGE);
        mint.then().statusCode(200);
        return new HashMap<>(mint.getCookies());
    }

    private Response login(String ruta, Map<String, String> jar, String email, boolean conToken) {
        RequestSpecification peticion = given().redirects().follow(false)
                .cookies(jar)
                .header(CSRF_HEADER, jar.get(CSRF_COOKIE))
                .contentType(ContentType.JSON)
                .body("{\"email\":\"" + email + "\",\"password\":\"contrasena-equivocada\"}");
        if (conToken) {
            peticion = peticion.header("Authorization",
                    "Bearer " + jwtTokenUtil.generateAccessToken(CODIGO_CLIENTE_FANTASMA));
        }
        return peticion.when().post(ruta);
    }

    /** Exhausta el presupuesto de la cuenta, como lo haria una oleada de intentos. */
    private String agotarPresupuesto() {
        String email = "throttle-" + UUID.randomUUID() + "@mercurius.local";
        for (int i = 0; i < FALLOS_PARA_BLOQUEAR; i++) {
            intentosDeCredencial.registrarFallo(email, DIRECCION_DE_ORIGEN);
        }
        return email;
    }

    /** Contrato compartido del 429: status, Retry-After y envelope TOO_MANY_ATTEMPTS. */
    private static void esBloqueo(Response respuesta) {
        respuesta.then()
                .statusCode(429)
                .header("Retry-After", notNullValue())
                .body("error.code", equalTo("TOO_MANY_ATTEMPTS"));

        String retryAfter = respuesta.getHeader("Retry-After");
        assertNotNull(retryAfter, "un 429 debe traer Retry-After para que el cliente retroceda");
        assertTrue(Long.parseLong(retryAfter.trim()) > 0,
                "Retry-After debe ser un numero de segundos positivo, no: " + retryAfter);
    }

    // ── escenarios ──────────────────────────────────────────────────────

    @Test
    @DisplayName("mercatus login responde 429 con Retry-After al superar los intentos")
    void loginMercatusResponde429AlSuperarElLimite() {
        esBloqueo(login(MERCATUS_LOGIN, csrfJar(), agotarPresupuesto(), true));
    }

    @Test
    @DisplayName("marketplace login responde 429 con Retry-After al superar los intentos")
    void loginMarketplaceResponde429AlSuperarElLimite() {
        esBloqueo(login(MARKETPLACE_LOGIN, csrfJar(), agotarPresupuesto(), false));
    }

    @Test
    @DisplayName("un login sin historial no recibe 429 y llega al servicio (401)")
    void loginSinHistorialLlegaAlServicio() {
        String email = "throttle-limpio-" + UUID.randomUUID() + "@mercurius.local";

        // Ni 429 ni 500: la peticion sin historial tiene que llegar al servicio y
        // responder credenciales invalidas. El 401 pin de paso que el camino
        // completo sigue vivo, no solo el atajo del 429.
        login(MARKETPLACE_LOGIN, csrfJar(), email, false)
                .then().statusCode(401);
        login(MERCATUS_LOGIN, csrfJar(), email, true)
                .then().statusCode(401);
    }

    @Test
    @DisplayName("el presupuesto es compartido: agotarlo por mercatus bloquea tambien a marketplace")
    void elPresupuestoSeComparteEntreLosDosEndpoints() {
        // Misma cuenta, dos endpoints. La clave de cuenta no distingue de donde
        // viene el intento, asi que un endpoint no puede saltarse el limite del
        // otro. Solo se agota por mercatus: marketplace queda en un intento de
        // mostrar que alli el 429 aparece igual.
        String email = agotarPresupuesto();
        Map<String, String> jar = csrfJar();
        login(MERCATUS_LOGIN, jar, email, true).then().statusCode(429);
        esBloqueo(login(MARKETPLACE_LOGIN, jar, email, false));
    }
}
