package Models.Correos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The provider setting must decide where the mail subsystem connects.
 *
 * <p>{@code Services.EmailService} carried {@code smtp.gmail.com} and
 * {@code imap.gmail.com} as literals in six places, so the mailbox sweep could
 * not read an Outlook or Yahoo mailbox and no sender could use a relay. These
 * tests pin the mapping that replaced them.</p>
 *
 * <p>The property that must not regress: <b>Gmail is the default for every
 * unset/blank/unknown value</b>. A row written before the columns existed (or a
 * database that has not been migrated yet) resolves to exactly the endpoints
 * the hard-coded constants named, so no existing installation changes
 * behaviour by not touching the new setting.</p>
 */
@DisplayName("ProveedorCorreo: la eleccion del proveedor fija los endpoints")
class ProveedorCorreoTest {

    // ── Presets ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Los presets traen sus propios endpoints")
    class Presets {

        @Test
        @DisplayName("Gmail conserva imap.gmail.com:993 y smtp.gmail.com:587")
        void gmail() {
            ConfiguracionConexion.Resuelta r = ConfiguracionConexion.resolver(
                    ProveedorCorreo.GMAIL, null, null, null, null);

            assertThat(r.imapHost()).isEqualTo("imap.gmail.com");
            assertThat(r.imapPuerto()).isEqualTo(993);
            assertThat(r.smtpHost()).isEqualTo("smtp.gmail.com");
            assertThat(r.smtpPuerto()).isEqualTo(587);
        }

        @Test
        @DisplayName("Outlook usa outlook.office365.com:993 y smtp.office365.com:587")
        void outlook() {
            ConfiguracionConexion.Resuelta r = ConfiguracionConexion.resolver(
                    ProveedorCorreo.OUTLOOK, null, null, null, null);

            assertThat(r.imapHost()).isEqualTo("outlook.office365.com");
            assertThat(r.imapPuerto()).isEqualTo(993);
            assertThat(r.smtpHost()).isEqualTo("smtp.office365.com");
            assertThat(r.smtpPuerto()).isEqualTo(587);
        }

        @Test
        @DisplayName("Yahoo usa imap.mail.yahoo.com:993 y smtp.mail.yahoo.com:587")
        void yahoo() {
            ConfiguracionConexion.Resuelta r = ConfiguracionConexion.resolver(
                    ProveedorCorreo.YAHOO, null, null, null, null);

            assertThat(r.imapHost()).isEqualTo("imap.mail.yahoo.com");
            assertThat(r.imapPuerto()).isEqualTo(993);
            assertThat(r.smtpHost()).isEqualTo("smtp.mail.yahoo.com");
            assertThat(r.smtpPuerto()).isEqualTo(587);
        }
    }

    // ── Backwards compatibility ────────────────────────────────────────

    @Nested
    @DisplayName("Sin configuracion, el comportamiento es el de Gmail de siempre")
    class DefaultEsGmail {

        @ParameterizedTest(name = "clave \"{0}\" -> GMAIL")
        @ValueSource(strings = {"", "   ", "GMAIL", "gmail", "  Gmail  "})
        @DisplayName("null/vacio/ignorante/mayusculas-impuras resuelven a GMAIL")
        void clavesDesconocidasResuelvenAGmail(String clave) {
            assertThat(ProveedorCorreo.desdeClave(clave)).isEqualTo(ProveedorCorreo.GMAIL);
        }

        @Test
        @DisplayName("null (columna ausente, fila vieja) resuelve a GMAIL")
        void nullResuelveAGmail() {
            assertThat(ProveedorCorreo.desdeClave(null)).isEqualTo(ProveedorCorreo.GMAIL);
        }

        @Test
        @DisplayName("una fila sin ningun dato de proveedor conecta igual que antes")
        void filaVaciaConectaComoAntes() {
            ConfiguracionConexion.Resuelta r = ConfiguracionConexion.desdeClave(null, null, null, null, null);

            assertThat(r.imapHost()).isEqualTo("imap.gmail.com");
            assertThat(r.imapPuerto()).isEqualTo(993);
            assertThat(r.smtpHost()).isEqualTo("smtp.gmail.com");
            assertThat(r.smtpPuerto()).isEqualTo(587);
        }

        @Test
        @DisplayName("el nombre del proveedor en cualquier caja se acepta")
        void claveNormalizada() {
            assertThat(ProveedorCorreo.desdeClave("outlook")).isEqualTo(ProveedorCorreo.OUTLOOK);
            assertThat(ProveedorCorreo.desdeClave(" Yahoo ")).isEqualTo(ProveedorCorreo.YAHOO);
            assertThat(ProveedorCorreo.desdeClave("personalizado")).isEqualTo(ProveedorCorreo.PERSONALIZADO);
        }
    }

    // ── Custom provider ────────────────────────────────────────────────

    @Nested
    @DisplayName("PERSONALIZADO usa los host/puerto escritos por el operador")
    class Personalizado {

        @Test
        @DisplayName("los endpoints propios se respetan tal cual")
        void respetaEndpointsPropios() {
            ConfiguracionConexion.Resuelta r = ConfiguracionConexion.resolver(
                    ProveedorCorreo.PERSONALIZADO,
                    "correo.midominio.com", "1143",
                    "smtp.midominio.com", "2525");

            assertThat(r.imapHost()).isEqualTo("correo.midominio.com");
            assertThat(r.imapPuerto()).isEqualTo(1143);
            assertThat(r.smtpHost()).isEqualTo("smtp.midominio.com");
            assertThat(r.smtpPuerto()).isEqualTo(2525);
        }

        @Test
        @DisplayName("con campos vacios cae a Gmail, nunca a null")
        void camposVaciosCaenAGmail() {
            // Un formulario a medias no puede dejar el cliente de correo con un
            // host null: fallaria a mitad del barrido con un error opaco.
            ConfiguracionConexion.Resuelta r = ConfiguracionConexion.resolver(
                    ProveedorCorreo.PERSONALIZADO, "", "", "   ", null);

            assertThat(r.imapHost()).isEqualTo("imap.gmail.com");
            assertThat(r.imapPuerto()).isEqualTo(993);
            assertThat(r.smtpHost()).isEqualTo("smtp.gmail.com");
            assertThat(r.smtpPuerto()).isEqualTo(587);
        }

        @Test
        @DisplayName("se puede cambiar solo el puerto y conservar el host propio")
        void puertoPropioConHostDelPreset() {
            ConfiguracionConexion.Resuelta r = ConfiguracionConexion.resolver(
                    ProveedorCorreo.PERSONALIZADO, "correo.midominio.com", "1143", null, null);

            assertThat(r.imapHost()).isEqualTo("correo.midominio.com");
            assertThat(r.imapPuerto()).isEqualTo(1143);
            assertThat(r.smtpHost()).isEqualTo("smtp.gmail.com");
        }

        @Test
        @DisplayName("el preset no propone endpoints propios")
        void personalizadoNoTienePreset() {
            assertThat(ProveedorCorreo.PERSONALIZADO.esPersonalizado()).isTrue();
            assertThat(ProveedorCorreo.GMAIL.esPersonalizado()).isFalse();
            assertThat(ProveedorCorreo.OUTLOOK.esPersonalizado()).isFalse();
            assertThat(ProveedorCorreo.YAHOO.esPersonalizado()).isFalse();
        }
    }

    // ── Overrides and malformed input ──────────────────────────────────

    @Nested
    @DisplayName("Un override explicito gana; uno invalido cae al preset")
    class OverridesYEntradasInvalidas {

        @Test
        @DisplayName("un host propio gana sobre el preset del proveedor")
        void hostPropioGanaSobreElPreset() {
            // El operador puede estar en un preset y apuntar a un relay propio;
            // lo que escribio es lo que quiere.
            ConfiguracionConexion.Resuelta r = ConfiguracionConexion.resolver(
                    ProveedorCorreo.OUTLOOK, "imap.exchange.midominio.com", null, null, null);

            assertThat(r.imapHost()).isEqualTo("imap.exchange.midominio.com");
            assertThat(r.imapPuerto()).isEqualTo(993); // del preset
            assertThat(r.smtpHost()).isEqualTo("smtp.office365.com");
        }

        @ParameterizedTest(name = "puerto \"{0}\" cae al preset")
        @ValueSource(strings = {"abc", "0", "-1", "65536", "99.9", "  ", ""})
        @DisplayName("un puerto no entero o fuera de rango no se propaga")
        void puertoInvalidoCaeAlPreset(String puerto) {
            ConfiguracionConexion.Resuelta r = ConfiguracionConexion.resolver(
                    ProveedorCorreo.OUTLOOK, null, puerto, null, puerto);

            assertThat(r.imapPuerto()).isEqualTo(993);
            assertThat(r.smtpPuerto()).isEqualTo(587);
        }

        @ParameterizedTest(name = "puerto \"{0}\" -> {1}")
        @CsvSource({"1, 1", "993, 993", "465, 465", "65535, 65535"})
        @DisplayName("los limites de puerto validos se respetan")
        void puertoValidoSeRespeta(String entrada, int esperado) {
            ConfiguracionConexion.Resuelta r = ConfiguracionConexion.resolver(
                    ProveedorCorreo.PERSONALIZADO, null, entrada, null, null);

            assertThat(r.imapPuerto()).isEqualTo(esperado);
        }

        @Test
        @DisplayName("el host se recorta; los espacios alrededor no son parte del host")
        void hostSeRecorta() {
            ConfiguracionConexion.Resuelta r = ConfiguracionConexion.resolver(
                    ProveedorCorreo.PERSONALIZADO, "  imap.midominio.com  ", null, null, null);

            assertThat(r.imapHost()).isEqualTo("imap.midominio.com");
        }

        @Test
        @DisplayName("ningun endpoint resuelto queda vacio, para cualquier entrada")
        void nuncaDevuelveHostVacio() {
            String[] claves = {null, "", "   ", "BASURA", "GMAIL", "OUTLOOK", "YAHOO", "PERSONALIZADO"};
            String[] hosts = {null, "", "   "};

            for (String clave : claves) {
                for (String host : hosts) {
                    ConfiguracionConexion.Resuelta r =
                            ConfiguracionConexion.desdeClave(clave, host, null, host, null);
                    assertThat(r.imapHost()).as("IMAP con clave=%s host=%s", clave, host).isNotBlank();
                    assertThat(r.smtpHost()).as("SMTP con clave=%s host=%s", clave, host).isNotBlank();
                    assertThat(r.imapPuerto()).isBetween(1, 65535);
                    assertThat(r.smtpPuerto()).isBetween(1, 65535);
                }
            }
        }
    }

    // ── SMTP props ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Las propiedades SMTP sale de la conexion resuelta")
    class PropiedadesSmtp {

        @Test
        @DisplayName("con Gmail reproducen exactamente el bloque que se usaba antes")
        void gmailReproduceElBloqueOriginal() {
            // Estos cuatro valores eran literales en los seis metodos de envio.
            // Si alguno cambia, todos los correos de una instalacion sin
            // configurar dejan de enviarse.
            java.util.Properties props = ConfiguracionConexion.resolver(
                    ProveedorCorreo.GMAIL, null, null, null, null).propiedadesSmtp();

            assertThat(props.getProperty("mail.smtp.host")).isEqualTo("smtp.gmail.com");
            assertThat(props.getProperty("mail.smtp.port")).isEqualTo("587");
            assertThat(props.getProperty("mail.smtp.auth")).isEqualTo("true");
            assertThat(props.getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
        }

        @Test
        @DisplayName("con otro proveedor viajan el host y el puerto de ese proveedor")
        void otroProveedorViajaEnLasProps() {
            java.util.Properties props = ConfiguracionConexion.resolver(
                    ProveedorCorreo.YAHOO, null, null, null, null).propiedadesSmtp();

            assertThat(props.getProperty("mail.smtp.host")).isEqualTo("smtp.mail.yahoo.com");
            assertThat(props.getProperty("mail.smtp.port")).isEqualTo("587");
            assertThat(props.getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
        }

        @Test
        @DisplayName("un puerto personalizado llega como numero, no como cadena vacia")
        void puertoPropioEnLasProps() {
            java.util.Properties props = ConfiguracionConexion.resolver(
                    ProveedorCorreo.PERSONALIZADO, null, null, null, "2525").propiedadesSmtp();

            assertThat(props.getProperty("mail.smtp.port")).isEqualTo("2525");
        }
    }

    // ── Enum surface ───────────────────────────────────────────────────

    @Test
    @DisplayName("cada preset tiene etiqueta legible para el select de ajustes")
    void etiquetasLegibles() {
        for (ProveedorCorreo p : ProveedorCorreo.values()) {
            assertThat(p.getEtiqueta()).as("etiqueta de %s", p).isNotBlank();
        }
    }

    @Test
    @DisplayName("cada preset con endpoints los tiene completos")
    void presetsConEndpointsCompletos() {
        for (ProveedorCorreo p : ProveedorCorreo.values()) {
            if (p.esPersonalizado()) {
                continue;
            }
            assertThat(p.getImapHost()).as("IMAP host de %s", p).isNotBlank();
            assertThat(p.getImapPuerto()).as("IMAP puerto de %s", p).isBetween(1, 65535);
            assertThat(p.getSmtpHost()).as("SMTP host de %s", p).isNotBlank();
            assertThat(p.getSmtpPuerto()).as("SMTP puerto de %s", p).isBetween(1, 65535);
        }
    }

    @Test
    @DisplayName("valueOf lanza sobre una clave que no existe; desdeClave no")
    void desdeClaveEsLenienteYValueOfNo() {
        // La diferencia es intencional: la API rechaza la clave desconocida con
        // 400 (SettingsResource.esProveedorConocido), pero la resolucion
        // interna nunca deja de devolver un proveedor usable.
        assertThatThrownBy(() -> ProveedorCorreo.valueOf("ICLOUD"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ProveedorCorreo.desdeClave("ICLOUD")).isEqualTo(ProveedorCorreo.GMAIL);
    }
}