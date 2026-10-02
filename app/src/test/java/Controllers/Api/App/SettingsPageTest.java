package Controllers.Api.App;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import Models.ConfiguracionMargen;
import Models.DTO.AppSettingsDTO;
import Models.DTO.BackupStatusDTO;
import org.junit.jupiter.api.Test;
import support.AppBase;

/**
 * T26 (VIEW half) suite for the consolidated settings page
 * {@code templates/pages/settings/index.html} and the LIVE
 * {@link SettingsResource} contract it consumes.
 *
 * <p>Three mandated areas:</p>
 * <ol>
 *   <li><b>Page group markers</b> - the template's {@code contenido} fragment
 *       renders every legacy field group (general-negocio, correo, loyalty,
 *       stock-umbrales, prevalidacion, backups), the Alpine wizard, the mail
 *       provider controls and both save endpoints, and NEVER mentions secret
 *       field names.</li>
 *   <li><b>PUT whitelist round-trip</b> - JSON PUT of a whitelisted field
 *       persists and reads back through the live endpoint (plus the HH:mm
 *       validation guard).</li>
 *   <li><b>backup-trigger shape</b> - POST /backup-trigger answers the
 *       {@link BackupStatusDTO} key set inside the ApiResponse envelope.</li>
 * </ol>
 *
 * <p>The provider block (2b) covers the IMAP/SMTP endpoints
 * {@code Services.EmailService} reads: round-trip through the live endpoint,
 * plus the two rejections that keep a typo from being stored as if it were a
 * configuration (unknown provider key, out-of-range port).</p>
 *
 * <p>Auth follows the repo convention (see StockAlertConfigResourceTest):
 * {@code @TestSecurity(user = "admin", roles = {"admin"})} impersonates the
 * admin principal (dev credential pair admin/admin123); unsafe methods need
 * the rest-csrf double-submit jar minted by a prior safe request.</p>
 *
 * <p>The page-marker test renders the {@code contenido} FRAGMENT directly
 * (Template.getFragment) instead of going through HTTP because the serving
 * route belongs to the API half of T26; fragment rendering exercises the exact
 * production markup without the layout shell (no csrf/bundle injectors).</p>
 */
@QuarkusTest
class SettingsPageTest extends AppBase {

    private static final String BASE = "/api/app/settings";

    @Inject
    @Location("pages/settings/index")
    Template pagina;

    // ── helpers ─────────────────────────────────────────────────────────

    /**
     * Any safe request mints the csrf-token cookie (rest-csrf default).
     * backup-status is used as the minter because it also GUARANTEES an
     * active settings row exists (BackupService.getSettings() creates it when
     * the table is empty), which the PUT endpoint requires.
     */
    private Map<String, String> csrfJar() {
        Response mint = given().when().get(BASE + "/backup-status");
        mint.then().statusCode(200);
        return new HashMap<>(mint.getCookies());
    }

    // ── 1. page group markers ───────────────────────────────────────────

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void paginaExponeGruposDeCampos() {
        AppSettingsDTO settings = new AppSettingsDTO();
        settings.setNombrePerfil("Perfil IT26");
        settings.setCorreoElectronico("it26@mercurius.local");
        settings.setNotificarRechazos(Boolean.TRUE);
        settings.setNotificarRechazosResumen(Boolean.FALSE);
        settings.setCorreoNotificaciones("avisos@mercurius.local");
        settings.setProveedorCorreo("OUTLOOK");
        settings.setImapCorreoHost("outlook.office365.com");
        settings.setImapCorreoPuerto(993);
        settings.setSmtpCorreoHost("smtp.office365.com");
        settings.setSmtpCorreoPuerto(587);
        settings.setBackupHabilitado(Boolean.TRUE);
        settings.setBackupHora("03:00");
        settings.setBackupRetencionDias(7);
        settings.setCashbackPercentage(new BigDecimal("5.00"));
        settings.setPuntosInactivityMonths(6);
        settings.setCompletedSteps(5);
        BackupStatusDTO backup = new BackupStatusDTO(null, true, false);
        ConfiguracionMargen configuracionMargen = new ConfiguracionMargen();
        configuracionMargen.setMargenBase(new BigDecimal("25.00"));
        configuracionMargen.setAjusteRefrigerado(new BigDecimal("5.00"));
        configuracionMargen.setAjusteCongelado(new BigDecimal("10.00"));

        String html = pagina.getFragment("contenido")
                .data("titulo", "Ajustes de la Aplicación")
                .data("modo", "")
                .data("settings", settings)
                .data("backup", backup)
                .data("backupLog", java.util.Collections.emptyList())
                .data("backupRutaEfectiva", "/tmp/mercurius-backups")
                .data("baseUrl", "/api/app/settings")
                .data("configuracionMargen", configuracionMargen)
                .data("margenHistorial", java.util.Collections.emptyList())
                .render();

        // every legacy field group is present and marked
        assertTrue(html.contains("data-group=\"general-negocio\""), "falta grupo general/negocio");
        assertTrue(html.contains("data-group=\"correo\""), "falta grupo correo");
        assertTrue(html.contains("data-group=\"loyalty\""), "falta grupo loyalty");
        assertTrue(html.contains("cashbackPercentage"), "falta cashback en loyalty");
        assertTrue(html.contains("puntosInactivityMonths"), "faltan puntosInactivityMonths en loyalty");
        assertTrue(html.contains("data-group=\"stock-umbrales\""), "falta grupo umbrales de stock");
        assertTrue(html.contains("data-group=\"prevalidacion\""), "falta grupo prevalidacion");
        assertTrue(html.contains("data-group=\"backups\""), "falta grupo backups");

        // save wiring: form issues the PUT payload; wizard final step shares it
        assertTrue(html.contains("id=\"ajustes-forma\""), "falta el formulario de guardado");
        assertTrue(html.contains("hx-put="), "el guardado debe emitir PUT");
        assertTrue(html.contains("/api/app/settings"), "falta la URL del contrato");
        assertTrue(html.contains("name=\"notificarRechazos\""), "falta campo whitelisted notificarRechazos");
        assertTrue(html.contains("name=\"correoNotificaciones\""), "falta campo whitelisted correoNotificaciones");
        assertTrue(html.contains("name=\"backupHora\""), "falta campo whitelisted backupHora");
        assertTrue(html.contains("name=\"backupRetencionDias\""), "falta campo whitelisted backupRetencionDias");
        assertTrue(html.contains("name=\"backupRuta\""), "falta campo whitelisted backupRuta");

        // mail provider quartet: the mailbox sweep and every sender read these,
        // so they have to be editable here and not just displayed.
        assertTrue(html.contains("name=\"proveedorCorreo\""), "falta el selector de proveedor de correo");
        assertTrue(html.contains("name=\"imapCorreoHost\""), "falta el host IMAP editable");
        assertTrue(html.contains("name=\"imapCorreoPuerto\""), "falta el puerto IMAP editable");
        assertTrue(html.contains("name=\"smtpCorreoHost\""), "falta el host SMTP editable");
        assertTrue(html.contains("name=\"smtpCorreoPuerto\""), "falta el puerto SMTP editable");
        for (String clave : new String[]{"GMAIL", "OUTLOOK", "YAHOO", "PERSONALIZADO"}) {
            assertTrue(html.contains("value=\"" + clave + "\""),
                    "falta el preset " + clave + " en el selector de proveedor");
        }

        // Alpine wizard replaces p:steps with the five legacy steps
        assertTrue(html.contains("id=\"asistente-aplicacion\""), "falta el asistente Alpine");
        assertTrue(html.contains("x-data=\"{ paso: 0 }\""), "falta el estado Alpine del asistente");
        assertTrue(html.contains("Perfil"), "falta paso Perfil");
        assertTrue(html.contains("Logo"), "falta paso Logo");
        assertTrue(html.contains("Tributacion"), "falta paso Tributacion");
        assertTrue(html.contains("Confirmacion"), "falta paso Confirmacion");
        assertTrue(html.contains("hx-include=\"#ajustes-forma\""),
                "el paso final debe enviar el mismo payload del formulario");

        // backup status card + trigger button hit the live POST endpoint
        assertTrue(html.contains("id=\"backup-status-card\""), "falta la tarjeta de estado de backups");
        assertTrue(html.contains("hx-post="), "falta el disparador de backup");
        assertTrue(html.contains("/api/app/settings/backup-trigger"), "falta la URL del trigger");

        // kit toast parity markup for client-side notifications
        assertTrue(html.contains("toast-container") || html.contains("notification"),
                "las notificaciones deben seguir la convención del kit");

        // SECRET RULE: no secret field name may ever appear in the page
        assertFalse(html.contains("contrasena"), "la página no debe mencionar campos de credenciales");
        assertFalse(html.contains("haciendaApiKey"), "la página no debe mencionar haciendaApiKey");
        assertFalse(html.contains("haciendaEncryptionKey"), "la página no debe mencionar haciendaEncryptionKey");
        assertFalse(html.contains("fidesAuthPassword"), "la página no debe mencionar fidesAuthPassword");
        assertFalse(html.contains("certificadoPassword"), "la página no debe mencionar certificadoPassword");
    }

    // ── 2. PUT whitelist round-trip (live contract) ─────────────────────

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void putWhitelistRoundTrip() {
        Map<String, String> jar = csrfJar();
        String original = given().when().get(BASE + "/backup-status")
                .jsonPath().getString("data.backupHora");

        String nueva = "04:30";
        if (nueva.equals(original)) {
            nueva = "05:30";
        }

        given()
                .cookies(jar)
                .header("X-CSRF-TOKEN", jar.get("csrf-token"))
                .contentType(ContentType.JSON)
                .body(Map.of("backupHora", nueva))
                .when().put(BASE)
                .then()
                .statusCode(200)
                .body("error", nullValue())
                .body("data.backupHora", equalTo(nueva));

        // read-back through the live GET proves persistence
        given()
                .when().get(BASE)
                .then()
                .statusCode(200)
                .body("data.backupHora", equalTo(nueva));

        // restore prior state; the whitelist cannot express null (documented
        // residue when the column started empty)
        if (original != null) {
            given()
                    .cookies(jar)
                    .header("X-CSRF-TOKEN", jar.get("csrf-token"))
                    .contentType(ContentType.JSON)
                    .body(Map.of("backupHora", original))
                    .when().put(BASE)
                    .then()
                    .statusCode(200)
                    .body("data.backupHora", equalTo(original));
        }
    }

    // ── 2b. Proveedor de correo (IMAP/SMTP) ────────────────────────────

    /**
     * The provider quartet survives the live PUT→GET round trip. Without it
     * the settings page would accept the selection and EmailService would keep
     * connecting to whatever the row said before, which is exactly the failure
     * this feature exists to remove.
     */
    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void putProveedorCorreoRoundTrip() {
        Map<String, String> jar = csrfJar();
        String host = "imap." + System.nanoTime() + ".mercurius.local";

        given()
                .cookies(jar)
                .header("X-CSRF-TOKEN", jar.get("csrf-token"))
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "proveedorCorreo", "PERSONALIZADO",
                        "imapCorreoHost", host,
                        "imapCorreoPuerto", 1143,
                        "smtpCorreoHost", "smtp.mercurius.local",
                        "smtpCorreoPuerto", 2525))
                .when().put(BASE)
                .then()
                .statusCode(200)
                .body("error", nullValue())
                .body("data.proveedorCorreo", equalTo("PERSONALIZADO"))
                .body("data.imapCorreoHost", equalTo(host))
                .body("data.imapCorreoPuerto", equalTo(1143))
                .body("data.smtpCorreoHost", equalTo("smtp.mercurius.local"))
                .body("data.smtpCorreoPuerto", equalTo(2525));

        given()
                .when().get(BASE)
                .then()
                .statusCode(200)
                .body("data.proveedorCorreo", equalTo("PERSONALIZADO"))
                .body("data.imapCorreoHost", equalTo(host))
                .body("data.imapCorreoPuerto", equalTo(1143));

        // Volver a un preset: los hosts quedan como overrides, que es lo
        // documentado, pero el preset debe quedar registrado.
        given()
                .cookies(jar)
                .header("X-CSRF-TOKEN", jar.get("csrf-token"))
                .contentType(ContentType.JSON)
                .body(Map.of("proveedorCorreo", "GMAIL"))
                .when().put(BASE)
                .then()
                .statusCode(200)
                .body("data.proveedorCorreo", equalTo("GMAIL"));
    }

    /**
     * A typo in the provider must not be stored: ProveedorCorreo.desdeClave
     * resolves anything unknown to Gmail, so accepting it would leave the
     * operator believing the mailbox sweep reads somewhere it does not.
     */
    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void putRechazaProveedorDesconocido400() {
        Map<String, String> jar = csrfJar();
        given()
                .cookies(jar)
                .header("X-CSRF-TOKEN", jar.get("csrf-token"))
                .contentType(ContentType.JSON)
                .body(Map.of("proveedorCorreo", "ICLOUD"))
                .when().put(BASE)
                .then()
                .statusCode(400)
                .body("error.code", equalTo("VALIDATION_ERROR"))
                .body("error.message", containsString("Proveedor de correo"));
    }

    /** A port outside 1..65535 (or non-numeric) is a typo, not a silent fallback. */
    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void putRechazaPuertoInvalido400() {
        Map<String, String> jar = csrfJar();
        for (String puerto : new String[]{"70000", "0", "abc"}) {
            given()
                    .cookies(jar)
                    .header("X-CSRF-TOKEN", jar.get("csrf-token"))
                    .contentType(ContentType.JSON)
                    .body(Map.of("smtpCorreoPuerto", puerto))
                    .when().put(BASE)
                    .then()
                    .statusCode(400)
                    .body("error.code", equalTo("VALIDATION_ERROR"))
                    .body("error.message", containsString("puerto SMTP"));
        }
    }

    /** Guard parity: ProgramadorTareas parses HH:mm strictly; API rejects early. */
    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void putRechazaHoraMalformada400() {
        Map<String, String> jar = csrfJar();
        given()
                .cookies(jar)
                .header("X-CSRF-TOKEN", jar.get("csrf-token"))
                .contentType(ContentType.JSON)
                .body(Map.of("backupHora", "99:99"))
                .when().put(BASE)
                .then()
                .statusCode(400)
                .body("error.code", equalTo("VALIDATION_ERROR"))
                .body("error.message", containsString("HH:mm"));
    }

    // ── 3. backup-trigger returns the BackupStatusDTO shape ─────────────

    @Test
    @TestSecurity(user = "admin", roles = {"admin"})
    void backupTriggerDevuelveFormaBackupStatusDTO() {
        // El trigger ejecuta un pg_dump real; si el binario cliente no está en
        // este entorno la llamada no puedeRespond 200 y la prueba se omite.
        support.PgDumpAvailability.assumeAvailable();

        Map<String, String> jar = csrfJar();
        given()
                .cookies(jar)
                .header("X-CSRF-TOKEN", jar.get("csrf-token"))
                .when().post(BASE + "/backup-trigger")
                .then()
                .statusCode(200)
                .body("error", nullValue())
                .body("data.size()", equalTo(3))
                .body("data.containsKey('backupUltimoEjecutado')", equalTo(true))
                .body("data.containsKey('backupHabilitado')", equalTo(true))
                .body("data.containsKey('mysqldumpResuelto')", equalTo(true))
                .body("data.backupHabilitado", instanceOf(Boolean.class))
                .body("data.mysqldumpResuelto", instanceOf(Boolean.class));
    }
}
