package Controllers.Api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import Models.Clientes;
import Services.ClientAuthService;
import Services.ClientService;
import Services.JwtTokenUtil;
import Utils.IntentosDeCredencial;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La superficie de mercado ({@code /api/marketplace} y
 * {@code /api/v1/mercatus/clients}) responde los errores en el MISMO envelope
 * {@link Models.DTO.ApiResponse} —{@code {"data":null,"error":{"code","message",
 * "details"}}}— sin excepcion: los 401 del filtro, los 400/404/500 de
 * registro, refresh y perfil, y el rechazo de refresh de una cuenta
 * desactivada.
 *
 * <p><b>Por que importa.</b> Los 401 de login ya venían unificados, pero el
 * resto de la superficie seguia emitiendo {@code {"error":"..."}} plano: un
 * cliente de mercado tenia que mantener un lector para el rechazo del token del
 * filtro, otro para el 404 de perfil y otro para el 400 de refresh, y todos
 * tenian el motivo en una cadena en vez de en un campo. Estas pruebas fijan el
 * contrato cerrado: TODOS esos cuerpos tienen las mismas dos claves de primer
 * nivel y {@code error} es un objeto, no una cadena.</p>
 *
 * <p><b>Por que el codigo, y no el mensaje.</b> El codigo es lo que el cliente
 * puede ramificar sin depender de un texto: {@code UNAUTHORIZED} (no se
 * presento credencial), {@code INVALID_TOKEN} (se presento un token y fue
 * rechazado), {@code VALIDATION_ERROR}, {@code NOT_FOUND},
 * {@code INTERNAL_ERROR}. Los mensajes se comparan contra la cadena exacta solo
 * donde esa cadena ES el contrato (el motivo de una validacion, el motivo de un
 * token rechazado).</p>
 *
 * <p><b>Por que el 401 de refresh de una cuenta desactivada dice "Credenciales
 * inválidas".</b> Ese camino solo es alcanzable con un refresh token VALIDO, asi
 * que no filtra nada por el cuerpo —no es un oraculo de enumeracion— pero si
 * obligaba a mantener dos textos para "tu sesion no sirvio". Ahora dice lo mismo
 * que las cuatro ramas de {@code login} y lo dice por la misma constante
 * ({@link ClientAuthService#MENSAJE_CREDENCIALES_INVALIDAS}); el operador sigue
 * viendo el motivo real en el log.</p>
 *
 * <p><b>Aislamiento.</b> Las filas se COMITEAN (la peticion HTTP corre en otra
 * transaccion y jamas veria un fixture sin commit) y se borran siempre en un
 * finally con EntityManager+UserTransaction. Los correos llevan UUID para no
 * chocar ni con {@code import-test.sql} ni entre corridas. El limiter es
 * {@code @ApplicationScoped} y su clave de DIRECCION la comparten todas las
 * peticiones del JVM, asi que se limpia en cada prueba.</p>
 *
 * <p><b>Lo que NO se fija aqui.</b> El status que devuelve {@code /me} a un
 * llamante anonimo: depende de como el contenedor materialice el
 * {@code SecurityContext} vacio, y ese comportamiento es preexistente y ajeno a
 * este cambio. Lo que si se fija es que, sea cual sea, salga en el envelope.</p>
 */
@QuarkusTest
@DisplayName("Mercado: un solo envelope de error en el filtro y en los endpoints de auth")
class MarketplaceErrorEnvelopeTest {

    private static final String MARKETPLACE_REGISTER = "/api/marketplace/auth/register";
    private static final String MERCATUS_REGISTER = "/api/v1/mercatus/clients/register";
    private static final String MARKETPLACE_LOGIN = "/api/marketplace/auth/login";
    private static final String MARKETPLACE_REFRESH = "/api/marketplace/auth/refresh";
    private static final String MARKETPLACE_ME = "/api/marketplace/auth/me";
    /** Recurso real bajo /api/marketplace/: el filtro actua DESPUES del
     *  emparejamiento, asi que una ruta inexistente daria 404 sin pasar por el. */
    private static final String MARKETPLACE_PRODUCTS = "/api/marketplace/products";

    private static final String LOGIN_PAGE = "/login";
    private static final String CSRF_COOKIE = "csrf-token";
    private static final String CSRF_HEADER = "X-CSRF-TOKEN";

    /** El filtro no consulta la base, asi que este codigo vale como token valido. */
    private static final int CODIGO_CLIENTE_FANTASMA = 999_999;

    @Inject
    ClientService clientService;

    @Inject
    IntentosDeCredencial intentosDeCredencial;

    @Inject
    JwtTokenUtil jwtTokenUtil;

    @Inject
    EntityManager em;

    @Inject
    UserTransaction utx;

    @BeforeEach
    void limpiarContadores() {
        // La clave de DIRECCION la comparten todas las peticiones del JVM: sin
        // esto, una prueba que gaste el presupuesto responderia 429 a las demas.
        intentosDeCredencial.limpiar();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static String correoUnico() {
        return "envelope-" + UUID.randomUUID() + "@mercurius.local";
    }

    /**
     * Any safe request mints the csrf-token cookie (rest-csrf default), and a
     * JSON POST is refused 400 without a matching X-CSRF-TOKEN header. Ver
     * {@code CsrfEnforcementTest} para la matriz completa.
     */
    private static Map<String, String> csrfJar() {
        Response mint = given().when().get(LOGIN_PAGE);
        mint.then().statusCode(200);
        return new HashMap<>(mint.getCookies());
    }

    private static Response post(String ruta, Map<String, String> jar, String cuerpo) {
        return given().redirects().follow(false)
                .cookies(jar)
                .header(CSRF_HEADER, jar.get(CSRF_COOKIE))
                .contentType(ContentType.JSON)
                .body(cuerpo)
                .when().post(ruta);
    }

    private static Response getConToken(String ruta, String token) {
        RequestSpecification peticion = given().redirects().follow(false);
        if (token != null) {
            peticion = peticion.header("Authorization", "Bearer " + token);
        }
        return peticion.when().get(ruta);
    }

    /**
     * El contrato de FORMA, comun a todos los errores de la superficie: dos
     * claves de primer nivel, {@code data} nulo y {@code error} como objeto con
     * las tres piezas. Que {@code error} sea un objeto es justamente lo que
     * distingue este envelope del {@code {"error":"..."}} plano: si alguien
     * reintrodujera la cadena, esta asercion falla.
     */
    private static void esEnvelope(Response respuesta) {
        String cuerpo = respuesta.getBody().asString();

        assertThat(respuesta.getContentType())
                .as("un envelope JSON debe declararlo, aunque lo emita un filtro: " + cuerpo)
                .contains("application/json");

        Map<String, Object> raiz = new JsonPath(cuerpo).getMap("$");
        assertThat(raiz)
                .as("el cuerpo debe ser un objeto JSON con data y error: " + cuerpo)
                .isNotNull();
        assertThat(raiz.keySet())
                .as("las claves de primer nivel del envelope no pueden cambiar: " + cuerpo)
                .containsExactlyInAnyOrder("data", "error");

        Object data = new JsonPath(cuerpo).get("data");
        assertThat(data).as("un rechazo no lleva payload de exito: " + cuerpo).isNull();

        Map<String, Object> error = new JsonPath(cuerpo).getMap("error");
        assertThat(error)
                .as("error debe ser un objeto {code,message,details}, no el string del envelope "
                        + "plano anterior: " + cuerpo)
                .containsKeys("code", "message", "details");

        List<Object> detalles = new JsonPath(cuerpo).getList("error.details");
        assertThat(detalles)
                .as("details viaja siempre presente (vacio cuando no hay detalle por campo): " + cuerpo)
                .isEmpty();
    }

    /**
     * Filas sembradas y comprometidas, borradas SIEMPRE en un finally: la
     * peticion HTTP corre en otra transaccion y jamas veria un fixture sin
     * commit, y una prueba roja no puede dejar filas huerfanas en
     * {@code mercurius_test}.
     */
    private final class Fixtures implements AutoCloseable {

        private final List<Integer> codigos = new ArrayList<>();

        /**
         * Cliente con todos los NOT NULL de {@code Clientes}.
         *
         * <p>Sin password de mercado a proposito: ni el registro duplicado ni el
         * refresh necesitan verificar una contrasena, y sembrarla costaria un
         * BCrypt cost-12 por prueba sin anadir cobertura.</p>
         *
         * @param refreshBruto token de actualizacion crudo a sembrar (su SHA-256
         *        es lo que se guarda), o {@code null} para no sembrar ninguno
         */
        int sembrar(String email, Boolean estado, String refreshBruto, Date expiracion) {
            Clientes cliente = new Clientes();
            cliente.setName("IT Envelope " + UUID.randomUUID().toString().substring(0, 8));
            cliente.setAddress("Barrio IT, San Jose");
            cliente.setProvincia("1");
            cliente.setEmail(email);
            cliente.setBirthDate(Date.from(
                    LocalDate.of(1995, 3, 15).atStartOfDay(ZoneId.systemDefault()).toInstant()));
            cliente.setIdType("Cedula Fisica");
            cliente.setIdNumber("IT-" + UUID.randomUUID().toString().substring(0, 8));
            cliente.setDiscount(0.0);
            cliente.setPhoneNumber("8888-0000");
            cliente.setTaxpayer(true);
            cliente.setZoneCode(1);
            cliente.setTipoIdentificacion("01");
            cliente.setStatus(estado);
            cliente.setPassword(null);
            if (refreshBruto != null) {
                cliente.setRefreshToken(sha256Hex(refreshBruto));
                cliente.setTokenExpiry(expiracion);
            }
            clientService.create(cliente);
            // ClientService.create se traga la PersistenceException: sin este guardia
            // una fixture que no entrara devolveria 0 y la prueba seguiria contando
            // una situacion que no es la que dice estar probando.
            assertThat(cliente.getCode())
                    .as("la fixture debio insertarse con un code asignado por IDENTITY")
                    .isGreaterThan(0);
            codigos.add(cliente.getCode());
            return cliente.getCode();
        }

        @Override
        public void close() {
            try {
                utx.begin();
                for (Integer codigo : codigos) {
                    Clientes cliente = em.find(Clientes.class, codigo);
                    if (cliente != null) {
                        em.remove(cliente);
                    }
                }
                utx.commit();
            } catch (Exception e) {
                try {
                    utx.rollback();
                } catch (Exception rollback) {
                    rollback.printStackTrace(); // limpieza best-effort; el fallo primario ya se reporto
                }
                throw new IllegalStateException("Limpieza de fixtures fallida", e);
            }
        }
    }

    /**
     * El mismo digest que guarda {@code ClientAuthService.buildAuthResponse}: solo
     * el SHA-256 del token crudo se persiste, de modo que sembrar un refresh
     * token utilizable exige calcularlo aqui. Se replica en vez de llamar al bean
     * porque el metodo es package-private de {@code Services}.
     */
    private static String sha256Hex(String valor) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(valor.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible en esta JVM", e);
        }
    }

    /** Top-level keys del envelope, para comparar la FORMA entre endpoints. */
    private static Set<String> clavesRaiz(String cuerpo) {
        Map<String, Object> raiz = new JsonPath(cuerpo).getMap("$");
        assertThat(raiz).as("el cuerpo debe ser un objeto JSON: " + cuerpo).isNotNull();
        return raiz.keySet();
    }

    // ── el filtro ───────────────────────────────────────────────────────

    @Test
    @DisplayName("filtro: sin cabecera Authorization responde 401 con envelope UNAUTHORIZED")
    void elFiltroSinCredencialResponde401ConEnvelope() {
        Response respuesta = getConToken(MARKETPLACE_PRODUCTS, null);

        respuesta.then()
                .statusCode(401)
                .body("error.code", equalTo("UNAUTHORIZED"))
                .body("error.message", equalTo("Token de autenticación requerido"));
        esEnvelope(respuesta);
    }

    @Test
    @DisplayName("filtro: un token rechazado responde 401 con envelope INVALID_TOKEN")
    void elFiltroConTokenInvalidoResponde401ConEnvelope() {
        Response respuesta = getConToken(MARKETPLACE_PRODUCTS, "no-es-un-jwt");

        respuesta.then()
                .statusCode(401)
                .body("error.code", equalTo("INVALID_TOKEN"))
                .body("error.message", equalTo("Token inválido o expirado"));
        esEnvelope(respuesta);
    }

    @Test
    @DisplayName("filtro: el envelope del 401 no dejo de dejar pasar un token valido")
    void unTokenValidoSiguePasandoElFiltro() {
        // La migracion es del cuerpo del rechazo: un token valido tiene que
        // seguir llegando al recurso. products devuelve 200 siempre (lista vacia
        // si no hay articulos, vacia tambien ante error), asi que un 401 o 500
        // aqui seria el filtro rompiendo el camino feliz.
        String token = jwtTokenUtil.generateAccessToken(CODIGO_CLIENTE_FANTASMA);

        getConToken(MARKETPLACE_PRODUCTS, token).then().statusCode(200);
    }

    @Test
    @DisplayName("filtro: el 401 del filtro tiene la misma forma que el 401 de login")
    void el401DelFiltroComparteLaFormaDel401DeLogin() {
        Map<String, String> jar = csrfJar();
        String delFiltro = getConToken(MARKETPLACE_PRODUCTS, null).getBody().asString();
        String deLogin = post(MARKETPLACE_LOGIN, jar,
                "{\"email\":\"" + correoUnico() + "\",\"password\":\"corta\"}")
                .getBody().asString();

        assertThat(clavesRaiz(delFiltro))
                .as("el filtro y el controlador deben ser la misma forma de envelope")
                .isEqualTo(clavesRaiz(deLogin));
    }

    // ── registro ────────────────────────────────────────────────────────

    @Test
    @DisplayName("registro: sin nombre responde 400 con envelope VALIDATION_ERROR")
    void elRegistroSinNombreResponde400ConEnvelope() {
        Response respuesta = post(MARKETPLACE_REGISTER, csrfJar(),
                "{\"email\":\"" + correoUnico() + "\",\"password\":\"clave-de-prueba\"}");

        respuesta.then()
                .statusCode(400)
                .body("error.code", equalTo("VALIDATION_ERROR"))
                .body("error.message", equalTo("El nombre es requerido"));
        esEnvelope(respuesta);
    }

    @Test
    @DisplayName("registro: el correo ya registrado da el mismo cuerpo en marketplace y mercatus")
    void elRegistroDuplicadoDaElMismoCuerpoEnLosDosEndpoints() {
        String correo = correoUnico();
        try (Fixtures fx = new Fixtures()) {
            fx.sembrar(correo, Boolean.TRUE, null, null);

            Map<String, String> jar = csrfJar();
            String cuerpo = "{\"name\":\"IT\",\"email\":\"" + correo
                    + "\",\"password\":\"clave-de-prueba\"}";
            Response marketplace = post(MARKETPLACE_REGISTER, jar, cuerpo);
            Response mercatus = post(MERCATUS_REGISTER, jar, cuerpo);

            // El status difiere (400 aqui, 409 en mercatus) y es parte de cada
            // contrato; el cuerpo no debe.
            marketplace.then()
                    .statusCode(400)
                    .body("error.code", equalTo("VALIDATION_ERROR"))
                    .body("error.message", equalTo("El correo electrónico ya está registrado"));
            mercatus.then()
                    .statusCode(409)
                    .body("error.code", equalTo("VALIDATION_ERROR"))
                    .body("error.message", equalTo("El correo electrónico ya está registrado"));
            esEnvelope(marketplace);
            esEnvelope(mercatus);
            assertThat(marketplace.getBody().asString())
                    .as("un cliente que registre por los dos endpoints no debe mantener dos cuerpos")
                    .isEqualTo(mercatus.getBody().asString());
        }
    }

    // ── refresh ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("refresh: sin refreshToken responde 400 con envelope INVALID_TOKEN")
    void elRefreshSinTokenResponde400ConEnvelope() {
        Response respuesta = post(MARKETPLACE_REFRESH, csrfJar(), "{}");

        respuesta.then()
                .statusCode(400)
                .body("error.code", equalTo("INVALID_TOKEN"))
                .body("error.message", equalTo("Token de actualización requerido"));
        esEnvelope(respuesta);
    }

    @Test
    @DisplayName("refresh: un token desconocido responde 401 con envelope INVALID_TOKEN")
    void elRefreshConTokenInexistenteResponde401ConEnvelope() {
        Response respuesta = post(MARKETPLACE_REFRESH, csrfJar(),
                "{\"refreshToken\":\"token-que-no-existe\"}");

        respuesta.then()
                .statusCode(401)
                .body("error.code", equalTo("INVALID_TOKEN"))
                .body("error.message", equalTo("Token de actualización inválido"));
        esEnvelope(respuesta);
    }

    @Test
    @DisplayName("refresh: una cuenta desactivada recibe el mensaje unico de credenciales")
    void elRefreshDeCuentaDesactivadaDiceCredencialesInvalidas() {
        String refreshBruto = "refresh-" + UUID.randomUUID();
        Date futuro = new Date(System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000);
        try (Fixtures fx = new Fixtures()) {
            fx.sembrar(correoUnico(), Boolean.FALSE, refreshBruto, futuro);

            Response respuesta = post(MARKETPLACE_REFRESH, csrfJar(),
                    "{\"refreshToken\":\"" + refreshBruto + "\"}");

            respuesta.then()
                    .statusCode(401)
                    .body("error.code", equalTo("INVALID_TOKEN"))
                    .body("error.message",
                            equalTo(ClientAuthService.MENSAJE_CREDENCIALES_INVALIDAS));
            esEnvelope(respuesta);
            assertThat(respuesta.getBody().asString())
                    .as("el cuerpo no debe volver a decir que la cuenta esta desactivada")
                    .doesNotContain("desactivada");
        }
    }

    // ── perfil ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("perfil: /me responde en el envelope, no en el cuerpo plano")
    void meSinIdentidadSigueSiendoEnvelope() {
        Response respuesta = getConToken(MARKETPLACE_ME, null);

        // El status exacto lo decide como el contenedor materialice el
        // SecurityContext vacio (401 UNAUTHENTICATED si no hay principal, 500
        // INTERNAL_ERROR si hay un principal que no es un codigo de cliente), y
        // eso es preexistente. Lo que se fija es que ninguna de las dos salidas
        // vuelva como {"error":"..."}.
        assertThat(respuesta.getStatusCode())
                .as("sin identidad /me solo puede responder 401 o 500: " + respuesta.getBody().asString())
                .isIn(401, 500);
        esEnvelope(respuesta);

        String codigo = new JsonPath(respuesta.getBody().asString()).getString("error.code");
        assertThat(codigo)
                .as("el codigo debe corresponder al status: 401 UNAUTHENTICATED, 500 INTERNAL_ERROR")
                .isEqualTo(401 == respuesta.getStatusCode() ? "UNAUTHENTICATED" : "INTERNAL_ERROR");
    }
}
