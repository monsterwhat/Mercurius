package Controllers.Api.App;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import Models.AppSettings;
import Models.DTO.AppSettingsDTO;
import Models.DTO.BackupStatusDTO;
import Services.AppSettingsService;
import Services.BackupService;

/**
 * HTML pages of the consolidated application-settings module for the NEW
 * Qute/HTMX app surface:
 *
 * <ul>
 *   <li>{@code GET /app/aplicacion} — first-run wizard + AppSettings
 *       (legacy {@code secured/pages/Aplicacion/index.xhtml}).</li>
 *   <li>{@code GET /app/backups} — backup admin (legacy
 *       {@code secured/pages/Ajustes/Backups/index.xhtml}).</li>
 * </ul>
 *
 * Both routes render the same consolidated template
 * {@code templates/pages/settings/index.html} (T26 VIEW half), whose data
 * contract is {@code titulo}, {@code settings} (AppSettingsDTO),
 * {@code backup} (BackupStatusDTO) and {@code baseUrl}. Reads/actions live in
 * the JSON twin {@link SettingsResource} ({@code /api/app/settings}); this
 * class only renders HTML, mirroring the ArticulosPagesResource /
 * ArticuloResource split.
 */
@Path("/app")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"admin"})
public class SettingsPagesResource {

    private static final Logger LOG = Logger.getLogger(SettingsPagesResource.class.getName());

    private static final String BASE_URL = "/api/app/settings";

    @Inject
    @Nonnull
    AppSettingsService settingsService;

    @Inject
    @Nonnull
    BackupService backupService;

    @Inject
    @Nonnull
    @Location("pages/settings/index")
    Template page;

    @GET
    @Path("/aplicacion")
    public Response aplicacion() {
        return render("Ajustes de la Aplicación", "aplicacion");
    }

    @GET
    @Path("/backups")
    public Response backups() {
        return render("Respaldos de Base de Datos", "backups");
    }

    private Response render(@Nonnull String titulo, @Nonnull String modo) {
        try {
            AppSettings settings = backupService.getSettings();
            Map<String, Object> model = new LinkedHashMap<>();
            model.put("titulo", titulo);
            model.put("modo", modo);
            model.put("settings", toDTO(settings));
            model.put("backup", toBackupStatusDTO(settings));
            model.put("backupLog", toBackupLog(backupService.listarBackups()));
            model.put("baseUrl", BASE_URL);
            TemplateInstance instance = page.instance();
            model.forEach(instance::data);
            return Response.ok(instance.render())
                    .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error renderizando la página de ajustes", e);
            return Response.serverError()
                    .entity(Models.DTO.ApiResponse.error("INTERNAL_ERROR",
                            "No se pudieron cargar los ajustes de la aplicación"))
                    .build();
        }
    }

    /**
     * Secret-free DTO mapping, 1:1 with {@code SettingsResource.toDTO()}.
     * Credential/key fields (correo password, certificado .p12, Hacienda
     * API key/encryption key, Fides auth password) are never carried.
     */
    private static AppSettingsDTO toDTO(@Nonnull AppSettings s) {
        return new AppSettingsDTO(
                s.getId(),
                s.getNombrePerfil(),
                s.getLogo(),
                s.getLogoMimeType(),
                s.getCorreoElectronico(),
                s.getNombre(),
                s.getTipoIdentificacion(),
                s.getIdentificacion(),
                s.getNombreNegocio(),
                s.getProvincia(),
                s.getCanton(),
                s.getDistrito(),
                s.getBarrio(),
                s.getDireccionCompleta(),
                s.getCodigoPais(),
                s.getTelefono(),
                s.getCodigoPaisFax(),
                s.getTelefonoFax(),
                s.getCorreoElectronicoTributacion(),
                s.getCorreoElectronicoTributacion2(),
                s.getCorreoElectronicoTributacion3(),
                s.getCorreoElectronicoTributacion4(),
                s.getRazonSocial(),
                s.getProvedor(),
                s.getCodigoActividad(),
                s.getEstatus(),
                s.getCompletedSteps(),
                s.getCashbackPercentage(),
                s.getUltimoConsecutivo(),
                s.getCodigoSucursal(),
                s.getCodigoTerminal(),
                s.getTipoDocumento(),
                s.getPuntosInactivityMonths(),
                s.getHaciendaEnvironment(),
                s.getHaciendaTokenExpiry(),
                s.getNotificarRechazos(),
                s.getCorreoNotificaciones(),
                s.getNotificarRechazosResumen(),
                s.getBackupHabilitado(),
                s.getBackupHora(),
                s.getBackupRetencionDias(),
                s.getBackupRuta(),
                s.getBackupUltimoEjecutado(),
                s.getHaciendaCallbackUrl(),
                s.getUseFides(),
                s.getFidesApiUrl(),
                s.getFidesAuthEmail(),
                s.getFidesTenantId(),
                s.getFidesUserId());
    }

    /**
     * Backup status from what BackupService publicly exposes; conservative
     * {@code mysqldumpResuelto=false} (resolvePgDump() is private), matching
     * {@code SettingsResource.toBackupStatusDTO()}.
     */
    private static BackupStatusDTO toBackupStatusDTO(@Nullable AppSettings settings) {
        if (settings == null) {
            return new BackupStatusDTO(null, false, false);
        }
        return new BackupStatusDTO(
                settings.getBackupUltimoEjecutado(),
                Boolean.TRUE.equals(settings.getBackupHabilitado()),
                false);
    }

    /**
     * BackupService.listarBackups() returns "filename|size" strings; split
     * them into structured rows for the template's log table.
     */
    private static java.util.List<Map<String, String>> toBackupLog(
            @Nonnull java.util.List<String> entries) {
        java.util.List<Map<String, String>> rows = new java.util.ArrayList<>();
        for (String entry : entries) {
            Map<String, String> row = new LinkedHashMap<>();
            int sep = entry.indexOf('|');
            if (sep >= 0) {
                row.put("archivo", entry.substring(0, sep));
                row.put("tamano", entry.substring(sep + 1));
            } else {
                row.put("archivo", entry);
                row.put("tamano", null);
            }
            rows.add(row);
        }
        return rows;
    }
}
