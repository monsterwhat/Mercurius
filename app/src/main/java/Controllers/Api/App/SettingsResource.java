package Controllers.Api.App;

import Models.ConfiguracionAplicacion;
import Models.Correos.ProveedorCorreo;
import Models.DTO.ApiResponse;
import Models.DTO.AppSettingsDTO;
import Models.DTO.BackupStatusDTO;
import Models.Sucursal;
import Models.Usuarios;
import Services.AppSettingsService;
import Services.BackupService;
import Services.LoginService;
import Services.SucursalService;
import Utils.DiffUtils;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jboss.logging.Logger;
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
 * <p><b>Registro de sucursales y terminales</b> ({@code /sucursales},
 * {@code /sucursales/seleccionar}, {@code /sucursales/activo}): el asistente
 * inicial registra los puntos de venta y elige el que opera. Seleccionar copia
 * el par a codigoSucursal/codigoTerminal de la configuracion global, que es de
 * donde la emision arma el NumeroConsecutivo; el contador global no se toca.</p>
 *
 * <p>All responses follow the {@link ApiResponse} envelope conventions.</p>
 */
@Path("/api/app/settings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("admin")
@Tag(name = "App - Ajustes")
public class SettingsResource {

    private static final Logger LOG = Logger.getLogger(SettingsResource.class);

    @Nonnull
    @Inject
    AppSettingsService settingsService;

    @Nonnull
    @Inject
    BackupService backupService;

    @Nonnull
    @Inject
    LoginService loginService;

    /** Registro de sucursales y terminales del asistente inicial. */
    @Nonnull
    @Inject
    SucursalService sucursalService;

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
            ConfiguracionAplicacion settings = settingsService.returnCurrent();
            if (settings == null) {
                return noActiveSettings();
            }
            return Response.ok(ApiResponse.ok(toDTO(settings))).build();
        } catch (Exception e) {
            LOG.warn("Error reading app settings", e);
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

            ConfiguracionAplicacion settings = settingsService.returnCurrent();
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
            if (request.proveedorSistemas != null) {
                String valorProveedor = request.proveedorSistemas.trim();
                // El XSD oficial limita ProveedorSistemas a 20 caracteres; la
                // DGT espera la identificacion del proveedor de sistemas.
                if (!valorProveedor.isEmpty() && valorProveedor.length() > 20) {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(ApiResponse.error("VALIDATION_ERROR",
                                    "El proveedor de sistemas no puede superar 20 caracteres."))
                            .build();
                }
                settings.setProvedor(valorProveedor.isEmpty() ? null : valorProveedor);
            }

            // ── Proveedor de correo (IMAP/SMTP) ──
            // Se guarda por triplete (clave + host + puerto) porque la UI
            // manda el host/puerto editable siempre: si el operador cambia de
            // preset, los valores del preset anterior quedarian como overrides
            // y el "nuevo" proveedor apuntaria al host viejo. El endpoint
            // resuelve los presets en ConfiguracionConexion.
            if (request.proveedorCorreo != null) {
                String clave = request.proveedorCorreo.trim().toUpperCase(Locale.ROOT);
                if (!esProveedorConocido(clave)) {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(ApiResponse.error("VALIDATION_ERROR",
                                    "Proveedor de correo no reconocido. Use GMAIL, OUTLOOK, YAHOO o PERSONALIZADO."))
                            .build();
                }
                settings.setProveedorCorreo(clave);
            }
            if (request.imapCorreoHost != null) {
                String host = request.imapCorreoHost.trim();
                if (host.length() > 200 || !host.matches("[A-Za-z0-9._-]*")) {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(ApiResponse.error("VALIDATION_ERROR",
                                    "El host IMAP solo admite letras, digitos, punto, guion y guion bajo."))
                            .build();
                }
                settings.setImapCorreoHost(host.isEmpty() ? null : host);
            }
            if (request.imapCorreoPuerto != null) {
                Integer puerto = puertoValido(request.imapCorreoPuerto);
                if (puerto == null) {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(ApiResponse.error("VALIDATION_ERROR",
                                    "El puerto IMAP debe ser un numero entre 1 y 65535."))
                            .build();
                }
                settings.setImapCorreoPuerto(puerto);
            }
            if (request.smtpCorreoHost != null) {
                String host = request.smtpCorreoHost.trim();
                if (host.length() > 200 || !host.matches("[A-Za-z0-9._-]*")) {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(ApiResponse.error("VALIDATION_ERROR",
                                    "El host SMTP solo admite letras, digitos, punto, guion y guion bajo."))
                            .build();
                }
                settings.setSmtpCorreoHost(host.isEmpty() ? null : host);
            }
            if (request.smtpCorreoPuerto != null) {
                Integer puerto = puertoValido(request.smtpCorreoPuerto);
                if (puerto == null) {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(ApiResponse.error("VALIDATION_ERROR",
                                    "El puerto SMTP debe ser un numero entre 1 y 65535."))
                            .build();
                }
                settings.setSmtpCorreoPuerto(puerto);
            }

            settingsService.update(settings);

                        LOG.info("Se ha actualizado la configuración: " + settings.getNombrePerfil() + " | user=" + String.valueOf(currentUserOrNull()) + " | source=" + "SettingsResource.update()" + " | antes=" + String.valueOf(antes) + " | despues=" + String.valueOf(DiffUtils.snapshotEntity(settings)));

            return Response.ok(ApiResponse.ok(toDTO(settings))).build();
        } catch (Exception e) {
            LOG.warn("Error updating app settings", e);
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
            ConfiguracionAplicacion settings = backupService.getSettings();
            return Response.ok(ApiResponse.ok(toBackupStatusDTO(settings))).build();
        } catch (Exception e) {
            LOG.warn("Error reading backup status", e);
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
            LOG.warn("Error triggering backup", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error ejecutando el backup"))
                    .build();
        }
    }

    @GET
    @Path("/backup-descargar")
    @Produces("application/gzip")
    @Operation(summary = "Download a backup file (admin, traversal-guarded)")
    public Response backupDescargar(@QueryParam("archivo") @Nullable String archivo) {
        try {
            if (archivo == null || archivo.isBlank()) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            String nombre = archivo.trim();
            if (!nombre.endsWith(".sql.gz") || nombre.contains("/") || nombre.contains("\\")) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            java.nio.file.Path base = Paths.get(backupService.rutaEfectiva()).toAbsolutePath().normalize();
            java.nio.file.Path file = base.resolve(nombre).normalize();
            if (!file.startsWith(base) || !Files.isRegularFile(file)) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            return Response.ok(file.toFile())
                    .type("application/gzip")
                    .header("Content-Disposition", "attachment; filename=\"" + nombre + "\"")
                    .build();
        } catch (RuntimeException e) {
            LOG.warn("Error descargando respaldo", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error descargando el respaldo"))
                    .build();
        }
    }

    // ── Registro de sucursales y terminales ──────────────────────────────

    /**
     * GET /sucursales — catalogo de puntos de venta del asistente inicial.
     * Cada fila es un par (sucursal, terminal) con el ancho del consecutivo
     * (3 y 5 digitos) y su estado activo; {@code seleccionada} marca la fila
     * que hoy escribio {@code GET /} en codigoSucursal/codigoTerminal.
     */
    @GET
    @Path("/sucursales")
    @Operation(summary = "Branch/terminal registry used by the initial setup")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Registry listing"),
        @APIResponse(responseCode = "403", description = "Missing admin role"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response sucursales() {
        try {
            sucursalService.asegurarPorDefecto();
            return Response.ok(ApiResponse.ok(toSucursales(sucursalService.seleccionada()))).build();
        } catch (RuntimeException e) {
            LOG.warn("Error leyendo el registro de sucursales", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error consultando las sucursales"))
                    .build();
        }
    }

    /**
     * POST /sucursales — registra un punto de venta nuevo (form-encoded, el
     * mismo canal que el resto del asistente). El par no puede repetirse.
     */
    @POST
    @Path("/sucursales")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Operation(summary = "Register a branch/terminal pair in the registry")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Registered; now the selected point of sale"),
        @APIResponse(responseCode = "400", description = "Invalid code or duplicate pair"),
        @APIResponse(responseCode = "403", description = "Missing admin role"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response registrarSucursal(@FormParam("codigoSucursal") @Nullable String codigoSucursal,
                                      @FormParam("nombreSucursal") @Nullable String nombreSucursal,
                                      @FormParam("codigoTerminal") @Nullable String codigoTerminal,
                                      @FormParam("nombreTerminal") @Nullable String nombreTerminal) {
        try {
            Sucursal registrada = sucursalService.registrar(
                    codigoSucursal, nombreSucursal, codigoTerminal, nombreTerminal);
            // Queda lista para operar: registrar y seleccionar son el mismo
            // gesto desde el asistente.
            sucursalService.seleccionar(registrada.getId());
            LOG.info("Configuración actualizada: punto de venta registrado y seleccionado | user="
                    + String.valueOf(currentUserOrNull()) + " | source=SettingsResource.registrarSucursal()"
                    + " | despues=" + String.valueOf(DiffUtils.snapshotEntity(registrada)));
            return Response.ok(ApiResponse.ok(toSucursales(sucursalService.seleccionada()))).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("VALIDATION_ERROR", e.getMessage()))
                    .build();
        } catch (RuntimeException e) {
            LOG.warn("Error registrando la sucursal", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error registrando la sucursal"))
                    .build();
        }
    }

    /**
     * POST /sucursales/seleccionar — deja ese punto de venta como el que
     * opera: copia su par a codigoSucursal/codigoTerminal, que es lo que la
     * emision lee para armar el NumeroConsecutivo. El contador global no se
     * toca: cada (sucursal, terminal, tipo) ya tiene su propia secuencia.
     */
    @POST
    @Path("/sucursales/seleccionar")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Operation(summary = "Select the branch/terminal that issues documents")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Selected"),
        @APIResponse(responseCode = "400", description = "Unknown or inactive point of sale"),
        @APIResponse(responseCode = "403", description = "Missing admin role"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response seleccionarSucursal(@FormParam("id") @Nullable String id) {
        try {
            Long registro = parseId(id);
            Sucursal seleccionada = sucursalService.seleccionar(registro);
            LOG.info("Configuración actualizada: sucursal seleccionada | user="
                    + String.valueOf(currentUserOrNull()) + " | source=SettingsResource.seleccionarSucursal()"
                    + " | despues=" + String.valueOf(DiffUtils.snapshotEntity(seleccionada)));
            return Response.ok(ApiResponse.ok(toSucursales(seleccionada))).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("VALIDATION_ERROR", e.getMessage()))
                    .build();
        } catch (RuntimeException e) {
            LOG.warn("Error seleccionando la sucursal", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error seleccionando la sucursal"))
                    .build();
        }
    }

    /** Activa o desactiva una fila del registro sin mover la seleccion. */
    @POST
    @Path("/sucursales/activo")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Operation(summary = "Activate/deactivate a registry row")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Updated"),
        @APIResponse(responseCode = "400", description = "Unknown row"),
        @APIResponse(responseCode = "403", description = "Missing admin role"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response cambiarActivoSucursal(@FormParam("id") @Nullable String id,
                                          @FormParam("activo") @Nullable String activo) {
        try {
            sucursalService.cambiarActivo(parseId(id), "true".equalsIgnoreCase(activo));
            return Response.ok(ApiResponse.ok(toSucursales(sucursalService.seleccionada()))).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("VALIDATION_ERROR", e.getMessage()))
                    .build();
        } catch (RuntimeException e) {
            LOG.warn("Error cambiando el estado de la sucursal", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error cambiando el estado de la sucursal"))
                    .build();
        }
    }

    /**
     * Proyeccion del registro para el asistente: la lista de puntos de venta y
     * cual esta seleccionado, ya resuelto contra la configuracion global.
     */
    private @Nonnull Map<String, Object> toSucursales(@Nullable Sucursal seleccionada) {
        List<Map<String, Object>> filas = new ArrayList<>();
        Long idSeleccionada = seleccionada == null ? null : seleccionada.getId();
        for (Sucursal fila : sucursalService.listar()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", fila.getId());
            item.put("codigoSucursal", fila.getCodigoSucursal());
            item.put("nombreSucursal", fila.getNombreSucursal());
            item.put("codigoTerminal", fila.getCodigoTerminal());
            item.put("nombreTerminal", fila.getNombreTerminal());
            item.put("activo", Boolean.TRUE.equals(fila.getActivo()));
            item.put("seleccionada", idSeleccionada != null && idSeleccionada.equals(fila.getId()));
            filas.add(item);
        }
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("sucursales", filas);
        cuerpo.put("seleccionada", idSeleccionada);
        return cuerpo;
    }

    @Nullable
    private static Long parseId(@Nullable String id) {
        if (id == null || id.isBlank() || !id.trim().matches("[0-9]+")) {
            throw new IllegalArgumentException("Debe indicar el punto de venta del registro.");
        }
        try {
            return Long.valueOf(id.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Debe indicar el punto de venta del registro.");
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
     * Resolves the authenticated Usuarios row for audit attribution, mirroring
     * legacy currentSession.getCurrentUser(); null when anonymous/unknown.
     */
    @Nullable
    private Usuarios currentUserOrNull() {
        if (!sessionValid() || securityIdentity.getPrincipal() == null) {
            return null;
        }
        return loginService.findByUsername(securityIdentity.getPrincipal().getName());
    }

    /**
     * Manual mapper: ConfiguracionAplicacion → AppSettingsDTO, field by field. Secret
     * fields (contrasenaCorreo, certificado, certificadoPassword,
     * haciendaApiKey, haciendaEncryptionKey, fidesAuthPassword) are NEVER read
     * here — the DTO does not carry them.
     */
    private static AppSettingsDTO toDTO(@Nonnull ConfiguracionAplicacion s) {
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
                s.getFidesUserId(),
                s.getProveedorCorreo(),
                s.getImapCorreoHost(),
                s.getImapCorreoPuerto(),
                s.getSmtpCorreoHost(),
                s.getSmtpCorreoPuerto());
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
    private static BackupStatusDTO toBackupStatusDTO(@Nullable ConfiguracionAplicacion settings) {
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
        /**
         * Identificacion del proveedor de sistemas, que el comprobante v4.4
         * exige como <ProveedorSistemas> nada mas despues de <Clave>. Antes de
         * exponerlo aqui el campo no era escribible desde ningun sitio, con lo
         * que getProvedor() devolvia null y el elemento se omitia del XML.
         */
        @Nullable
        public String proveedorSistemas;

        /**
         * Proveedor de buzone: GMAIL | OUTLOOK | YAHOO | PERSONALIZADO. Un valor
         * desconocido se rechaza con 400 en vez de guardarse: un nombre mal
         * escrito se resolveria en silencio a Gmail (ver
         * ProveedorCorreo.desdeClave) y el operador creeria que esta leyendo
         * otra bandeja.
         */
        @Nullable
        public String proveedorCorreo;

        /** Host IMAP; vacio = el del preset. */
        @Nullable
        public String imapCorreoHost;

        /** Puerto IMAP; null/vacio/ilegible = el del preset. */
        @Nullable
        public String imapCorreoPuerto;

        /** Host SMTP; vacio = el del preset. */
        @Nullable
        public String smtpCorreoHost;

        /** Puerto SMTP; null/vacio/ilegible = el del preset. */
        @Nullable
        public String smtpCorreoPuerto;
    }

    /** Whether a stored provider key names a real preset. */
    private static boolean esProveedorConocido(@Nonnull String clave) {
        for (ProveedorCorreo proveedor : ProveedorCorreo.values()) {
            if (proveedor.name().equals(clave)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Parses an operator-entered port, or returns null when it is absent or
     * outside 1..65535. Rejecting here (rather than clamping) means a typo in
     * the settings page is reported to whoever typed it instead of silently
     * connecting somewhere else.
     */
    @Nullable
    private static Integer puertoValido(@Nullable String puerto) {
        if (puerto == null || puerto.isBlank()) {
            return null;
        }
        try {
            int valor = Integer.parseInt(puerto.trim());
            if (valor < 1 || valor > 65535) {
                return null;
            }
            return valor;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
