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
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

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

    @Nonnull
    @Inject
    Services.EmailService emailService;

    @Inject
    @Nonnull
    SecurityIdentity securityIdentity;

    /**
     * Current operational settings mapped to the secret-free DTO.
     * 404 when no row has estatus=true (GET never creates rows).
     */
    @GET
    @Transactional
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
        return aplicarCambios(request);
    }

    /**
     * Form twin of {@link #update}.
     *
     * <p>La pantalla de ajustes manda un cuerpo <b>form-urlencoded</b> (un
     * {@code hx-post} desde un {@code <form>}), pero el endpoint solo declaraba
     * {@code @Consumes(application/json)}: el servidor respondía <b>415</b> y el
     * botón "Guardar ajustes" nunca había guardado nada. Este gemelo mapea los
     * mismos campos y delega en el MISMO merge; no agrega lógica.</p>
     *
     * <p>Casillas tri-estado: el formulario emite un marcador
     * {@code <campo>Presente} para distinguir "no vino" (dejar igual) de
     * "desmarcada" (poner false).</p>
     */
    @POST
    @Path("/form")
    @Transactional
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(summary = "Update whitelisted operational settings (form twin)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Settings updated"),
        @APIResponse(responseCode = "400", description = "Validation error"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Missing admin role"),
        @APIResponse(responseCode = "404", description = "No active settings row")
    })
    public Response updateForm(
            @FormParam("notificarRechazos") @Nullable String notificarRechazos,
            @FormParam("notificarRechazosPresente") @Nullable String notificarRechazosPresente,
            @FormParam("notificarRechazosResumen") @Nullable String notificarRechazosResumen,
            @FormParam("notificarRechazosResumenPresente") @Nullable String notificarRechazosResumenPresente,
            @FormParam("correoNotificaciones") @Nullable String correoNotificaciones,
            @FormParam("backupHabilitado") @Nullable String backupHabilitado,
            @FormParam("backupHabilitadoPresente") @Nullable String backupHabilitadoPresente,
            @FormParam("backupHora") @Nullable String backupHora,
            @FormParam("backupRetencionDias") @Nullable String backupRetencionDias,
            @FormParam("backupRuta") @Nullable String backupRuta,
            @FormParam("proveedorSistemas") @Nullable String proveedorSistemas,
            @FormParam("proveedorCorreo") @Nullable String proveedorCorreo,
            @FormParam("imapCorreoHost") @Nullable String imapCorreoHost,
            @FormParam("imapCorreoPuerto") @Nullable String imapCorreoPuerto,
            @FormParam("smtpCorreoHost") @Nullable String smtpCorreoHost,
            @FormParam("smtpCorreoPuerto") @Nullable String smtpCorreoPuerto,
            @FormParam("contrasenaCorreo") @Nullable String contrasenaCorreo,
            @FormParam("nombrePerfil") @Nullable String nombrePerfil,
            @FormParam("nombre") @Nullable String nombre,
            @FormParam("tipoIdentificacion") @Nullable String tipoIdentificacion,
            @FormParam("identificacion") @Nullable String identificacion,
            @FormParam("nombreNegocio") @Nullable String nombreNegocio,
            @FormParam("razonSocial") @Nullable String razonSocial,
            @FormParam("codigoActividad") @Nullable String codigoActividad,
            @FormParam("telefono") @Nullable String telefono,
            @FormParam("correoElectronicoTributacion") @Nullable String correoElectronicoTributacion,
            @FormParam("provincia") @Nullable String provincia,
            @FormParam("canton") @Nullable String canton,
            @FormParam("distrito") @Nullable String distrito,
            @FormParam("barrio") @Nullable String barrio,
            @FormParam("direccionCompleta") @Nullable String direccionCompleta) {

        OperationalSettingsRequest request = new OperationalSettingsRequest();
        request.notificarRechazos = casilla(notificarRechazos, notificarRechazosPresente);
        request.notificarRechazosResumen = casilla(notificarRechazosResumen, notificarRechazosResumenPresente);
        request.backupHabilitado = casilla(backupHabilitado, backupHabilitadoPresente);
        request.correoNotificaciones = correoNotificaciones;
        // Un <input> vacío manda "" y el merge trata null como "no cambiar":
        // sin esta normalización, dejar la hora o el puerto en blanco se
        // rechazaba con 400 en vez de conservar el valor actual.
        request.backupHora = enBlancoNulo(backupHora);
        request.backupRetencionDias = enteroONull(backupRetencionDias);
        request.backupRuta = backupRuta;
        request.proveedorSistemas = proveedorSistemas;
        request.proveedorCorreo = proveedorCorreo;
        request.imapCorreoHost = imapCorreoHost;
        request.imapCorreoPuerto = enBlancoNulo(imapCorreoPuerto);
        request.smtpCorreoHost = smtpCorreoHost;
        request.smtpCorreoPuerto = enBlancoNulo(smtpCorreoPuerto);
        // Secreto de solo escritura: vacío = conservar la contraseña actual.
        request.contrasenaCorreo = enBlancoNulo(contrasenaCorreo);
        request.nombrePerfil = nombrePerfil;
        request.nombre = nombre;
        request.tipoIdentificacion = tipoIdentificacion;
        request.identificacion = identificacion;
        request.nombreNegocio = nombreNegocio;
        request.razonSocial = razonSocial;
        request.codigoActividad = codigoActividad;
        request.telefono = telefono;
        request.correoElectronicoTributacion = correoElectronicoTributacion;
        request.provincia = provincia;
        request.canton = canton;
        request.distrito = distrito;
        request.barrio = barrio;
        request.direccionCompleta = direccionCompleta;
        return aplicarCambios(request);
    }

    @Nullable
    private static String enBlancoNulo(@Nullable String valor) {
        return valor == null || valor.isBlank() ? null : valor;
    }

    /** Texto recortado; null si queda vacío. Rechaza lo que no cabe en la columna. */
    @Nullable
    private static String textoLimitado(@Nullable String valor, int max, @Nonnull String etiqueta) {
        if (valor == null) {
            return null;
        }
        String limpio = valor.trim();
        if (limpio.isEmpty()) {
            return null;
        }
        if (limpio.length() > max) {
            throw new IllegalArgumentException(etiqueta + " no puede superar " + max + " caracteres.");
        }
        return limpio;
    }

    /** Tri-state checkbox: marker absent = leave unchanged; unchecked = false. */
    @Nullable
    private static Boolean casilla(@Nullable String valor, @Nullable String presente) {
        if (presente == null) {
            return null;
        }
        return valor != null && !valor.isBlank() && !"false".equalsIgnoreCase(valor) && !"0".equals(valor);
    }

    /** Un entero de formulario; vacío o ilegible = "no cambiar". */
    @Nullable
    private static Integer enteroONull(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            LOG.warn("Valor numérico inválido en ajustes ignorado: " + raw
                    + " | source=SettingsResource.enteroONull()");
            return null;
        }
    }

    private Response aplicarCambios(@Nullable OperationalSettingsRequest request) {
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

            // ── Perfil y tributación ─────────────────────────────────────
            // Todos son columnas de texto de ConfiguracionAplicacion. Se
            // recortan y se limitan; el tipo de identificación solo admite los
            // códigos que Hacienda define (01..04) porque el comprobante los
            // emite tal cual.
            if (request.nombrePerfil != null) {
                settings.setNombrePerfil(textoLimitado(request.nombrePerfil, 120, "El nombre del perfil"));
            }
            if (request.nombre != null) {
                settings.setNombre(textoLimitado(request.nombre, 160, "El nombre completo"));
            }
            if (request.tipoIdentificacion != null) {
                String tipo = request.tipoIdentificacion.trim();
                if (!tipo.isEmpty() && !tipo.matches("0[1-4]")) {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(ApiResponse.error("VALIDATION_ERROR",
                                    "El tipo de identificación debe ser 01 (física), 02 (jurídica), 03 (DIMEX) o 04 (NITE)."))
                            .build();
                }
                settings.setTipoIdentificacion(tipo.isEmpty() ? null : tipo);
            }
            if (request.identificacion != null) {
                settings.setIdentificacion(textoLimitado(request.identificacion, 20, "La identificación"));
            }
            if (request.nombreNegocio != null) {
                settings.setNombreNegocio(textoLimitado(request.nombreNegocio, 160, "El nombre del negocio"));
            }
            if (request.razonSocial != null) {
                settings.setRazonSocial(textoLimitado(request.razonSocial, 160, "La razón social"));
            }
            if (request.codigoActividad != null) {
                String actividad = request.codigoActividad.trim();
                // Hacienda v4.4: CodigoActividadType = exactamente 6 digitos.
                // Un valor como "4711.2" produce XML invalido en TODOS los
                // comprobantes (el XSD lo rechaza con cvc-pattern-valid).
                if (!actividad.isEmpty() && !actividad.matches("\\d{6}")) {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(ApiResponse.error("VALIDATION_ERROR",
                                    "El código de actividad debe tener 6 dígitos (por ejemplo, 471102)."))
                            .build();
                }
                settings.setCodigoActividad(actividad.isEmpty() ? null : actividad);
            }
            if (request.telefono != null) {
                settings.setTelefono(textoLimitado(request.telefono, 20, "El teléfono"));
            }
            if (request.correoElectronicoTributacion != null) {
                String correo = request.correoElectronicoTributacion.trim();
                if (!correo.isEmpty() && (correo.length() > 160 || !correo.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"))) {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(ApiResponse.error("VALIDATION_ERROR",
                                    "El correo de tributación no es válido."))
                            .build();
                }
                settings.setCorreoElectronicoTributacion(correo.isEmpty() ? null : correo);
            }
            if (request.provincia != null) {
                settings.setProvincia(textoLimitado(request.provincia, 80, "La provincia"));
            }
            if (request.canton != null) {
                settings.setCanton(textoLimitado(request.canton, 80, "El cantón"));
            }
            if (request.distrito != null) {
                settings.setDistrito(textoLimitado(request.distrito, 80, "El distrito"));
            }
            if (request.barrio != null) {
                settings.setBarrio(textoLimitado(request.barrio, 80, "El barrio"));
            }
            if (request.direccionCompleta != null) {
                settings.setDireccionCompleta(textoLimitado(request.direccionCompleta, 250, "La dirección"));
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
            // Contraseña de aplicación del correo: solo se escribe si llega un
            // valor (el campo del formulario es de solo escritura).
            if (request.contrasenaCorreo != null) {
                settings.setContrasenaCorreo(request.contrasenaCorreo);
            }

            settingsService.update(settings);

                        LOG.info("Se ha actualizado la configuración: " + settings.getNombrePerfil() + " | user=" + String.valueOf(currentUserOrNull()) + " | source=" + "SettingsResource.update()" + " | antes=" + String.valueOf(antes) + " | despues=" + String.valueOf(DiffUtils.snapshotEntity(settings)));

            return Response.ok(ApiResponse.ok(toDTO(settings))).build();
        } catch (IllegalArgumentException e) {
            // Mensaje ya redactado por textoLimitado(); es un dato del
            // operador, no un fallo del servidor.
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("VALIDATION_ERROR", e.getMessage()))
                    .build();
        } catch (Exception e) {
            LOG.warn("Error updating app settings", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error actualizando la configuración"))
                    .build();
        }
    }

    /**
     * Logo de la empresa: bytes guardados en la configuración.
     *
     * <p>El almacenamiento ya existía (columnas {@code Logo}/{@code LogoMimeType}
     * de {@link ConfiguracionAplicacion} y el viejo
     * {@code SettingsController.uploadLogo(Part)} de JSF); lo que no existía era
     * una superficie HTTP en la API nueva. {@code no-store} para que el logo
     * recién subido no se sirva desde caché.</p>
     */
    @GET
    @Path("/logo")
    @Produces(MediaType.WILDCARD)
    @Transactional
    @Operation(summary = "Current company logo bytes")
    public Response logo() {
        try {
            ConfiguracionAplicacion settings = settingsService.returnCurrent();
            if (settings == null || settings.getLogo() == null || settings.getLogo().length == 0) {
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            String tipo = settings.getLogoMimeType() == null || settings.getLogoMimeType().isBlank()
                    ? "image/png" : settings.getLogoMimeType();
            return Response.ok(settings.getLogo())
                    .type(tipo)
                    .header("Cache-Control", "no-store")
                    .build();
        } catch (RuntimeException e) {
            LOG.warn("No se pudo servir el logo de la configuración", e);
            return Response.serverError().build();
        }
    }

    /** 2 MB: de sobra para un membrete y lejos del límite del cuerpo. */
    private static final long LOGO_MAX_BYTES = 2L * 1024 * 1024;

    /** SVG queda fuera a propósito: se sirve con su propio content-type y puede traer script. */
    private static final List<String> LOGO_TIPOS = List.of("image/png", "image/jpeg", "image/webp");

    /**
     * Sube el logo de la empresa (multipart, campo {@code logo}).
     *
     * <p>Paridad con {@link Controllers.SettingsController#uploadLogo}: guarda
     * bytes + mime type y avanza el paso del asistente (completedSteps 1 -> 2).
     * No toca nada más de la configuración.</p>
     */
    @POST
    @Path("/logo")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Transactional
    @Operation(summary = "Upload the company logo (multipart)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Logo stored"),
        @APIResponse(responseCode = "400", description = "Missing file, wrong type or too large"),
        @APIResponse(responseCode = "404", description = "No active settings row")
    })
    public Response uploadLogo(@RestForm("logo") @Nullable FileUpload archivo) {
        if (archivo == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("VALIDATION_ERROR", "Seleccione un archivo de imagen."))
                    .build();
        }
        String tipo = archivo.contentType() == null ? "" : archivo.contentType().toLowerCase(Locale.ROOT);
        if (!LOGO_TIPOS.contains(tipo)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("VALIDATION_ERROR",
                            "El logo debe ser una imagen PNG, JPG o WebP."))
                    .build();
        }
        if (archivo.size() <= 0) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("VALIDATION_ERROR", "El archivo está vacío."))
                    .build();
        }
        if (archivo.size() > LOGO_MAX_BYTES) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("VALIDATION_ERROR", "El logo no puede superar 2 MB."))
                    .build();
        }
        try {
            byte[] bytes = Files.readAllBytes(archivo.uploadedFile());
            ConfiguracionAplicacion settings = settingsService.returnCurrent();
            if (settings == null) {
                return noActiveSettings();
            }
            settings.setLogo(bytes);
            settings.setLogoMimeType(tipo);
            Integer paso = settings.getCompletedSteps();
            if (paso != null && paso == 1) {
                settings.setCompletedSteps(2);
            }
            settingsService.update(settings);
            LOG.info("Logo actualizado: " + bytes.length + " bytes (" + tipo + ")"
                    + " | user=" + currentUserOrNull()
                    + " | source=SettingsResource.uploadLogo()");
            // Respuesta mínima a propósito: toDTO() arrastra los bytes del logo
            // (hasta 2 MB) y la página recarga para mostrar la imagen nueva.
            Map<String, Object> resultado = new LinkedHashMap<>();
            resultado.put("logoMimeType", tipo);
            resultado.put("bytes", bytes.length);
            return Response.ok(ApiResponse.ok(resultado)).build();
        } catch (java.io.IOException e) {
            LOG.warn("No se pudo leer el archivo de logo subido", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "No se pudo leer el archivo subido"))
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
    /**
     * Envía un correo de prueba con las credenciales guardadas (SMTP).
     *
     * <p>Devuelve JSON para que el botón {@code data-json-save} de Ajustes
     * muestre el resultado (éxito o el motivo del fallo) sin recargar.</p>
     */
    @POST
    @Path("/correo/prueba")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Operation(summary = "Send a test email with the saved SMTP credentials")
    public Response probarCorreo() {
        ConfiguracionAplicacion settings = settingsService.returnCurrent();
        if (settings == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("VALIDATION_ERROR", "No hay configuración guardada."))
                    .build();
        }
        String correo = settings.getCorreoElectronico();
        String pass = settings.getContrasenaCorreo();
        if (correo == null || correo.isBlank() || pass == null || pass.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("VALIDATION_ERROR",
                            "Configure el correo y la contraseña de aplicación antes de probar."))
                    .build();
        }
        String destino = settings.getCorreoNotificaciones() != null
                && !settings.getCorreoNotificaciones().isBlank()
                ? settings.getCorreoNotificaciones().trim() : correo.trim();
        java.util.concurrent.CompletableFuture<String> resultado = new java.util.concurrent.CompletableFuture<>();
        emailService.sendEmail(destino, "Prueba de correo - Mercurius",
                "Este es un correo de prueba enviado desde Mercurius.",
                correo.trim(), pass.trim(), resultado::complete);
        String estado;
        try {
            estado = resultado.get(45, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            LOG.warn("Correo de prueba sin respuesta en 45s | destino=" + destino, e);
            return Response.status(Response.Status.GATEWAY_TIMEOUT)
                    .entity(ApiResponse.error("EMAIL_TIMEOUT",
                            "El servidor de correo no respondió. Revise host y puerto."))
                    .build();
        } catch (RuntimeException | InterruptedException | java.util.concurrent.ExecutionException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.warn("Error esperando el correo de prueba: " + e.getMessage(), e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(ApiResponse.error("INTERNAL_ERROR", "No se pudo enviar el correo de prueba."))
                    .build();
        }
        if ("Sent".equals(estado)) {
            LOG.info("Correo de prueba enviado a " + destino
                    + " | user=" + String.valueOf(currentUserOrNull()));
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("destino", destino);
            return Response.ok(ApiResponse.ok(data)).build();
        }
        LOG.warn("Correo de prueba falló: " + estado + " | destino=" + destino);
        return Response.status(Response.Status.BAD_GATEWAY)
                .entity(ApiResponse.error("EMAIL_FAILED", "No se pudo enviar: " + estado))
                .build();
    }

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

        // ── Perfil y tributación ──────────────────────────────────────────
        // Estos campos existían en ConfiguracionAplicacion pero no eran
        // escribibles desde ninguna pantalla nueva: el asistente los mostraba
        // en solo lectura. Exponerlos aquí es paridad con la entidad, no
        // lógica nueva.
        @Nullable public String nombrePerfil;
        @Nullable public String nombre;
        @Nullable public String tipoIdentificacion;
        @Nullable public String identificacion;
        @Nullable public String nombreNegocio;
        @Nullable public String razonSocial;
        @Nullable public String codigoActividad;
        @Nullable public String telefono;
        @Nullable public String correoElectronicoTributacion;
        @Nullable public String provincia;
        @Nullable public String canton;
        @Nullable public String distrito;
        @Nullable public String barrio;
        @Nullable public String direccionCompleta;

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

        /** Contraseña de aplicación del correo; null = conservar la actual. */
        @Nullable
        public String contrasenaCorreo;
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
