package Services.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pruebas puras de la validacion de secretos/CORS de produccion. */
class ValidacionConfiguracionProduccionTest {

    private static final String JWT_OK = "una-clave-jwt-de-produccion-con-mas-de-32-caracteres";
    private static final String SESION_OK = "una-clave-de-sesion-de-produccion-con-32-o-mas";
    private static final String CORS_OK = "https://app.mercurius.cr,https://admin.mercurius.cr";

    @Test
    void devYTestNoExigenSecretos() {
        assertThat(ValidacionConfiguracionProduccion.validar(
                false, null, null, "*")).isEmpty();
    }

    @Test
    void produccionSinSecretosFalla() {
        List<String> errores = ValidacionConfiguracionProduccion.validar(
                true, null, null, null);

        assertThat(errores).hasSize(3);
        assertThat(errores).anyMatch(e -> e.contains("mercatus.jwt.secret"));
        assertThat(errores).anyMatch(e -> e.contains("session.encryption-key"));
        assertThat(errores).anyMatch(e -> e.contains("cors.origins"));
    }

    @Test
    void secretoDeEjemploFallaEnProduccion() {
        List<String> errores = ValidacionConfiguracionProduccion.validar(
                true, ValidacionConfiguracionProduccion.JWT_DEV,
                ValidacionConfiguracionProduccion.SESION_DEV, CORS_OK);

        assertThat(errores).anyMatch(e -> e.contains("valor de ejemplo"));
        assertThat(errores).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void secretoCortoFallaEnProduccion() {
        List<String> errores = ValidacionConfiguracionProduccion.validar(
                true, "corto", "tambien-corto", CORS_OK);

        assertThat(errores).anyMatch(e -> e.contains("32 caracteres"));
    }

    @Test
    void configuracionValidaPasa() {
        assertThat(ValidacionConfiguracionProduccion.validar(
                true, JWT_OK, SESION_OK, CORS_OK)).isEmpty();
    }

    @Test
    void comodinCorsFallaEnProduccion() {
        List<String> errores = ValidacionConfiguracionProduccion.validar(
                true, JWT_OK, SESION_OK, "*");

        assertThat(errores).anyMatch(e -> e.contains("no puede ser '*'"));
    }

    @Test
    void origenCorsConRutaOFtpFalla() {
        List<String> errores = ValidacionConfiguracionProduccion.validar(
                true, JWT_OK, SESION_OK, "https://app.mercurius.cr/ruta, ftp://viejo.local");

        assertThat(errores).hasSize(2);
    }
}
