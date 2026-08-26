package Controllers.Api.App;

import Models.Correos.EmailTemplate;
import Models.DTO.ApiResponse;
import Models.DTO.EmailTemplateDTO;
import Models.DTO.PagedResponse;
import Models.Users;
import Services.AlertasService;
import Services.Correos.EmailTemplateService;
import Services.LoginService;
import Utils.DiffUtils;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Email-template administration endpoints for the NEW Qute/HTMX app surface
 * (/app world) — strangler-phase REST mirror of the legacy JSF
 * {@code Controllers.Correos.EmailTemplateController}.
 *
 * <p><b>Legacy parity</b> (verified against {@code EmailTemplateController}
 * saveTemplate/updateTemplate/deleteTemplate/toggleTemplate):</p>
 * <ul>
 *   <li>Creation rejects duplicate names with the legacy warning text
 *       ("Ya existe una plantilla con ese nombre!") as a 409 — the legacy
 *       guard is {@code emailTemplateService.findByNombre(...) != null}.</li>
 *   <li>Creation always forces {@code status = true} and stamps both
 *       {@code fechaCreacion} and {@code fechaModificacion}, exactly like
 *       {@code saveTemplate()}.</li>
 *   <li>Updates perform NO duplicate-name re-check and stamp
 *       {@code fechaModificacion} on every save, exactly like
 *       {@code updateTemplate()}.</li>
 *   <li>Deletion is a HARD delete ({@code deleteTemplate()} never soft-archives
 *       templates), with the legacy audit texts preserved verbatim.</li>
 *   <li>The legacy {@code usuario} attribution ({@code currentSession
 *       .getCurrentUser()}) is preserved by resolving the form-auth principal
 *       back to a {@link Users} row through {@link LoginService#findByUsername};
 *       the JSF {@code SessionController} itself must never be injected into
 *       JAX-RS resources (see {@link AppAuthResource}). While auth is dormant
 *       the identity is anonymous and the attribution stays null.</li>
 * </ul>
 *
 * <p>Required-field validation (nombre, tipo) is explicit here because the
 * legacy JSF form enforced it client-side; {@code cuerpoHtml}/{@code asunto}
 * stay optional, mirroring the nullable entity columns. The {@code q} filter
 * reproduces the nombre branch of the legacy in-memory {@code matchesFilter()}
 * (templates were always loaded via {@code listAll()} and filtered in memory),
 * so no new service query was needed; pagination follows the
 * {@code listPage + count} convention of {@link UsersResource}.</p>
 *
 * <p>Role gate mirrors the Correos administration surface: {@code admin} OR
 * {@code tributacion}. Like every {@code /api/app/*} resource, the
 * {@code @RolesAllowed} gates are DORMANT until the form-cookie auth block is
 * enabled in application.properties (see {@link AppAuthResource}).</p>
 *
 * <p>All responses follow the {@link ApiResponse}/{@link PagedResponse}
 * envelope conventions. No {@code FacesContext} anywhere. Write methods carry
 * no resource-level {@code @Transactional} because
 * {@code EmailTemplateService} inherits transactional create/update/delete
 * from {@code GService} (unlike {@code LoginService}, which is why
 * {@link UsersResource} annotates its own).</p>
 */
@Path("/api/app/email-templates")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"admin", "tributacion"})
@Tag(name = "App - Plantillas de Correo")
public class EmailTemplateResource {

    private static final Logger LOG = Logger.getLogger(EmailTemplateResource.class.getName());

    @Inject
    @Nonnull
    EmailTemplateService emailTemplateService;

    @Inject
    @Nonnull
    LoginService loginService;

    @Inject
    @Nonnull
    AlertasService alertas;

    @Inject
    @Nonnull
    SecurityIdentity securityIdentity;

    /**
     * Paginated template listing; optional {@code q} filters by name
     * (case-insensitive substring), mirroring the legacy nombre filter.
     */
    @GET
    @Operation(summary = "List email templates with pagination and an optional name filter")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Missing admin/tributacion role"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response list(
            @QueryParam("page") @DefaultValue("0")
            @Parameter(description = "Page number (0-based)") int page,
            @QueryParam("size") @DefaultValue("20")
            @Parameter(description = "Page size (max 100)") int size,
            @QueryParam("q") @Nullable
            @Parameter(description = "Filter by template name (case-insensitive substring)") String q) {

        // Clamp size to max 100 (SuppliersController/UsersResource convention)
        size = Math.min(Math.max(size, 1), 100);
        page = Math.max(page, 0);

        try {
            if (q == null || q.isBlank()) {
                long total = emailTemplateService.count();
                List<EmailTemplateDTO> data = emailTemplateService.listPage(page * size, size).stream()
                        .map(template -> toDTO(template))
                        .toList();
                return Response.ok(new PagedResponse<>(data, total, page, size)).build();
            }

            // Parity with legacy getFilteredTemplates()/matchesFilter(): the
            // datatable ALWAYS listed all templates and filtered them in
            // memory, so filtering happens here over listAll() instead of a
            // dedicated service query that does not exist.
            String needle = q.trim().toLowerCase(Locale.ROOT);
            List<EmailTemplate> filtered = emailTemplateService.listAll().stream()
                    .filter(t -> t.getNombre() != null
                            && t.getNombre().toLowerCase(Locale.ROOT).contains(needle))
                    .toList();

            int from = Math.min(page * size, filtered.size());
            int to = Math.min(from + size, filtered.size());
            List<EmailTemplateDTO> data = filtered.subList(from, to).stream()
                    .map(template -> toDTO(template))
                    .toList();
            return Response.ok(new PagedResponse<>(data, filtered.size(), page, size)).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error listing email templates", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR",
                            "Error listando las plantillas de correo"))
                    .build();
        }
    }

    /** Fetches one template by id. */
    @GET
    @Path("/{id}")
    @Operation(summary = "Get an email template by id")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Missing admin/tributacion role"),
        @APIResponse(responseCode = "404", description = "Not found"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response get(@PathParam("id") @Parameter(description = "Template ID") Long id) {
        try {
            EmailTemplate template = emailTemplateService.find(id);
            if (template == null) {
                return notFound(id);
            }
            return Response.ok(ApiResponse.ok(toDTO(template))).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error fetching email template " + id, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR",
                            "Error obteniendo la plantilla de correo"))
                    .build();
        }
    }

    /**
     * Creates a template — saveTemplate() parity: duplicate-name 409 with the
     * legacy warning text, status forced to true, both dates stamped now, and
     * the current user attributed when the identity is authenticated.
     */
    @POST
    @Operation(summary = "Create an email template (duplicate names rejected)")
    @APIResponses({
        @APIResponse(responseCode = "201", description = "Created"),
        @APIResponse(responseCode = "400", description = "Validation error"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Missing admin/tributacion role"),
        @APIResponse(responseCode = "409", description = "Duplicate template name"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response create(@Nullable EmailTemplateDTO request) {
        try {
            if (request == null || request.getNombre() == null || request.getNombre().isBlank()) {
                return badRequest("El nombre de la plantilla no puede estar vacío.");
            }
            if (request.getTipo() == null || request.getTipo().isBlank()) {
                return badRequest("El tipo de la plantilla no puede estar vacío.");
            }

            // Parity with saveTemplate(): findByNombre guard BEFORE persist,
            // rejected with the legacy FacesMessage text.
            if (emailTemplateService.findByNombre(request.getNombre().trim()) != null) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(ApiResponse.error("NAME_TAKEN",
                                "Ya existe una plantilla con ese nombre!"))
                        .build();
            }

            EmailTemplate template = new EmailTemplate();
            template.setNombre(request.getNombre().trim());
            template.setAsunto(request.getAsunto());
            template.setCuerpoHtml(request.getCuerpoHtml());
            template.setTipo(request.getTipo().trim());
            // Parity: saveTemplate() always enables new templates.
            template.setStatus(true);
            Date now = new Date();
            template.setFechaCreacion(now);
            template.setFechaModificacion(now);
            // Parity: newTemplate.setUsuario(currentSession.getCurrentUser())
            template.setUsuario(resolveCurrentUser());

            emailTemplateService.create(template);

            alertas.registrarAlerta("Plantilla de correo creada",
                    "Se ha creado la plantilla de correo: " + template.getNombre(),
                    null, 0, "EmailTemplateResource.create()",
                    null, DiffUtils.snapshotEntity(template));

            return Response.status(Response.Status.CREATED)
                    .entity(ApiResponse.ok(toDTO(template)))
                    .build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error creating email template", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR",
                            "Error creando la plantilla de correo"))
                    .build();
        }
    }

    /**
     * Updates a template — updateTemplate() parity: NO duplicate-name re-check
     * (legacy behavior, kept deliberately), {@code fechaModificacion} stamped
     * on every save. Full-replace semantics mirroring the legacy edit dialog:
     * omitted (null) fields keep their stored value.
     */
    @PUT
    @Path("/{id}")
    @Operation(summary = "Update an email template (no duplicate-name re-check, legacy parity)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Updated"),
        @APIResponse(responseCode = "400", description = "Validation error"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Missing admin/tributacion role"),
        @APIResponse(responseCode = "404", description = "Not found"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response update(
            @PathParam("id") @Parameter(description = "Template ID") Long id,
            @Nullable EmailTemplateDTO request) {
        try {
            if (request == null) {
                return badRequest("El cuerpo de la petición es requerido.");
            }
            if (request.getNombre() != null && request.getNombre().isBlank()) {
                return badRequest("El nombre de la plantilla no puede estar vacío.");
            }
            if (request.getTipo() != null && request.getTipo().isBlank()) {
                return badRequest("El tipo de la plantilla no puede estar vacío.");
            }

            EmailTemplate template = emailTemplateService.find(id);
            if (template == null) {
                return notFound(id);
            }

            String antes = DiffUtils.snapshotEntity(template);
            if (request.getNombre() != null) {
                template.setNombre(request.getNombre().trim());
            }
            if (request.getAsunto() != null) {
                template.setAsunto(request.getAsunto());
            }
            if (request.getCuerpoHtml() != null) {
                template.setCuerpoHtml(request.getCuerpoHtml());
            }
            if (request.getTipo() != null) {
                template.setTipo(request.getTipo().trim());
            }
            template.setStatus(request.isStatus());
            // Parity: updateTemplate() stamps fechaModificacion on every save.
            template.setFechaModificacion(new Date());

            emailTemplateService.update(template);

            alertas.registrarAlerta("Plantilla de correo actualizada",
                    "Se ha actualizado la plantilla de correo: " + template.getNombre(),
                    null, 0, "EmailTemplateResource.update()",
                    antes, DiffUtils.snapshotEntity(template));

            return Response.ok(ApiResponse.ok(toDTO(template))).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error updating email template " + id, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR",
                            "Error actualizando la plantilla de correo"))
                    .build();
        }
    }

    /**
     * Deletes a template — deleteTemplate() parity: HARD delete with the
     * legacy audit texts (templates have no soft-archive concept).
     */
    @DELETE
    @Path("/{id}")
    @Operation(summary = "Delete an email template (hard delete, legacy parity)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Deleted"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Missing admin/tributacion role"),
        @APIResponse(responseCode = "404", description = "Not found"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response delete(@PathParam("id") @Parameter(description = "Template ID") Long id) {
        try {
            EmailTemplate template = emailTemplateService.find(id);
            if (template == null) {
                return notFound(id);
            }

            String antes = DiffUtils.snapshotEntity(template);
            emailTemplateService.delete(template);

            alertas.registrarAlerta("Plantilla de correo eliminada",
                    "Se ha eliminado la plantilla de correo: " + template.getNombre(),
                    null, 0, "EmailTemplateResource.delete()",
                    antes, null);

            return Response.ok(ApiResponse.ok(toDTO(template))).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error deleting email template " + id, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR",
                            "Error eliminando la plantilla de correo"))
                    .build();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static Response notFound(Long id) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(ApiResponse.error("NOT_FOUND",
                        "No se encontró la plantilla de correo: " + id))
                .build();
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(ApiResponse.error("VALIDATION_ERROR", message))
                .build();
    }

    /**
     * Parity with {@code currentSession.getCurrentUser()} on creation, resolved
     * WITHOUT touching the JSF {@code SessionController} (never injectable into
     * JAX-RS): the authenticated principal name is mapped back to its
     * {@link Users} row via {@link LoginService#findByUsername}. Returns
     * {@code null} while auth is dormant (anonymous identity).
     */
    @Nullable
    private Users resolveCurrentUser() {
        if (securityIdentity == null || securityIdentity.isAnonymous()
                || securityIdentity.getPrincipal() == null) {
            return null;
        }
        return loginService.findByUsername(securityIdentity.getPrincipal().getName());
    }

    /**
     * Read-side mapping ONLY — flattens the {@code @ManyToOne usuario}
     * relation to {@code usuarioId} so no user fields leak into the DTO layer.
     */
    private static EmailTemplateDTO toDTO(@Nonnull EmailTemplate template) {
        return new EmailTemplateDTO(
                template.getId(),
                template.getNombre(),
                template.getAsunto(),
                template.getCuerpoHtml(),
                template.getTipo(),
                template.isStatus(),
                template.getFechaCreacion(),
                template.getFechaModificacion(),
                template.getUsuario() == null ? null : template.getUsuario().getId());
    }
}
