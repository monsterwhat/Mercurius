package Controllers.Api.App;

import Models.Correos.EmailTemplate;
import Models.DTO.ApiResponse;
import Models.DTO.EmailTemplateDTO;
import Models.DTO.PagedResponse;
import Models.Correos.EmailTemplateTipo;
import Models.Users;
import Services.Correos.EmailTemplateService;
import Services.LoginService;
import Utils.DiffUtils;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    @Nonnull
    @Inject
    EmailTemplateService emailTemplateService;

    @Nonnull
    @Inject
    LoginService loginService;

    
    @Inject
    @Nonnull
    SecurityIdentity securityIdentity;

    /** Request context (quarkus-rest injectable) — source of HX-Request. */
    @Nonnull
    @Inject
    RoutingContext routing;

    // View-half templates (W4B-CORREOS). Rendered to String: no
    // quarkus-rest-qute MessageBodyWriter on this stack — same approach as
    // CategoriaResource/T18 and LoginPageResource/T14.
    @Nonnull
    @Location("pages/correos/templates.html")
    @Inject
    Template pageIndex;

    @Nonnull
    @Location("pages/correos/tabla-plantillas.html")
    @Inject
    Template tablaPlantillas;

    @Nonnull
    @Location("pages/correos/form-plantilla.html")
    @Inject
    Template formPlantilla;

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

                        LOG.info("Se ha creado la plantilla de correo: " + template.getNombre() + " | source=" + "EmailTemplateResource.create()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(DiffUtils.snapshotEntity(template)));

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

                        LOG.info("Se ha actualizado la plantilla de correo: " + template.getNombre() + " | source=" + "EmailTemplateResource.update()" + " | antes=" + String.valueOf(antes) + " | despues=" + String.valueOf(DiffUtils.snapshotEntity(template)));

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

                        LOG.info("Se ha eliminado la plantilla de correo: " + template.getNombre() + " | source=" + "EmailTemplateResource.delete()" + " | antes=" + String.valueOf(antes) + " | despues=" + String.valueOf((Object) null));

            // View-half branch (docs/ui-kit.md §2.9 dual-mode contract): HTMX
            // callers get the refreshed table fragment plus an out-of-band
            // toast; the JSON envelope below stays byte-identical for API
            // clients (CategoriaResource/T18 precedent).
            if (isHxRequest()) {
                return tableFragment(1, 20, null, "asc", null,
                        "success", "Plantilla eliminada exitosamente");
            }

            return Response.ok(ApiResponse.ok(toDTO(template))).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error deleting email template " + id, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR",
                            "Error eliminando la plantilla de correo"))
                    .build();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // View half (W4B-CORREOS): dual-mode table endpoint + modal-body forms.
    // Invariant: the JSON endpoints above keep their exact request/response
    // contract; JAX-RS routes the urlencoded dialog forms to the *Form twins
    // purely by @Consumes media type.
    // ════════════════════════════════════════════════════════════════════

    /**
     * GET /table?page&size&sort&dir&q — with the {@code HX-Request} header
     * returns ONLY the data-table include (fragment swap into the page's
     * table container); without it renders the FULL plantillas admin page.
     * Mirrors the SERVER-SIDE CONTRACT of {@code templates/_kit/data-table.html}
     * exactly: the same endpoint renders page and fragments, all
     * paging/sorting state lives in the URL, and {@code page} is 1-based here
     * (kit convention — deliberately NOT the 0-based JSON list above, whose
     * contract is untouched).
     */
    @GET
    @Path("/table")
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Data-table fragment (HX-Request) or full plantillas page")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "HTML fragment or full page"),
        @APIResponse(responseCode = "500", description = "Template rendering failure")
    })
    public Response table(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("sort") @Nullable String sort,
            @QueryParam("dir") @DefaultValue("asc") String dir,
            @QueryParam("q") @Nullable String q) {
        try {
            if (isHxRequest()) {
                return tableFragment(page, size, sort, dir, q, null, null);
            }
            return htmlOk(renderFullPage());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error renderizando la página de plantillas", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error renderizando la página"))
                    .build();
        }
    }

    /** Empty plantilla creation form (modal body). */
    @GET
    @Path("/formularios/nueva")
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "New-plantilla form fragment (modal body)")
    public Response formNuevaPlantilla() {
        return htmlOk(formPlantilla
                .data("modo", "crear")
                .data("plantilla", null)
                .data("tipoOptions", tipoOptions())
                .data("errorNombre", null)
                .data("errorTipo", null)
                .data("toastSeverity", null)
                .data("toastMessage", null));
    }

    /** Prefilled plantilla edit form (modal body), looked up by id. */
    @GET
    @Path("/formularios/{id}")
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Edit-plantilla form fragment (modal body)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Form HTML"),
        @APIResponse(responseCode = "404", description = "Unknown id")
    })
    public Response formEditarPlantilla(@PathParam("id") Long id) {
        EmailTemplate template = emailTemplateService.find(id);
        if (template == null) {
            return notFound(id);
        }
        return htmlOk(formPlantilla
                .data("modo", "editar")
                .data("plantilla", template)
                .data("tipoOptions", tipoOptions())
                .data("errorNombre", null)
                .data("errorTipo", null)
                .data("toastSeverity", null)
                .data("toastMessage", null));
    }

    /**
     * Form-urlencoded twin of {@link #create(EmailTemplateDTO)} for the HTMX
     * dialog form (JAX-RS selects by Content-Type). Legacy saveTemplate()
     * parity: duplicate-name guard BEFORE persist with the legacy warning
     * text, status forced true, both dates stamped, user attributed.
     */
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Operation(summary = "Create a plantilla from an HTMX form", hidden = true)
    public Response createPlantillaForm(
            @FormParam("nombre") @Nullable String nombre,
            @FormParam("tipo") @Nullable String tipo,
            @FormParam("asunto") @Nullable String asunto,
            @FormParam("cuerpoHtml") @Nullable String cuerpoHtml) {
        if (nombre == null || nombre.isBlank()) {
            return formFailure("crear", null, null, "error",
                    "El nombre de la plantilla no puede estar vacío.");
        }
        if (tipo == null || tipo.isBlank()) {
            return formFailure("crear", null, nombre.trim(), "error",
                    "El tipo de la plantilla no puede estar vacío.");
        }
        try {
            if (emailTemplateService.findByNombre(nombre.trim()) != null) {
                return formFailure("crear", null, nombre.trim(), "warn",
                        "Ya existe una plantilla con ese nombre!");
            }

            EmailTemplate template = new EmailTemplate();
            template.setNombre(nombre.trim());
            template.setAsunto(asunto);
            template.setCuerpoHtml(cuerpoHtml);
            template.setTipo(tipo.trim());
            template.setStatus(true);
            Date now = new Date();
            template.setFechaCreacion(now);
            template.setFechaModificacion(now);
            template.setUsuario(resolveCurrentUser());

            emailTemplateService.create(template);

                        LOG.info("Se ha creado la plantilla de correo: " + template.getNombre() + " | source=" + "EmailTemplateResource.createPlantillaForm()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(DiffUtils.snapshotEntity(template)));

            if (isHxRequest()) {
                return hxRedirect("/api/app/email-templates/table");
            }
            return Response.status(Response.Status.CREATED)
                    .entity(ApiResponse.ok(toDTO(template))).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error creating email template from form", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR",
                            "Error creando la plantilla de correo"))
                    .build();
        }
    }

    /**
     * Form-urlencoded twin of {@link #update(Long, EmailTemplateDTO)} for the
     * HTMX dialog form. Legacy updateTemplate() parity: fechaModificacion
     * stamped on every save, NO duplicate-name re-check, and status left
     * untouched (the entity keeps its stored value because no setStatus call
     * is made here — unlike the JSON PUT, which applies
     * {@code request.isStatus()} unconditionally per its documented contract;
     * the dialog carries no status control, so passing it through would
     * disable the template on every edit).
     */
    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Operation(summary = "Update a plantilla from an HTMX form", hidden = true)
    public Response updatePlantillaForm(
            @PathParam("id") Long id,
            @FormParam("nombre") @Nullable String nombre,
            @FormParam("tipo") @Nullable String tipo,
            @FormParam("asunto") @Nullable String asunto,
            @FormParam("cuerpoHtml") @Nullable String cuerpoHtml) {
        if (nombre != null && nombre.isBlank()) {
            return formFailure("editar", id, null, "error",
                    "El nombre de la plantilla no puede estar vacío.");
        }
        if (tipo != null && tipo.isBlank()) {
            return formFailure("editar", id, nombre, "error",
                    "El tipo de la plantilla no puede estar vacío.");
        }
        try {
            EmailTemplate template = emailTemplateService.find(id);
            if (template == null) {
                return notFound(id);
            }

            String antes = DiffUtils.snapshotEntity(template);
            if (nombre != null) {
                template.setNombre(nombre.trim());
            }
            if (asunto != null) {
                template.setAsunto(asunto);
            }
            if (cuerpoHtml != null) {
                template.setCuerpoHtml(cuerpoHtml);
            }
            if (tipo != null) {
                template.setTipo(tipo.trim());
            }
            template.setFechaModificacion(new Date());

            emailTemplateService.update(template);

                        LOG.info("Se ha actualizado la plantilla de correo: " + template.getNombre() + " | source=" + "EmailTemplateResource.updatePlantillaForm()" + " | antes=" + String.valueOf(antes) + " | despues=" + String.valueOf(DiffUtils.snapshotEntity(template)));

            if (isHxRequest()) {
                return hxRedirect("/api/app/email-templates/table");
            }
            return Response.ok(ApiResponse.ok(toDTO(template))).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error updating email template " + id + " from form", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR",
                            "Error actualizando la plantilla de correo"))
                    .build();
        }
    }

    // ── View-half helpers ────────────────────────────────────────────────────

    /** True when the call comes from HTMX (layout hx-headers carries it). */
    private boolean isHxRequest() {
        String header = routing.request().getHeader("HX-Request");
        return header != null && !"false".equalsIgnoreCase(header);
    }

    private static Response htmlOk(@Nonnull TemplateInstance template) {
        return Response.ok(template.render())
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).build();
    }

    /** HTMX redirect: the client navigates and the page re-renders fresh. */
    private static Response hxRedirect(@Nonnull String url) {
        return Response.status(Response.Status.OK)
                .header("HX-Redirect", url)
                .build();
    }

    /**
     * Failure branch shared by the form twins: HTMX callers get the
     * redisplayed form fragment (entered values echoed back) plus an
     * out-of-band toast with HTTP 422 (ui-kit.md Pattern A); non-HTMX
     * callers get the structured envelope with the same status codes as the
     * JSON contract (409 duplicate / 400 validation).
     */
    private Response formFailure(@Nonnull String modo, @Nullable Long id,
                                 @Nullable String nombreEco, @Nonnull String severity,
                                 @Nonnull String mensaje) {
        boolean duplicado = "Ya existe una plantilla con ese nombre!".equals(mensaje);
        if (isHxRequest()) {
            EmailTemplate eco = new EmailTemplate();
            eco.setId(id);
            eco.setNombre(nombreEco);
            TemplateInstance template = formPlantilla
                    .data("modo", modo)
                    .data("plantilla", eco)
                    .data("tipoOptions", tipoOptions())
                    .data("errorNombre", mensaje)
                    .data("errorTipo", null)
                    .data("toastSeverity", severity)
                    .data("toastMessage", mensaje);
            return Response.status(Response.Status.BAD_REQUEST)
                    .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                    .entity(template.render()).build();
        }
        return Response.status(duplicado
                        ? Response.Status.CONFLICT : Response.Status.BAD_REQUEST)
                .entity(ApiResponse.error(
                        duplicado ? "NAME_TAKEN" : "VALIDATION_ERROR", mensaje))
                .build();
    }

    /**
     * Renders ONLY one data-table include (the fragment swap target).
     * Model keys mirror the _kit/data-table DATA CONTRACT verbatim.
     */
    private Response tableFragment(int page, int size, @Nullable String sort,
                                   @Nullable String dir, @Nullable String q,
                                   @Nullable String toastSeverity,
                                   @Nullable String toastMessage) {
        TableModel model = buildTableModel(page, size, sort, dir, q);
        return htmlOk(tablaPlantillas
                .data("modelo", model.asMap())
                .data("q", model.q())
                .data("toastSeverity", toastSeverity)
                .data("toastMessage", toastMessage));
    }

    /** Full-page model for pages/correos/templates.html. */
    private TemplateInstance renderFullPage() {
        TableModel tp = buildTableModel(1, 20, null, "asc", null);
        return pageIndex
                .data("tablaPlantillas", tp.asMap())
                .data("totalPlantillas", emailTemplateService.count());
    }

    /** Immutable view of everything tabla-plantillas.html needs. */
    public record TableModel(String id, String baseUrl, List<Map<String, Object>> columnas,
                             List<?> filas, String sortKey, String sortDir, int page, int size,
                             long total, int totalPages, List<Integer> paginas,
                             Map<String, Object> filtros, String q) {

        /** Flat map variant for direct TemplateInstance.data(Map) feeding. */
        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("baseUrl", baseUrl);
            map.put("columnas", columnas);
            map.put("filas", filas);
            map.put("sortKey", sortKey);
            map.put("sortDir", sortDir);
            map.put("page", page);
            map.put("size", size);
            map.put("total", total);
            map.put("totalPages", totalPages);
            map.put("paginas", paginas);
            map.put("filtros", filtros);
            map.put("q", q);
            return map;
        }
    }

    /** Builds the table model (legacy filter → sort → slice → columns). */
    private TableModel buildTableModel(int page, int size, @Nullable String sort,
                                       @Nullable String dir, @Nullable String q) {
        List<EmailTemplate> filtered =
                filterTemplates(orEmpty(emailTemplateService.listAll()), q);
        sortTemplates(filtered, sort, dir);

        long total = filtered.size();
        Window w = windowOf(total, page, size);

        List<Map<String, Object>> filas = new ArrayList<>();
        for (EmailTemplate t : filtered.subList(w.from(), w.to())) {
            Map<String, Object> fila = new LinkedHashMap<>();
            fila.put("id", t.getId());
            fila.put("estado", t.isStatus());
            fila.put("nombre", t.getNombre());
            fila.put("tipo", t.getTipo());
            fila.put("tipoLabel", tipoLabel(t.getTipo()));
            fila.put("tipoBadge", tipoBadge(t.getTipo()));
            fila.put("asunto", t.getAsunto());
            fila.put("fechaModificacion", formatFecha(t.getFechaModificacion()));
            filas.add(fila);
        }

        // Column sets mirror the legacy Templates p:dataTable (chip Estado /
        // Nombre / Tipo / Asunto / Ultima Modificacion / Acciones); null key ⇒
        // not sortable (docs/ui-kit.md §3.1).
        List<Map<String, Object>> columnas = new ArrayList<>();
        columnas.add(col("Estado", null));
        columnas.add(col("Nombre", "nombre"));
        columnas.add(col("Tipo", "tipo"));
        columnas.add(col("Asunto", "asunto"));
        columnas.add(col("Ultima Modificacion", "fechaModificacion"));
        columnas.add(col("Acciones", null));

        Map<String, Object> filtros = new LinkedHashMap<>();
        if (q != null && !q.isBlank()) {
            filtros.put("q", q);
        }

        return new TableModel(
                "tabla-plantillas",
                "/api/app/email-templates/table",
                columnas,
                filas,
                sort,
                "desc".equalsIgnoreCase(dir) ? "desc" : "asc",
                w.page(),
                w.size(),
                total,
                w.totalPages(),
                pageWindow(w.page(), w.totalPages()),
                filtros,
                q);
    }

    /** Column definition helper (label + nullable sort key) as a map. */
    private static Map<String, Object> col(@Nonnull String label, @Nullable String key) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("label", label);
        map.put("key", key);
        return map;
    }

    /** Server-computed pager window: current ±2 clamped to [1,totalPages]. */
    private static List<Integer> pageWindow(int page, int totalPages) {
        if (totalPages <= 1) {
            return List.of(1);
        }
        List<Integer> pages = new ArrayList<>();
        int from = Math.max(1, page - 2);
        int to = Math.min(totalPages, page + 2);
        for (int i = from; i <= to; i++) {
            pages.add(i);
        }
        return pages;
    }

    private record Window(int page, int size, int from, int to, int totalPages) {}

    /** Clamped 1-based window over an in-memory result (Qute can't divide). */
    private static Window windowOf(long total, int page, int size) {
        int s = Math.min(Math.max(size, 1), 100);
        int totalPages = (int) Math.max(1L, (total + s - 1) / s);
        int p = Math.min(Math.max(page, 1), totalPages);
        int from = Math.min((p - 1) * s, (int) total);
        int to = Math.min(from + s, (int) total);
        return new Window(p, s, from, to, totalPages);
    }

    private static <T> List<T> orEmpty(@Nullable List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }

    /**
     * Legacy {@code matchesFilter()} parity over ALL text columns
     * (nombre/tipo/asunto contains, case-insensitive) — deliberately broader
     * than the JSON list's nombre-only {@code q}, which keeps its own
     * documented contract.
     */
    private static List<EmailTemplate> filterTemplates(@Nonnull List<EmailTemplate> source,
                                                       @Nullable String q) {
        if (q == null || q.trim().isEmpty()) {
            return new ArrayList<>(source);
        }
        String needle = q.trim().toLowerCase(Locale.ROOT);
        List<EmailTemplate> out = new ArrayList<>();
        for (EmailTemplate t : source) {
            if (matches(t.getNombre(), needle) || matches(t.getTipo(), needle)
                    || matches(t.getAsunto(), needle)) {
                out.add(t);
            }
        }
        return out;
    }

    private static boolean matches(@Nullable String value, @Nonnull String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    /** Typed comparator dispatch over a whitelisted key set. */
    private static void sortTemplates(@Nonnull List<EmailTemplate> rows,
                                      @Nullable String sort, @Nullable String dir) {
        if (rows.isEmpty() || sort == null || sort.isBlank()) {
            return;
        }
        Comparator<EmailTemplate> cmp;
        switch (sort) {
            case "id":
                cmp = Comparator.comparing(EmailTemplate::getId,
                        Comparator.nullsLast(Comparator.naturalOrder()));
                break;
            case "nombre":
                cmp = Comparator.comparing(EmailTemplate::getNombre,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
                break;
            case "tipo":
                cmp = Comparator.comparing(EmailTemplate::getTipo,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
                break;
            case "asunto":
                cmp = Comparator.comparing(EmailTemplate::getAsunto,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
                break;
            case "status":
                cmp = Comparator.comparing(EmailTemplate::isStatus);
                break;
            case "fechaModificacion":
                cmp = Comparator.comparing(EmailTemplate::getFechaModificacion,
                        Comparator.nullsLast(Comparator.naturalOrder()));
                break;
            default:
                return;
        }
        rows.sort("desc".equalsIgnoreCase(dir) ? cmp.reversed() : cmp);
    }

    /** Legacy initTipoOptions() parity: enum name + Spanish description pairs. */
    private static List<Map<String, Object>> tipoOptions() {
        List<Map<String, Object>> options = new ArrayList<>();
        for (EmailTemplateTipo tipo : EmailTemplateTipo.values()) {
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("value", tipo.name());
            option.put("label", tipo.getDescripcion());
            options.add(option);
        }
        return options;
    }

    /** Legacy getTipoLabel() parity. */
    private static String tipoLabel(@Nullable String tipo) {
        if (tipo == null) {
            return "";
        }
        try {
            return EmailTemplateTipo.valueOf(tipo).getDescripcion();
        } catch (IllegalArgumentException e) {
            return tipo;
        }
    }

    /** Legacy getTipoBadgeClass() parity. */
    private static String tipoBadge(@Nullable String tipo) {
        if (tipo == null) {
            return "is-dark";
        }
        switch (tipo) {
            case "REPORTES": return "is-info";
            case "ALERTAS_STOCK": return "is-danger";
            case "NOTIFICACIONES": return "is-warning";
            case "PERSONALIZADO": return "is-success";
            default: return "is-dark";
        }
    }

    /** Legacy f:convertDateTime pattern="dd/MM/yyyy HH:mm" parity. */
    @Nullable
    private static String formatFecha(@Nullable Date date) {
        return date == null ? null : new SimpleDateFormat("dd/MM/yyyy HH:mm").format(date);
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
