package Services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import Models.Clientes;
import Models.DTO.AuthResponse;
import Models.DTO.LoginRequest;
import Utils.IntentosDeCredencial;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contabilidad de intentos dentro de {@link ClientAuthService#login}, el unico
 * lugar que sabe que rama rechazo y por lo tanto el unico que puede pagar el
 * coste de igualacion y registrar el fallo.
 *
 * <p>Cubre las cuatro salidas negativas y la positiva:</p>
 * <ul>
 *   <li>correo inexistente — igualado con {@code verificarContraHashFalso};</li>
 *   <li>cuenta sin password de mercado (creada por un admin) — tambien igualado,
 *       porque nunca se alcanza una verificacion real;</li>
 *   <li>contrasena incorrecta — verificacion real fallida;</li>
 *   <li>cuenta desactivada con la contrasena correcta;</li>
 *   <li>acierto — limpia el contador de la cuenta.</li>
 * </ul>
 *
 * <p>Se llama al servicio por CDI y no por HTTP a proposito: asi se comprueba
 * solo la contabilidad, sin el token que {@code PublicApiJwtFilter} exige en
 * {@code /api/v1/**} ni el doble envio de CSRF. El contrato HTTP del 429 vive en
 * {@code Controllers.Api.ClientLoginThrottleTest}.</p>
 *
 * <p>Todas las pruebas van en {@link TestTransaction}: la fila sembrada nunca se
 * commitea, asi que no puede filtrarse a otra prueba ni a la base compartida, y
 * no hace falta bloque finally. Los correos llevan UUID para no chocar ni con las
 * filas de import-test.sql ni entre corridas.</p>
 */
@QuarkusTest
@DisplayName("ClientAuthService.login: limite de intentos de credenciales")
class ClientAuthServiceThrottleTest {

    private static final String CLAVE = "clave-de-prueba-mercurius";
    private static final String CLAVE_MALA = "contrasena-equivocada";

    /**
     * {@code IntentosDeCredencial.restanteBloqueo} solo bloquea cuando el contador
     * supera {@code max} (5), asi que hacen falta 6 fallos para observarlo. No se
     * baja el limite configurado: 5 es el valor que ve un atacante.
     */
    private static final int FALLOS_PARA_BLOQUEAR = 6;

    private static final String DIRECCION = "10.1.1.1";
    private static final String OTRA_DIRECCION = "10.2.2.2";
    private static final String DIRECCION_DE_SONDEO = "10.3.3.3";

    @Inject
    ClientAuthService clientAuthService;

    @Inject
    ClientService clientService;

    @Inject
    IntentosDeCredencial intentosDeCredencial;

    @BeforeEach
    void limpiarContadores() {
        intentosDeCredencial.limpiar();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static String correoUnico() {
        return "throttle-" + UUID.randomUUID() + "@mercurius.local";
    }

    /**
     * Cliente con todos los NOT NULL de {@code Clients} (discount/taxpayer/zoneCode
     * son primitivos). {@code password == null} modela la cuenta creada por un
     * admin, sin acceso al mercado.
     */
    private void sembrarCliente(String email, String password, Boolean estado) {
        Clientes cliente = new Clientes();
        cliente.setName("IT Throttle " + UUID.randomUUID().toString().substring(0, 8));
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
    }

    private void intentarFallar(String email, String password, String direccion) {
        assertThrows(IllegalArgumentException.class,
                () -> clientAuthService.login(new LoginRequest(email, password), direccion),
                "un login invalido debe seguir rechazando con IllegalArgumentException");
    }

    // ── escenarios ──────────────────────────────────────────────────────

    @Test
    @TestTransaction
    @DisplayName("la contrasena incorrecta se cuenta como intento fallido")
    void contrasenaIncorrectaCuentaElIntento() {
        String email = correoUnico();
        sembrarCliente(email, clientAuthService.hashPassword(CLAVE), Boolean.TRUE);

        for (int i = 0; i < FALLOS_PARA_BLOQUEAR; i++) {
            intentarFallar(email, CLAVE_MALA, DIRECCION);
        }

        assertThat(intentosDeCredencial.restanteBloqueo(email, DIRECCION))
                .as("una cuenta real con la contrasena equivocada no puede seguir probada")
                .isNotNull();
    }

    @Test
    @TestTransaction
    @DisplayName("un correo inexistente tambien se cuenta, con hash de comparacion")
    void correoInexistenteCuentaElIntento() {
        // Sin cliente sembrado. Cuenta igual que el camino de contrasena
        // incorrecta (mismo precedente que la autenticacion por formulario) y
        // paga el mismo coste con verificarContraHashFalso, de modo que la
        // respuesta no revela que el correo no existe. No contarlo abriria de
        // vuelta el sondeo de tiempos.
        String email = correoUnico();

        for (int i = 0; i < FALLOS_PARA_BLOQUEAR; i++) {
            intentarFallar(email, CLAVE_MALA, DIRECCION);
        }

        assertThat(intentosDeCredencial.restanteBloqueo(email, DIRECCION))
                .as("probar correos inexistentes no puede ser un camino gratis e ilimitado")
                .isNotNull();
    }

    @Test
    @TestTransaction
    @DisplayName("una cuenta sin acceso al mercado tambien se cuenta e iguala el tiempo")
    void cuentaSinPasswordDeMercadoCuentaElIntento() {
        // Cliente creado por un admin: password == null, asi que esta rama
        // devolvia sin tocar BCrypt. Es el segundo oraculo de enumeracion que
        // queda cerrado junto con el not-found.
        String email = correoUnico();
        sembrarCliente(email, null, Boolean.TRUE);

        for (int i = 0; i < FALLOS_PARA_BLOQUEAR; i++) {
            intentarFallar(email, CLAVE_MALA, DIRECCION);
        }

        assertThat(intentosDeCredencial.restanteBloqueo(email, DIRECCION))
                .as("una cuenta sin password de mercado no puede probarse ilimitadamente")
                .isNotNull();
    }

    @Test
    @TestTransaction
    @DisplayName("una cuenta desactivada con la contrasena correcta se cuenta como intento fallido")
    void cuentaDesactivadaCuentaElIntento() {
        String email = correoUnico();
        sembrarCliente(email, clientAuthService.hashPassword(CLAVE), Boolean.FALSE);

        for (int i = 0; i < FALLOS_PARA_BLOQUEAR; i++) {
            intentarFallar(email, CLAVE, DIRECCION);
        }

        assertThat(intentosDeCredencial.restanteBloqueo(email, DIRECCION))
                .as("rechazar una cuenta desactivada no da un intento gratis por intento")
                .isNotNull();
    }

    @Test
    @TestTransaction
    @DisplayName("un acierto limpia el contador de la cuenta")
    void aciertoLimpiaElContadorDeLaCuenta() {
        String email = correoUnico();
        sembrarCliente(email, clientAuthService.hashPassword(CLAVE), Boolean.TRUE);

        for (int i = 0; i < FALLOS_PARA_BLOQUEAR; i++) {
            intentarFallar(email, CLAVE_MALA, DIRECCION);
        }
        assertThat(intentosDeCredencial.restanteBloqueo(email, DIRECCION))
                .as("precondicion: la cuenta quedo bloqueada tras los fallos")
                .isNotNull();

        // El acierto se registra desde OTRA direccion, asi que registrarExito
        // borra la clave de cuenta y la de esa direccion, pero NO la de DIRECCION.
        // Consultar con una tercera direccion aisla la clave de cuenta: si
        // registrarExito no existiera, aqui seguiria bloqueada con 6 fallos.
        AuthResponse respuesta = clientAuthService.login(new LoginRequest(email, CLAVE), OTRA_DIRECCION);
        assertThat(respuesta).as("el acierto debe devolver tokens").isNotNull();
        assertThat(respuesta.getAccessToken()).isNotBlank();

        assertThat(intentosDeCredencial.restanteBloqueo(email, DIRECCION_DE_SONDEO))
                .as("un usuario legitimo que se equivoco varias veces no puede quedar a un intento del bloqueo")
                .isNull();
    }
}
