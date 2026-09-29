package Utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Credential-guess throttling: the counter that bounds password guessing.
 *
 * <p>Behavioral pin for the fix of two unthrottled BCrypt oracles:</p>
 * <ul>
 *   <li>{@code POST /api/app/auth/supervisor-authorize} verifies a hash for an
 *       arbitrary caller-supplied username;</li>
 *   <li>{@code PUT /api/app/users/{id}/password} verifies a hash for whatever id
 *       the caller names.</li>
 * </ul>
 *
 * <p>Both are throttled per account AND per source address. The account key stops
 * one target being ground down; the address key stops one caller spraying many
 * targets (credential stuffing), which the account key alone cannot catch because
 * each victim stays under its own threshold.</p>
 */
@DisplayName("IntentosDeCredencial: limite de intentos de credenciales")
class IntentosDeCredencialTest {

    private IntentosDeCredencial limiter;

    @BeforeEach
    void setUp() {
        limiter = new IntentosDeCredencial();
        limiter.maxIntentos = 3;
        limiter.bloqueoMinutos = 15;
        limiter.ventanaMinutos = 15;
        limiter.init();
        limiter.limpiar();
    }

    @Test
    @DisplayName("un intento permitido no bloquea")
    void primerIntentoPermitido() {
        assertThat(limiter.restanteBloqueo("admin", "10.0.0.1")).isNull();
    }

    @Test
    @DisplayName("la cuenta se bloquea al superar el maximo de intentos")
    void cuentaSeBloqueaAlSuperarElMaximo() {
        for (int i = 0; i < 3; i++) {
            limiter.registrarFallo("admin", "10.0.0.1");
        }
        // Tres fallos = el maximo, todavia no bloqueado.
        assertThat(limiter.restanteBloqueo("admin", "10.0.0.2"))
                .as("con exactamente maxIntentos fallos aun se permite")
                .isNull();

        limiter.registrarFallo("admin", "10.0.0.3");

        assertThat(limiter.restanteBloqueo("admin", "10.0.0.4"))
                .as("al superar el maximo la cuenta queda bloqueada")
                .isNotNull();
    }

    @Test
    @DisplayName("el bloqueo de la cuenta no depende de la direccion de origen")
    void elBloqueoNoDependeDeLaDireccion() {
        // Un atacante que rota de IP sigue topandose con el bloqueo de la cuenta.
        for (int i = 0; i < 5; i++) {
            limiter.registrarFallo("admin", "10.0.0." + i);
        }
        assertThat(limiter.restanteBloqueo("admin", "192.168.1.99")).isNotNull();
    }

    @Test
    @DisplayName("la direccion queda bloqueada aunque la cuenta sea distinta")
    void direccionSeBloqueaPorSpray() {
        // Credential stuffing: cada victima por debajo de su propio limite, pero
        // el mismo origen las supera todas juntas.
        for (int i = 0; i < 5; i++) {
            limiter.registrarFallo("usuario" + i, "10.0.0.7");
        }
        assertThat(limiter.restanteBloqueo("usuario999", "10.0.0.7"))
                .as("el mismo origen no puede probar cuentas ilimitadas")
                .isNotNull();
    }

    @Test
    @DisplayName("una cuenta distinta desde otra direccion no esta bloqueada")
    void otrosNoEstanBloqueados() {
        for (int i = 0; i < 5; i++) {
            limiter.registrarFallo("admin", "10.0.0.1");
        }
        assertThat(limiter.restanteBloqueo("cajero", "10.0.0.2"))
                .as("el bloqueo es por clave, no global")
                .isNull();
    }

    @Test
    @DisplayName("un acierto limpia los contadores de cuenta y direccion")
    void aciertoLimpiaContadores() {
        for (int i = 0; i < 5; i++) {
            limiter.registrarFallo("admin", "10.0.0.1");
        }
        assertThat(limiter.restanteBloqueo("admin", "10.0.0.1")).isNotNull();

        limiter.registrarExito("admin", "10.0.0.1");

        assertThat(limiter.restanteBloqueo("admin", "10.0.0.1"))
                .as("un usuario legitimo que se equivoco dos veces no debe quedar a un intento del bloqueo")
                .isNull();
    }

    @Test
    @DisplayName("el nombre de usuario se compara sin distinguir mayusculas")
    void usuarioNormalizado() {
        for (int i = 0; i < 5; i++) {
            limiter.registrarFallo("Admin", "10.0.0.1");
        }
        assertThat(limiter.restanteBloqueo("admin", "10.0.0.2"))
                .as("Admin y admin son la misma cuenta")
                .isNotNull();
    }

    @Test
    @DisplayName("un nombre nulo o vacio no rompe el contador")
    void usuarioNuloOVacio() {
        assertThat(limiter.restanteBloqueo(null, null)).isNull();
        // Se supera el maximo (3) para comprobar que la clave nula tambien cuenta.
        for (int i = 0; i < 4; i++) {
            limiter.registrarFallo(null, null);
        }
        assertThat(limiter.restanteBloqueo(null, null))
                .as("una cuenta nula tambien debe quedar limitada, no quedar sin contabilizar")
                .isNotNull();

        for (int i = 0; i < 4; i++) {
            limiter.registrarFallo("", "  ");
        }
        assertThat(limiter.restanteBloqueo("", "  ")).isNotNull();
    }

    @Test
    @DisplayName("las claves de cuenta y direccion no colisionan")
    void clavesNoColisionan() {
        String cuenta = IntentosDeCredencial.claveCuenta("admin");
        String dir = IntentosDeCredencial.claveDireccion("admin");
        assertThat(cuenta).isNotEqualTo(dir);
    }
}
