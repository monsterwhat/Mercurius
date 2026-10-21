package Services;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * El identificador que el IdP de Hacienda tiene registrado no siempre es el
 * numero completo tal como se escribe hoy.
 *
 * <p>Verificado contra el IdP de produccion (2026-10-21) con la credencial del
 * sistema anterior: {@code cpf-01-0455-0334@prod...} devuelve token y
 * {@code cpf-01-104550334@prod...} responde {@code invalid_grant}. Con el
 * numero mal armado la app no podia autenticarse nunca y por lo tanto tampoco
 * podia emitir facturas electronicas.</p>
 */
@Tag("hacienda-crypto")
class HaciendaIdpUsernameTest {

    @Test
    void personaFisicaUsaElNumeroComoLoTieneRegistradoHacienda() {
        // 1-0455-0334 (cedula moderna) -> 0455-0334 (registro en Hacienda)
        assertEquals("0455-0334", HaciendaApiService.idpIdNumber("01", "104550334"));
        assertEquals("0455-0334", HaciendaApiService.idpIdNumber("01", " 104550334 "));
    }

    @Test
    void personaJuridicaSeparaElDigitoDeTipo() {
        // 3-101-123456 -> 3-101-123456
        assertEquals("3-101-123456", HaciendaApiService.idpIdNumber("02", "3101123456"));
    }

    @Test
    void losDemasTiposSeUsanTalCual() {
        assertEquals("155812345678", HaciendaApiService.idpIdNumber("03", "155812345678"));
        assertEquals("12345678901234", HaciendaApiService.idpIdNumber("04", "12345678901234"));
        // Una cedula fisica corta (sin el digito de provincia) no se toca
        assertEquals("12345678", HaciendaApiService.idpIdNumber("01", "12345678"));
    }
}
