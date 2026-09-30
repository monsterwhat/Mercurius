package Controllers.Api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import Models.Clientes;
import Services.ClientAuthService;
import Services.ClientService;
import Utils.IntentosDeCredencial;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Los cuatro rechazos de login del mercado deben ser INDISTINGUIBLES por el
 * cable, y su 401 debe usar el mismo envelope {@code ApiResponse} que su 429.
 *
 * <p><b>Por que importa.</b> {@code ClientAuthService.login} tiene cuatro
 * salidas negativas —correo no registrado, contrasena incorrecta, cuenta sin
 * password de mercado y cuenta desactivada— y las tres ultimas solo son
 * alcanzables con un correo YA REGISTRADO. Con un texto propio por rama, el
 * cuerpo del 401 era un oraculo de enumeracion de usuarios: el coste de BCrypt
 * ya estaba igualado con {@code verificarContraHashFalso}, pero el mensaje
 * seguia delatando la rama. Estas pruebas fijan el contrato que lo cierra: las
 * cuatro respuestas son identicas byte a byte.</p>
 *
 * <p><b>Por que se compara el cuerpo crudo y no un campo.</b> Comparar
 * {@code error.message} dejaria pasar diferencias en el codigo, en el orden de
 * las claves o en cualquier otro campo del envelope. Lo que importa es que un
 * atacante no pueda distinguir las ramas leyendo NADA de la respuesta, asi que
 * lo que se compara es el cuerpo entero, como cadena.</p>
 *
 * <p><b>Por que los dos endpoints.</b> {@code /api/marketplace/auth/login} y
 * {@code /api/v1/mercatus/clients/auth/login} ejecutan el MISMO servicio. Si
 * divergen en envelope o en mensaje, el cliente tiene dos contratos que
 * mantener. Ninguno de los dos pide Bearer: mercatus esta exento de
 * {@code PublicApiJwtFilter} por igualdad exacta de ruta.</p>
 *
 * <p><b>Aislamiento.</b> Las filas se COMITEAN (la peticion HTTP corre en otra
 * transaccion y jamas veria un fixture sin commit) y se borran siempre en un
 * finally con EntityManager+UserTransaction. Los correos llevan UUID para no
 * chocar ni con {@code import-test.sql} ni entre corridas. El limiter es
 * {@code @ApplicationScoped} y su clave de DIRECCION la comparten todas las
 * peticiones del JVM, asi que se limpia en cada prueba: 6 fallos bloquean, y
 * varias de estas pruebas necesitan cuatro.</p>
 */
@QuarkusTest
@DisplayName("Login del mercado: mensaje unico y envelope comun en 401 y 429")
class ClientLoginUnifiedFailureTest {

    private static final String MARKETPLACE_LOGIN = "/api/marketplace/auth/login";
    private static final String MERCATUS_LOGIN = "/api/v1/mercatus/clients/auth/login";

    private static final String LOGIN_PAGE = "/login";
    private static final String CSRF_COOKIE = "csrf-token";
    private static final String CSRF_HEADER = "X-CSRF-TOKEN";

    private static final String CLAVE = "clave-de-prueba-mercurius";
    private static final String CLAVE_MALA = "contrasena-equivocada";

    /** El bloqueo se observa en el fallo max+1 ({@code restanteBloqueo}: n > 5). */
    private static final int FALLOS_PARA_BLOQUEAR = 6;

    /** Direccion hipotetica del atacante; la clave de cuenta es la que manda. */
    private static final String DIRECCION_DE_ORIGEN = "10.9.9.9";

    /**
     * Hash cost-12 calculado UNA sola vez para toda la clase: es el mismo trabajo
     * que hace {@code ClientAuthService.hashPassword}, replicado aqui porque el
     * bean no existe todavia en un inicializador estatico y sembrar tres
     * clientes por prueba multiplicaria el coste sin anadir cobertura.
     */
    private static final String HASH_CLAVE = at.favre.lib.crypto.bcrypt.BCrypt
            .withDefaults().hashToString(12, CLAVE.toCharArray());

    @Inject
    ClientService clientService;

    @Inject
    IntentosDeCredencial intentosDeCredencial;

    @Inject
    EntityManager em;

    @Inject
    UserTransaction utx;

    @BeforeEach
    void limpiarContadores() {
        // Sin esto, la primera prueba que gasta el presupuesto de la DIRECCION
        // (compartido por todas las peticiones del JVM) responderia 429 a las
        // demas y las comparaciones de cuerpo no se ejecutarian.
        intentosDeCredencial.limpiar();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static String correoUnico() {
        return "unif-" + UUID.randomUUID() + "@mercurius.local";
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

    private Response login(String ruta, Map<String, String> jar, String email, String password) {
        return given().redirects().follow(false)
                .cookies(jar)
                .header(CSRF_HEADER, jar.get(CSRF_COOKIE))
                .contentType(ContentType.JSON)
                .body("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
                .when().post(ruta);
    }

    /**
     * Cliente con todos los NOT NULL de {@code Clients} (discount/taxpayer/zoneCode
     * son primitivos). {@code password == null} modela la cuenta creada por un
     * admin, sin acceso al mercado.
     *
     * @return el {@code code} asignado por IDENTITY, para el borrado posterior
     */
    private int sembrarCliente(String email, String password, Boolean estado) {
        Clientes cliente = new Clientes();
        cliente.setName("IT Unif " + UUID.randomUUID().toString().substring(0, 8));
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
        cliente.setPassword(password);
        clientService.create(cliente);
        // ClientService.create se traga la PersistenceException: sin este guardia
        // una fixture que no entrara devolveria 0 y la prueba seguiria contando
        // la rama not-found en vez de la que dice estar probando.
        assertThat(cliente.getCode())
                .as("la fixture debio insertarse con un code asignado por IDENTITY")
                .isGreaterThan(0);
        return cliente.getCode();
    }

    /**
     * Las cuatro situaciones de rechazo, sembradas y comprometidas.
     *
     * <p>AutoCloseable para que el borrado vaya SIEMPRE en un finally, tambien
     * si la asercion de igualdad de cuerpos falla a mitad de la prueba: si no,
     * una prueba roja deja filas huerfanas en {@code mercurius_test}.</p>
     */
    private final class Situaciones implements AutoCloseable {

        /** No se siembra: modela el correo que nadie registro. */
        final String correoInexistente = correoUnico();
        /** Registrado y activo; el login falla por la contrasena. */
        final String correoContrasenaIncorrecta = correoUnico();
        /** Registrado, sin password de mercado (creado por un admin). */
        final String correoSinAcceso = correoUnico();
        /** Registrado, con password correcta, pero desactivado. */
        final String correoDesactivado = correoUnico();

        private final List<Integer> codigos = new ArrayList<>();

        Situaciones() {
            codigos.add(sembrarCliente(correoContrasenaIncorrecta, HASH_CLAVE, Boolean.TRUE));
            codigos.add(sembrarCliente(correoSinAcceso, null, Boolean.TRUE));
            codigos.add(sembrarCliente(correoDesactivado, HASH_CLAVE, Boolean.FALSE));
        }

        /** Los cuatro correos, en el orden en que se comparan sus respuestas. */
        List<String> correos() {
            return List.of(correoInexistente, correoContrasenaIncorrecta,
                    correoSinAcceso, correoDesactivado);
        }

        /** Password que corresponde a cada correo; el inexistente va con la mala. */
        List<String> contrasenas() {
            return List.of(CLAVE_MALA, CLAVE_MALA, CLAVE_MALA, CLAVE);
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

    /** Los cuatro cuerpos crudos de rechazo de una ruta, en orden. */
    private List<String> cuatroCuerposDeRechazo(String ruta, Map<String, String> jar,
                                                Situaciones s) {
        List<String> cuerpos = new ArrayList<>();
        for (int i = 0; i < s.correos().size(); i++) {
            Response respuesta = login(ruta, jar, s.correos().get(i), s.contrasenas().get(i));
            respuesta.then().statusCode(401);
            cuerpos.add(respuesta.getBody().asString());
        }
        return cuerpos;
    }

    private static void cuerposIdenticos(List<String> cuerpos) {
        Set<String> distintos = new LinkedHashSet<>(cuerpos);
        assertThat(distintos)
                .as("las cuatro ramas de rechazo deben devolver el MISMO cuerpo byte a byte; "
                        + "si hay mas de uno, el 401 sigue enumerando que correos estan registrados")
                .hasSize(1);
    }

    /** Top-level keys del envelope, para comparar la FORMA entre status. */
    private static Set<String> clavesRaiz(String cuerpo) {
        Map<String, Object> raiz = new JsonPath(cuerpo).getMap("$");
        assertThat(raiz)
                .as("el cuerpo debe ser un objeto JSON, no texto plano: " + cuerpo)
                .isNotNull();
        return raiz.keySet();
    }

    /**
     * El campo {@code data} del envelope debe venir en null. Se lee a un
     * {@code Object} declarado porque {@code JsonPath.get} devuelve un {@code T}
     * generico y el {@code assertThat} de AssertJ queda ambiguo entre sus
     * sobrecargas de {@code Predicate}/{@code IntPredicate}.
     */
    private static void dataEsNull(String cuerpo) {
        Object data = new JsonPath(cuerpo).get("data");
        assertThat(data).as("un rechazo no lleva payload de exito: " + cuerpo).isNull();
    }

    // ── escenarios ──────────────────────────────────────────────────────

    @Test
    @DisplayName("marketplace: inexistente, contrasena mala, sin acceso y desactivada son indistinguibles")
    void losCuatroRechazosDeMarketplaceSonIndistinguibles() {
        Map<String, String> jar = csrfJar();
        try (Situaciones s = new Situaciones()) {
            cuerposIdenticos(cuatroCuerposDeRechazo(MARKETPLACE_LOGIN, jar, s));
        }
    }

    @Test
    @DisplayName("mercatus: inexistente, contrasena mala, sin acceso y desactivada son indistinguibles")
    void losCuatroRechazosDeMercatusSonIndistinguibles() {
        Map<String, String> jar = csrfJar();
        try (Situaciones s = new Situaciones()) {
            cuerposIdenticos(cuatroCuerposDeRechazo(MERCATUS_LOGIN, jar, s));
        }
    }

    @Test
    @DisplayName("los dos endpoints de login devuelven el mismo cuerpo en el 401")
    void losDosEndpointsCompartenElCuerpoDel401() {
        Map<String, String> jar = csrfJar();
        try (Situaciones s = new Situaciones()) {
            String marketplace = login(MARKETPLACE_LOGIN, jar, s.correoContrasenaIncorrecta, CLAVE_MALA)
                    .getBody().asString();
            String mercatus = login(MERCATUS_LOGIN, jar, s.correoContrasenaIncorrecta, CLAVE_MALA)
                    .getBody().asString();

            assertThat(marketplace)
                    .as("un cliente de mercado no debe tener que mantener dos contratos de error")
                    .isEqualTo(mercatus);
        }
    }

    @Test
    @DisplayName("el 401 de ambos endpoints usa el envelope ApiResponse con INVALID_CREDENTIALS")
    void el401UsaElEnvelopeDeApiResponse() {
        Map<String, String> jar = csrfJar();
        try (Situaciones s = new Situaciones()) {
            for (String ruta : List.of(MARKETPLACE_LOGIN, MERCATUS_LOGIN)) {
                Response respuesta = login(ruta, jar, s.correoContrasenaIncorrecta, CLAVE_MALA);
                respuesta.then()
                        .statusCode(401)
                        .header("Content-Type", org.hamcrest.Matchers.containsString("application/json"))
                        .body("error.code", org.hamcrest.Matchers.equalTo("INVALID_CREDENTIALS"))
                        .body("error.message",
                                org.hamcrest.Matchers.equalTo(ClientAuthService.MENSAJE_CREDENCIALES_INVALIDAS))
                        .body("error.details.size()", org.hamcrest.Matchers.equalTo(0));

                Set<String> raiz = clavesRaiz(respuesta.getBody().asString());
                assertThat(raiz)
                        .as("el envelope debe traer data y error, como el 429: " + raiz)
                        .containsExactlyInAnyOrder("data", "error");
                dataEsNull(respuesta.getBody().asString());
            }
        }
    }

    @Test
    @DisplayName("el cuerpo del 401 ya no nombra ninguna de las situaciones que antes lo distinguia")
    void el401YaNoNombraLasSituacionesQueAntesLoHacia() {
        Map<String, String> jar = csrfJar();
        try (Situaciones s = new Situaciones()) {
            // Guarda contra una reintroduccion parcial: con esos textos el cuerpo
            // vuelve a decir "esta cuenta existe pero no tiene acceso" / "esta
            // cuenta esta desactivada", que es exactamente la enumeracion.
            for (String ruta : List.of(MARKETPLACE_LOGIN, MERCATUS_LOGIN)) {
                String cuerpo = login(ruta, jar, s.correoSinAcceso, CLAVE_MALA)
                        .getBody().asString();
                assertThat(cuerpo)
                        .as("ruta " + ruta)
                        .doesNotContain("mercado en línea")
                        .doesNotContain("desactivada")
                        .doesNotContain("administrador");
            }
        }
    }

    @Test
    @DisplayName("el 429 conserva status, Retry-After y la misma forma de envelope que el 401")
    void el429MantieneSuContratoYComparteLaFormaDel401() {
        Map<String, String> jar = csrfJar();
        String email = correoUnico();
        // El presupuesto se agota por el limiter, sin pasar por BCrypt: el 429 se
        // decide ANTES de la verificacion, que es justo lo que lo deja alcanzable
        // incluso para una peticion que iba a fallar igual.
        for (int i = 0; i < FALLOS_PARA_BLOQUEAR; i++) {
            intentosDeCredencial.registrarFallo(email, DIRECCION_DE_ORIGEN);
        }

        Response bloqueo = login(MARKETPLACE_LOGIN, jar, email, CLAVE_MALA);
        bloqueo.then()
                .statusCode(429)
                .header("Retry-After", org.hamcrest.Matchers.notNullValue())
                .body("error.code", org.hamcrest.Matchers.equalTo("TOO_MANY_ATTEMPTS"));

        Set<String> raiz429 = clavesRaiz(bloqueo.getBody().asString());
        assertThat(raiz429)
                .as("401 y 429 deben ser el mismo envelope para que el cliente tenga un solo lector")
                .containsExactlyInAnyOrder("data", "error");
        dataEsNull(bloqueo.getBody().asString());

        // Y contra el 401 real, para que la comparacion sea sobre el mismo contrato.
        try (Situaciones s = new Situaciones()) {
            Response rechazo = login(MARKETPLACE_LOGIN, jar, s.correoContrasenaIncorrecta, CLAVE_MALA);
            rechazo.then().statusCode(401);
            assertThat(clavesRaiz(rechazo.getBody().asString()))
                    .as("el 401 debe tener las mismas claves de primer nivel que el 429")
                    .isEqualTo(raiz429);
            assertThat(rechazo.getBody().asString())
                    .as("ambos envuelven el motivo en error.code + error.message + error.details")
                    .contains("\"code\":").contains("\"message\":").contains("\"details\":");
        }
    }
}
