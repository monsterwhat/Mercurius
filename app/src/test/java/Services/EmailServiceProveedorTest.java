package Services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import Models.ConfiguracionAplicacion;
import Models.Correos.ConfiguracionConexion;
import Models.Correos.ProveedorCorreo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The provider columns must survive the database, not just the resolver.
 *
 * <p>{@link ProveedorCorreoTest} pins the mapping from a provider to its
 * endpoints — a pure function. This suite covers the half that a unit test
 * cannot reach: that the four new columns actually persist on the settings row
 * and read back, and that {@link EmailService#conexionActual()} is TOTAL — it
 * returns usable endpoints no matter what the row holds, because a throw there
 * aborts every send (and the MicroProfile fallback converts it into a
 * "could not send" with no visible cause).</p>
 *
 * <p>Reads go through the live service against the test database. No mail
 * server is contacted: the assertion is on the endpoints that WOULD be handed
 * to {@code store.connect(...)} / the SMTP props.</p>
 *
 * <p>Rows created here are deleted in {@code @AfterEach} so the shared test
 * database does not inherit a fake provider; each uses {@code estatus=false} so
 * it can never become the profile {@code returnCurrent()} hands to production
 * paths during another suite.</p>
 */
@QuarkusTest
@DisplayName("EmailService: los endpoints vienen de la configuracion persistida")
class EmailServiceProveedorTest {

    @Inject
    EmailService emailService;

    @Inject
    AppSettingsService appSettingsService;

    private final List<ConfiguracionAplicacion> creados = new ArrayList<>();

    @AfterEach
    void limpiar() {
        for (ConfiguracionAplicacion c : creados) {
            try {
                ConfiguracionAplicacion enBase = appSettingsService.find(c.getId());
                if (enBase != null) {
                    appSettingsService.delete(enBase);
                }
            } catch (RuntimeException e) {
                // Best-effort: la fila pudo no commitearse (otra suite con
                // rollback). Que el cleanup falle no invalida las aserciones.
            }
        }
        creados.clear();
    }

    private ConfiguracionAplicacion crear(String nombrePerfil, String proveedor,
                                         String imapHost, Integer imapPuerto,
                                         String smtpHost, Integer smtpPuerto) {
        ConfiguracionAplicacion c = new ConfiguracionAplicacion();
        c.setNombrePerfil(nombrePerfil);
        c.setEstatus(Boolean.FALSE); // nunca compite con el perfil activo
        c.setProveedorCorreo(proveedor);
        c.setImapCorreoHost(imapHost);
        c.setImapCorreoPuerto(imapPuerto);
        c.setSmtpCorreoHost(smtpHost);
        c.setSmtpCorreoPuerto(smtpPuerto);
        appSettingsService.create(c);
        creados.add(c);
        return c;
    }

    /** Resolves exactly the way the service does: from the PERSISTED row. */
    private ConfiguracionConexion.Resuelta resolverDesdeFila(ConfiguracionAplicacion id) {
        ConfiguracionAplicacion leido = appSettingsService.find(id.getId());
        assertThat(leido).as("la fila debe leerse de vuelta").isNotNull();
        return ConfiguracionConexion.resolver(
                ProveedorCorreo.desdeClave(leido.getProveedorCorreo()),
                leido.getImapCorreoHost(),
                leido.getImapCorreoPuerto() != null ? String.valueOf(leido.getImapCorreoPuerto()) : null,
                leido.getSmtpCorreoHost(),
                leido.getSmtpCorreoPuerto() != null ? String.valueOf(leido.getSmtpCorreoPuerto()) : null);
    }

    @Test
    @DisplayName("una fila nueva tiene las columnas de proveedor en null (no inventadas)")
    void filaNuevaNoInventaEndpoints() {
        // El punto de partida de toda migracion: una fila guardada antes de que
        // existieran las columnas debe quedar con ellas en null, no con un
        // host escrito por el cliente de correo. El preset lo decide la
        // resolucion, no el almacenamiento.
        ConfiguracionAplicacion c = crear("IT Proveedor Vacio", null, null, null, null, null);
        ConfiguracionAplicacion leido = appSettingsService.find(c.getId());

        assertThat(leido.getProveedorCorreo()).isNull();
        assertThat(leido.getImapCorreoHost()).isNull();
        assertThat(leido.getImapCorreoPuerto()).isNull();
        assertThat(leido.getSmtpCorreoHost()).isNull();
        assertThat(leido.getSmtpCorreoPuerto()).isNull();
    }

    @Test
    @DisplayName("sin datos de proveedor, la fila resuelve a los endpoints de Gmail de siempre")
    void perfilSinConfiguracionUsaGmail() {
        ConfiguracionConexion.Resuelta r = resolverDesdeFila(
                crear("IT Proveedor Gmail", null, null, null, null, null));

        assertThat(r.imapHost()).isEqualTo("imap.gmail.com");
        assertThat(r.imapPuerto()).isEqualTo(993);
        assertThat(r.smtpHost()).isEqualTo("smtp.gmail.com");
        assertThat(r.smtpPuerto()).isEqualTo(587);
    }

    @Test
    @DisplayName("Outlook persiste y resuelve a outlook.office365.com / smtp.office365.com")
    void perfilConOutlook() {
        ConfiguracionConexion.Resuelta r = resolverDesdeFila(
                crear("IT Proveedor Outlook", "OUTLOOK", null, null, null, null));

        assertThat(r.imapHost()).isEqualTo("outlook.office365.com");
        assertThat(r.imapPuerto()).isEqualTo(993);
        assertThat(r.smtpHost()).isEqualTo("smtp.office365.com");
        assertThat(r.smtpPuerto()).isEqualTo(587);
    }

    @Test
    @DisplayName("Yahoo persiste y resuelve a imap.mail.yahoo.com / smtp.mail.yahoo.com")
    void perfilConYahoo() {
        ConfiguracionConexion.Resuelta r = resolverDesdeFila(
                crear("IT Proveedor Yahoo", "YAHOO", null, null, null, null));

        assertThat(r.imapHost()).isEqualTo("imap.mail.yahoo.com");
        assertThat(r.smtpHost()).isEqualTo("smtp.mail.yahoo.com");
    }

    @Test
    @DisplayName("un host y puerto propios sobreviven el viaje a la base de datos")
    void perfilPersonalizadoConOverrides() {
        ConfiguracionConexion.Resuelta r = resolverDesdeFila(
                crear("IT Proveedor Custom", "PERSONALIZADO",
                        "correo.midominio.local", 1143,
                        "smtp.midominio.local", 2525));

        assertThat(r.imapHost()).isEqualTo("correo.midominio.local");
        assertThat(r.imapPuerto()).isEqualTo(1143);
        assertThat(r.smtpHost()).isEqualTo("smtp.midominio.local");
        assertThat(r.smtpPuerto()).isEqualTo(2525);
    }

    @Test
    @DisplayName("conexionActual() nunca lanza y siempre entrega endpoints utilizables")
    void conexionActualEsTotal() {
        // Se lee el perfil ACTIVO, que esta suite no controla; por eso la
        // asercion es sobre el contrato (nunca null, puerto en rango), no sobre
        // un proveedor concreto.
        assertDoesNotThrow(() -> emailService.conexionActual());

        ConfiguracionConexion.Resuelta r = emailService.conexionActual();
        assertThat(r.imapHost()).isNotBlank();
        assertThat(r.smtpHost()).isNotBlank();
        assertThat(r.imapPuerto()).isBetween(1, 65535);
        assertThat(r.smtpPuerto()).isBetween(1, 65535);
    }

    @Test
    @DisplayName("las props SMTP del servicio salen de la conexion resuelta")
    void propsSmtpDelServicio() {
        ConfiguracionConexion.Resuelta r = emailService.conexionActual();
        java.util.Properties props = r.propiedadesSmtp();

        assertThat(props.getProperty("mail.smtp.host")).isEqualTo(r.smtpHost());
        assertThat(props.getProperty("mail.smtp.port")).isEqualTo(String.valueOf(r.smtpPuerto()));
        assertThat(props.getProperty("mail.smtp.auth")).isEqualTo("true");
        assertThat(props.getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
    }
}