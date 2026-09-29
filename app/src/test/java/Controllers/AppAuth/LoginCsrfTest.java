package Controllers.AppAuth;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import Controllers.filters.LoginOrigenMechanism;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Login CSRF: a cross-site POST to the form-auth endpoint must not log anyone in.
 *
 * <p>Behavioral pin for {@code LoginOrigenMechanism}. {@code POST
 * /j_security_check} is served by Quarkus' form mechanism before the main
 * router and outside permission evaluation (a Vert.x {@code @RouteFilter} and a
 * custom {@code HttpSecurityPolicy} were both tried and verified never to fire
 * for it), so the authentication-mechanism decorator is the layer that gates
 * it. Without it, any site could auto-submit attacker credentials and log the
 * victim's browser into the attacker's account.</p>
 *
 * <p>The assertions pin the ATTACK OUTCOME, not a status code: the response
 * must carry no {@code quarkus-credential} session cookie, i.e. the victim's
 * browser is not logged in as the attacker. A forged login that mints no
 * session is a failed attack regardless of whether the framework answers 401
 * or 403.</p>
 */
@QuarkusTest
@DisplayName("Login CSRF: el inicio entre sitios no crea sesion")
class LoginCsrfTest extends support.ContextPathIsolation {

    private static final String BASE = "/Mercurius";
    private static final String CHECK = BASE + "/j_security_check";
    private static final String SESION = "quarkus-credential";

    private static io.restassured.specification.RequestSpecification intento() {
        return given().redirects().follow(false)
                .contentType(ContentType.URLENC)
                .formParam("j_username", "admin")
                .formParam("j_password", "admin123");
    }

    @Test
    @DisplayName("un Origin de otro sitio no inicia sesion")
    void origenCruzadoNoIniciaSesion() {
        var respuesta = intento().header("Origin", "https://evil.example")
                .when().post(CHECK)
                .then().extract();

        assertThat(respuesta.cookies())
                .as("un inicio entre sitios no debe crear sesion")
                .doesNotContainKey(SESION);
        assertThat(respuesta.statusCode())
                .as("y no debe redirigir como un inicio correcto")
                .isNotEqualTo(302);
    }

    @Test
    @DisplayName("un Referer de otro sitio no inicia sesion cuando no hay Origin")
    void refererCruzadoNoIniciaSesion() {
        var respuesta = intento().header("Referer", "https://evil.example/pagina")
                .when().post(CHECK)
                .then().extract();

        assertThat(respuesta.cookies())
                .doesNotContainKey(SESION);
        assertThat(respuesta.statusCode()).isNotEqualTo(302);
    }

    @Test
    @DisplayName("sin Origin ni Referer el inicio normal sigue funcionando")
    void sinOrigenElInicioSigueFuncionando() {
        // Non-browser clients and the test suite omit both headers; blocking
        // them would break legitimate logins. The 302 proves the request
        // reached the form mechanism instead of the filter's 403.
        intento().when().post(CHECK)
                .then().statusCode(302);
    }

    @Test
    @DisplayName("un Origin propio con puerto explicito se acepta")
    void origenPropioSeAcepta() {
        // The test server answers on localhost:8081, which is also the Host
        // the request carries, so this Origin matches and the filter lets it
        // through to the mechanism (302 either way: landing or error page).
        intento().header("Origin", "http://localhost:8081")
                .when().post(CHECK)
                .then().statusCode(302);
    }

    // ── unitarias de la comparacion de origen ─────────────────────────────

    @Test
    @DisplayName("los puertos por defecto se normalizan")
    void puertosPorDefectoSeNormalizan() {
        assertThat(LoginOrigenMechanism.mismoOrigen("https://app:443", "https://app")).isTrue();
        assertThat(LoginOrigenMechanism.mismoOrigen("http://app:80", "http://app")).isTrue();
        assertThat(LoginOrigenMechanism.mismoOrigen("http://app:8081", "http://app")).isFalse();
    }

    @Test
    @DisplayName("la comparacion no distingue mayusculas en esquema y host")
    void comparacionSinMayusculas() {
        assertThat(LoginOrigenMechanism.mismoOrigen("HTTPS://APP", "https://app")).isTrue();
    }

    @Test
    @DisplayName("un Referer aporta su origen aunque traiga ruta")
    void refererConRutaSeReduceASuOrigen() {
        assertThat(LoginOrigenMechanism.mismoOrigen(
                "https://app/pagina?x=1", "https://app")).isTrue();
        assertThat(LoginOrigenMechanism.mismoOrigen(
                "https://evil.example/", "https://app")).isFalse();
    }

    @Test
    @DisplayName("un candidato nulo o malformado nunca coincide")
    void candidatoInvalidoNoCoincide() {
        assertThat(LoginOrigenMechanism.mismoOrigen(null, "https://app")).isFalse();
        assertThat(LoginOrigenMechanism.mismoOrigen("no-es-url", "https://app")).isFalse();
    }
}
