package Controllers.Api.App;

import Models.AppSettings;
import Models.DTO.ApiResponse;
import Models.DTO.AppSettingsDTO;
import Models.DTO.BackupStatusDTO;
import Models.Users;
import Services.AppSettingsService;
import Services.BackupService;
import Services.LoginService;
import Utils.DiffUtils;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Application-settings endpoints for the NEW Qute/HTMX app surface (/app
 * world), admin-only, mirroring the legacy JSF settings controllers as REST.
 *
 * <p><b>Secrets never cross this resource.</b> The manual mapper below builds
 * {@link AppSettingsDTO}, which by design omits contrasenaCorreo,
 * certificado/certificadoPassword, haciendaApiKey, haciendaEncryptionKey and
 * fidesAuthPassword. Identity/Hacienda/Fides configuration keeps flowing
 * through the legacy entity-bound controllers (SettingsController), not here.</p>
 *
 * <p>{@code PUT ""} updates a whitelist of operational fields only
 * (rejection notifications + backup scheduling). Its semantics mirror
 * {@code SettingsDirController.updateSelectedSettings()} exactly:
 * DiffUtils snapshot → {@code settingsService.update(entity)} → audit alert
 * "Configuración actualizada" with antes/despues snapshots.</p>
 *
 * <p>{@code GET /backup-status} and {@code POST /backup-trigger} mirror
 * {@code BackupController}: the trigger re-checks admin (ported against
 * SecurityIdentity because SessionController is a @SessionScoped JSF-bound
 * bean and must not be injected into JAX-RS resources), invokes the very same
 * {@link BackupService#ejecutarBackup()} path and
 * reports with the legacy Spanish texts. Only existing public
 * {@code BackupService} methods are called — that file is being reworked in a
 * parallel lane (mysqldump→pg_dump) and is not touched here.</p>
 *
 * <p>The {@code @RolesAllowed} gate is dormant until the form-cookie auth
 * block is enabled in application.properties (see {@link AppAuthResource}).</p>
 *
 * <p>All responses follow the {@link ApiResponse} envelope conventions.</p>
 */
@Path("/api/app/settings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("admin")
@Tag(name = "App - Ajustes")
public class SettingsResource {

    private static final Logger LOG = Logger.getLogger(SettingsResource.class.getName());

    @Nonnull
    @Inject
    AppSettingsService settingsService;

    @Nonnull
    @Inject
    BackupService backupService;

    
    @Nonnull
    @Inject
    LoginService loginService;

    @Inject
    @Nonnull
    SecurityIdentity securityIdentity;

    /**
     * Current operational settings mapped to the secret-free DTO.
     * 404 when no row has estatus=true (GET never creates rows).
     */
    @GET
    @Operation(summary = "Current application settings (secrets omitted)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Missing admin role"),
        @APIResponse(responseCode = "404", description = "No active settings row"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response current() {
        try {
            AppSettings settings = settingsService.returnCurrent();
            if (settings == null) {
                return noActiveSettings();
            }
            return Response.ok(ApiResponse.ok(toDTO(settings))).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error reading app settings", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error consultando la configuración"))
                    .build();
        }
    }

    /**
     * Update whitelisted operational fields on the current settings row.
     * Fields left null are not modified. Semantics mirror
     * SettingsDirController.updateSelectedSettings(): snapshot antes → update →
     * audit alert with antes/despues.
     */
    @PUT
    @Transactional
    @Operation(summary = "Update whitelisted operational settings (notifications + backups)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Settings updated"),
        @APIResponse(responseCode = "400", description = "Validation error"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Missing admin role"),
        @APIResponse(responseCode = "404", description = "No active settings row"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response update(@Nullable OperationalSettingsRequest request) {
        try {
            if (request == null) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(ApiResponse.error("VALIDATION_ERROR",
                                "El cuerpo de la petición es requerido."))
                        .build();
            }
            // backupHora feeds ProgramadorTareas, which parses HH:mm strictly;
            // reject malformed values at the API boundary instead of letting
            // the scheduler fail later.
            if (request.backupHora != null && !request.backupHora.matches("^([01]\\d|2[0-3]):[0-5]\\d$")) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(ApiResponse.error("VALIDATION_ERROR",
                                "La hora de backup debe tener formato HH:mm (por ejemplo 03:00)"))
                        .build();
            }

            AppSettings settings = settingsService.returnCurrent();
            if (settings == null) {
                return noActiveSettings();
            }

            // Parity with SettingsDirController.updateSelectedSettings():
            // snapshot before, merge, snapshot after, alert with both sides.
            String antes = DiffUtils.snapshotEntity(settings);

            if (request.notificarRechazos != null) {
                settings.setNotificarRechazos(request.notificarRechazos);
            }
            if (request.correoNotificaciones != null) {
                settings.setCorreoNotificaciones(request.correoNotificaciones);
            }
            if (request.notificarRechazosResumen != null) {
                settings.setNotificarRechazosResumen(request.notificarRechazosResumen);
            }
            if (request.backupHabilitado != null) {
                settings.setBackupHabilitado(request.backupHabilitado);
            }
            if (request.backupHora != null) {
                settings.setBackupHora(request.backupHora);
            }
            if (request.backupRetencionDias != null) {
                settings.setBackupRetencionDias(request.backupRetencionDias);
            }
            if (request.backupRuta != null) {
                settings.setBackupRuta(request.backupRuta);
            }

            settingsService.update(settings);

                        LOG.info("Se ha actualizado la configuración: " + settings.getNombrePerfil() + " | user=" + String.valueOf(currentUserOrNull()) + " | source=" + "SettingsResource.update()" + " | antes=" + String.valueOf(antes) + " | despues=" + String.valueOf(DiffUtils.snapshotEntity(settings)));

            return Response.ok(ApiResponse.ok(toDTO(settings))).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error updating app settings", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error actualizando la configuración"))
                    .build();
        }
    }

    /**
     * Public status of the backup subsystem, built from BackupService's public
     * surface ({@code getSettings()}). See {@link #toBackupStatusDTO} for the
     * mysqldumpResuelto caveat.
     */
    @GET
    @Path("/backup-status")
    @Operation(summary = "Backup subsystem status (last run, enabled flag)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Missing admin role"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response backupStatus() {
        try {
            // Same lookup BackupController.loadSettings() uses; may create the
            // row when the table is empty — identical to legacy behavior.
            AppSettings settings = backupService.getSettings();
            return Response.ok(ApiResponse.ok(toBackupStatusDTO(settings))).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error reading backup status", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error consultando el estado de los respaldos"))
                    .build();
        }
    }

    /**
     * Trigger a backup now — same path as
     * {@code BackupController.executeBackupNow()}: inline admin re-check with
     * the legacy "Acceso Denegado" texts, then ejecutarBackup(). Returns the
     * refreshed status so callers see the new backupUltimoEjecutado.
     */
    @POST
    @Path("/backup-trigger")
    @Consumes(MediaType.WILDCARD)
    @Operation(summary = "Trigger a database backup now (same path as legacy executeBackupNow)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Backup completed; returns refreshed status"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Missing admin role"),
        @APIResponse(responseCode = "500", description = "Backup failed or internal server error")
    })
    public Response backupTrigger() {
        try {
            // Guard parity with executeBackupNow(): currentSession.isAdmin().
            // Ported against SecurityIdentity (no FacesContext, no session bean).
            if (!isAdmin()) {
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(ApiResponse.error("ACCESS_DENIED",
                                "Se requieren permisos de administrador"))
                        .build();
            }

            boolean success = backupService.ejecutarBackup();
            if (!success) {
                // Legacy FacesMessage text kept verbatim; the details live in
                // the system alerts, exactly like the JSF flow tells the user.
                return Response.serverError()
                        .entity(ApiResponse.error("BACKUP_FAILED",
                                "El backup falló. Revise las alertas del sistema."))
                        .build();
            }

            // Legacy reloads settings + list after running; the REST response
            // carries the refreshed status instead of a file listing.
            return Response.ok(ApiResponse.ok(toBackupStatusDTO(backupService.getSettings()))).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error triggering backup", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error ejecutando el backup"))
                    .build();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static Response noActiveSettings() {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(ApiResponse.error("NOT_FOUND", "No hay una configuración activa del sistema"))
                .build();
    }

    /** Mirror of SessionController.isValid()'s SecurityIdentity fallback branch. */
    private boolean sessionValid() {
        return securityIdentity != null && !securityIdentity.isAnonymous();
    }

    /** Mirror of SessionController.isAdmin()'s SecurityIdentity fallback branch. */
    private boolean isAdmin() {
        return sessionValid() && securityIdentity.hasRole("admin");
    }

    /**
     * Resolves the authenticated Users row for audit attribution, mirroring
     * legacy currentSession.getCurrentUser(); null when anonymous/unknown.
     */
    @Nullable
    private Users currentUserOrNull() {
        if (!sessionValid() || securityIdentity.getPrincipal() == null) {
            return null;
        }
        return loginService.findByUsername(securityIdentity.getPrincipal().getName());
    }

    /**
     * Manual mapper: AppSettings → AppSettingsDTO, field by field. Secret
     * fields (contrasenaCorreo, certificado, certificadoPassword,
     * haciendaApiKey, haciendaEncryptionKey, fidesAuthPassword) are NEVER read
     * here — the DTO does not carry them.
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
     * Maps the backup status from what BackupService publicly exposes.
     *
     * <p>Caveat on {@code mysqldumpResuelto}: the DTO documents it as the
     * outcome of {@code BackupService.resolvePgDump()}, but that method is
     * private and no public accessor exists. Since this lane must not edit
     * BackupService (parallel pg_dump migration), the field is reported as
     * {@code false} — the fail-safe value: an admin who sees "not resolved"
     * investigates, whereas a wrong "resolved" would hide broken backups.
     * Revisit once BackupService exposes resolution state publicly.</p>
     */
    private static BackupStatusDTO toBackupStatusDTO(@Nullable AppSettings settings) {
        if (settings == null) {
            return new BackupStatusDTO(null, false, false);
        }
        return new BackupStatusDTO(
                settings.getBackupUltimoEjecutado(),
                Boolean.TRUE.equals(settings.getBackupHabilitado()),
                false); // resolvePgDump() es privado: valor conservativo, ver nota
    }

    /** PUT payload; every field optional, null = leave unchanged. */
    public static class OperationalSettingsRequest {
        @Nullable
        public Boolean notificarRechazos;
        @Nullable
        public String correoNotificaciones;
        @Nullable
        public Boolean notificarRechazosResumen;
        @Nullable
        public Boolean backupHabilitado;
        @Nullable
        public String backupHora; // HH:mm
        @Nullable
        public Integer backupRetencionDias;
        @Nullable
        public String backupRuta;
    }
}
