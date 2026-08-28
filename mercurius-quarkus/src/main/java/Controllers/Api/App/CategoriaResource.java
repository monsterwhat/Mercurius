package Controllers.Api.App;

import Models.Departamento;
import Models.DepartamentoMetrico;
import Models.DTO.ApiResponse;
import Models.DTO.DepartamentoDTO;
import Models.DTO.DepartamentoMetricoDTO;
import Models.DTO.FamiliaDTO;
import Models.DTO.PagedResponse;
import Models.Enums.Tipo_SoftDelete;
import Models.Familia;
import Models.Users;
import Services.DepartamentoMetricoService;
import Services.DepartamentoService;
import Services.FamiliaService;
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
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
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
 * Categorías module for the NEW Qute/HTMX app surface (plan task T18):
 * JSON CRUD + server-rendered table fragments replacing the legacy JSF trio
 * {@code Controllers.DepartamentoController} (Categorias tab +
 * Departamentos/Reportes), {@code Controllers.FamiliaController}
 * (Categorias tab + Familias/Reportes) and
 * {@code Controllers.DepartamentoMetricoController} (Métricas de
 * Proveedores subpage).
 *
 * <p><b>Behavior parity contract</b> (ported 1:1, receipts in
 * .omo/evidence/t18/):</p>
 * <ul>
 *   <li>Departamento create: {@code status=true}, usuario = current user,
 *       {@link DepartamentoService#create} → "Se creó el departamento".</li>
 *   <li>Departamento update: usuario refreshed,
 *       {@link DepartamentoService#update} (the service forces status=true —
 *       preserved quirk) → "Se actualizó el departamento!".</li>
 *   <li>Familia create: {@code status=true}, usuario+fecha set,
 *       {@link FamiliaService#createIfNotExists}; duplicate nombre → legacy
 *       warning "Ya existe una familia con ese nombre!" surfaced as 409.</li>
 *   <li>Familia update: usuario+fecha refreshed,
 *       {@link FamiliaService#updateAndDisable} → "Se edito la familia"
 *       (the service archives the previous row — preserved quirk).</li>
 *   <li>Soft delete (both domains): {@code softDelete} toggling
 *       {@link Tipo_SoftDelete#DEACTIVATED}/{@link Tipo_SoftDelete#ACTIVATED},
 *       translated to the exact legacy FacesMessages ("Se desactivo el
 *       departamento!"/"Se activo el departamento!"/"Se desactivo la
 *       familia!"/"Se activo la familia!") plus the same
 *       {@code alertas.registrarAlerta} audit entries with DiffUtils
 *       snapshots.</li>
 *   <li>Métricas: read model of
 *       {@link DepartamentoMetricoService#listAll()} (+ avgScore /
 *       sumMontoTotalCompras summary cards) and the admin-only
 *       recalculation trigger that used to live on the JSF page.</li>
 * </ul>
 *
 * <p><b>Paging/sorting contract</b> follows docs/ui-kit.md §3.1:
 * {@code page} is 1-based (default 1), {@code size} default 20,
 * {@code sort} drawn from a per-domain whitelist (null = service order),
 * {@code dir} asc|desc. Sorting/paging/filtering is computed in memory over
 * the existing service queries because the Services layer must not be
 * modified by this task.</p>
 *
 * <p><b>Fragment dual-mode:</b> every endpoint backing a UI surface checks
 * the {@code HX-Request} header — when present it renders ONLY the requested
 * HTML fragment (data-table include, modal-body form redisplay + out-of-band
 * toast); when absent it behaves as a plain JSON API following the
 * {@link ApiResponse}/{@link PagedResponse} envelopes.</p>
 *
 * <p><b>Authorization:</b> {@code admin} or {@code inventario} (the module's
 * managing roles); the métricas recalculation narrows to {@code admin},
 * matching the legacy {@code rendered="#{SessionController.admin}"} gate.
 * The {@code /api/app/*} surface additionally requires any authenticated
 * user through the T13 permission policy.</p>
 */
@Path("/api/app/categorias")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"admin", "inventario"})
@Tag(name = "App - Categorías")
public class CategoriaResource {

    private static final Logger LOG = Logger.getLogger(CategoriaResource.class.getName());

    private static final String TAB_FAMILIAS = "familias";
    private static final String TAB_DEPARTAMENTOS = "departamentos";

    /** Legacy required-message parity (CrearDepartamentoDialog.xhtml). */
    private static final String MSG_NOMBRE_REQUERIDO = "El nombre no puede estar vacío";
    /** Legacy duplicate-name warning parity (FamiliaController.createFamiliaDialog). */
    private static final String MSG_FAMILIA_DUPLICADA = "Ya existe una familia con ese nombre!";

    @Nonnull
    @Inject
    DepartamentoService departamentoService;

    @Nonnull
    @Inject
    FamiliaService familiaService;

    @Nonnull
    @Inject
    DepartamentoMetricoService departamentoMetricoService;

    
    @Nonnull
    @Inject
    LoginService loginService;

    @Inject
    @Nonnull
    SecurityIdentity identity;

    /** Request context (quarkus-rest injectable) — source of HX-Request. */
    @Nonnull
    @Inject
    RoutingContext routing;

    // Templates (rendered to String: no quarkus-rest-qute MessageBodyWriter
    // on this stack — same approach as LoginPageResource, T14).
    @Nonnull
    @Location("pages/categorias/index.html")
    @Inject
    Template pageIndex;

    @Nonnull
    @Location("pages/categorias/tabla-familias.html")
    @Inject
    Template tablaFamilias;

    @Nonnull
    @Location("pages/categorias/tabla-departamentos.html")
    @Inject
    Template tablaDepartamentos;

    @Nonnull
    @Location("pages/categorias/form-familia.html")
    @Inject
    Template formFamilia;

    @Nonnull
    @Location("pages/categorias/form-departamento.html")
    @Inject
    Template formDepartamento;

    // ════════════════════════════════════════════════════════════════════
    // JSON list endpoints (paginated, sorted, filterable)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Paginated familia list ({@code page} is 1-based per the _kit contract).
     * {@code q} reproduces the legacy global filter (nombre / id / username
     * contains, case-insensitive).
     */
    @GET
    @Path("/familias")
    @Operation(summary = "List familias with pagination, sorting and global filter")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Paginated familias"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Role not allowed"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response listFamilias(
            @QueryParam("page") @DefaultValue("1") @Parameter(description = "Page number (1-based)") int page,
            @QueryParam("size") @DefaultValue("20") @Parameter(description = "Page size") int size,
            @QueryParam("sort") @Nullable @Parameter(description = "Sort key: id|nombre|status|fecha|usuario") String sort,
            @QueryParam("dir") @DefaultValue("asc") @Parameter(description = "Sort direction: asc|desc") String dir,
            @QueryParam("q") @Nullable @Parameter(description = "Global filter text") String q) {
        try {
            List<Familia> all = orEmpty(familiaService.listAll());
            List<Familia> filtered = filterFamilias(all, q);
            sortEntities(filtered, sort, dir);
            long total = filtered.size();
            Window w = windowOf(total, page, size);
            List<FamiliaDTO> data = filtered.subList(w.from(), w.to()).stream()
                    .map(CategoriaResource::toDTO).toList();
            return Response.ok(new PagedResponse<>(data, total, w.page(), w.size())).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error listing familias", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error listando las familias"))
                    .build();
        }
    }

    /** Paginated departamento list (same contract as {@link #listFamilias}). */
    @GET
    @Path("/departamentos")
    @Operation(summary = "List departamentos with pagination, sorting and global filter")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Paginated departamentos"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Role not allowed"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response listDepartamentos(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("sort") @Nullable String sort,
            @QueryParam("dir") @DefaultValue("asc") String dir,
            @QueryParam("q") @Nullable String q) {
        try {
            List<Departamento> all = orEmpty(departamentoService.listAll());
            List<Departamento> filtered = filterDepartamentos(all, q);
            sortEntities(filtered, sort, dir);
            long total = filtered.size();
            Window w = windowOf(total, page, size);
            List<DepartamentoDTO> data = filtered.subList(w.from(), w.to()).stream()
                    .map(CategoriaResource::toDTO).toList();
            return Response.ok(new PagedResponse<>(data, total, w.page(), w.size())).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error listing departamentos", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error listando los departamentos"))
                    .build();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Familias CRUD
    // ════════════════════════════════════════════════════════════════════

    /**
     * Creates a familia — legacy {@code createFamiliaDialog()} parity:
     * status forced true, duplicate nombre rejected with the legacy warning.
     */
    @POST
    @Path("/familias")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Create a familia (legacy createFamiliaDialog parity)")
    @APIResponses({
        @APIResponse(responseCode = "201", description = "Created"),
        @APIResponse(responseCode = "400", description = "Blank nombre"),
        @APIResponse(responseCode = "409", description = "Duplicate nombre"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response createFamilia(@Nullable FamiliaDTO body) {
        return doCreateFamilia(nombreOf(body == null ? null : body.getNombre()));
    }

    /**
     * Form-urlencoded twin of {@link #createFamilia} for the HTMX dialog
     * forms (JAX-RS selects by Content-Type).
     */
    @POST
    @Path("/familias")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Operation(summary = "Create a familia from an HTMX form", hidden = true)
    public Response createFamiliaForm(@FormParam("nombre") @Nullable String nombre) {
        return doCreateFamilia(nombreOf(nombre));
    }

    private Response doCreateFamilia(@Nonnull String nombre) {
        if (nombre.isEmpty()) {
            return formFailure(TAB_FAMILIAS, "crear", "error", MSG_NOMBRE_REQUERIDO);
        }
        try {
            Familia familia = new Familia();
            familia.setNombre(nombre);
            familia.setStatus(true); // legacy: newFamilia.setStatus(true)
            familia.setUsuario(currentUser());
            familia.setFecha(new Date()); // legacy sets fecha explicitly
            boolean created = familiaService.createIfNotExists(familia);
            if (!created) {
                return formFailure(TAB_FAMILIAS, "crear", "warn", MSG_FAMILIA_DUPLICADA);
            }
                        LOG.info("Se ha creado la familia: " + nombre + " | user=" + String.valueOf(currentUser()) + " | source=" + "CategoriaResource.createFamilia" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(familia.toString()));
            if (isHxRequest()) {
                // Success in the dialog flow: send the client back to the
                // page so table, counters and modal state reset (ui-kit §5).
                return hxRedirect("/api/app/categorias/table?tab=" + TAB_FAMILIAS);
            }
            return Response.status(Response.Status.CREATED)
                    .entity(ApiResponse.ok(toDTO(familia))).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error creating familia", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error creando la familia"))
                    .build();
        }
    }

    /**
     * Updates a familia — legacy {@code updateFamiliaDialog()} parity:
     * usuario+fecha refreshed, {@link FamiliaService#updateAndDisable}.
     */
    @PUT
    @Path("/familias/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Update a familia (legacy updateFamiliaDialog parity)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Updated"),
        @APIResponse(responseCode = "400", description = "Blank nombre"),
        @APIResponse(responseCode = "404", description = "Unknown id"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response updateFamilia(@PathParam("id") int id, @Nullable FamiliaDTO body) {
        return doUpdateFamilia(id, nombreOf(body == null ? null : body.getNombre()));
    }

    /** Form-urlencoded twin of {@link #updateFamilia} for the HTMX dialogs. */
    @PUT
    @Path("/familias/{id}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Operation(summary = "Update a familia from an HTMX form", hidden = true)
    public Response updateFamiliaForm(@PathParam("id") int id,
                                      @FormParam("nombre") @Nullable String nombre) {
        return doUpdateFamilia(id, nombreOf(nombre));
    }

    private Response doUpdateFamilia(int id, @Nonnull String nombre) {
        if (nombre.isEmpty()) {
            return formFailure(TAB_FAMILIAS, "editar", "error", MSG_NOMBRE_REQUERIDO);
        }
        try {
            Familia existing = familiaService.findById(id);
            if (existing == null) {
                return notFound("No se encontró la familia solicitada");
            }
            String antes = DiffUtils.snapshotEntity(existing);
            existing.setNombre(nombre);
            existing.setUsuario(currentUser());
            existing.setFecha(new Date());
            familiaService.updateAndDisable(existing);
                        LOG.info("Se ha actualizado la familia: " + nombre + " | user=" + String.valueOf(currentUser()) + " | source=" + "CategoriaResource.updateFamilia" + " | antes=" + String.valueOf(antes) + " | despues=" + String.valueOf(DiffUtils.snapshotEntity(existing)));
            if (isHxRequest()) {
                return hxRedirect("/api/app/categorias/table?tab=" + TAB_FAMILIAS);
            }
            Familia updated = familiaService.findById(id);
            if (updated == null) {
                return notFound("No se encontró la familia solicitada");
            }
            return Response.ok(ApiResponse.ok(toDTO(updated))).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error updating familia " + id, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error actualizando la familia"))
                    .build();
        }
    }

    /**
     * Soft-delete toggle — legacy {@code deleteFamilia()} parity: flips
     * status via the T3 {@link Tipo_SoftDelete} result and answers with the
     * exact legacy message. With {@code HX-Request} it returns the refreshed
     * table fragment plus an out-of-band toast instead of JSON.
     */
    @DELETE
    @Path("/familias/{id}")
    @Operation(summary = "Toggle familia soft-delete state (legacy deleteFamilia parity)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Toggled (or HTML fragment when HX-Request)"),
        @APIResponse(responseCode = "404", description = "Unknown id"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response softDeleteFamilia(@PathParam("id") int id) {
        try {
            Familia existing = familiaService.findById(id);
            if (existing == null) {
                return notFound("No se encontró la familia solicitada");
            }
            String antes = DiffUtils.snapshotEntity(existing);
            Tipo_SoftDelete resultado = familiaService.softDelete(existing);
            if (resultado == null) {
                return notFound("No se encontró la familia solicitada");
            }
            String mensaje = resultado == Tipo_SoftDelete.DEACTIVATED
                    ? "Se desactivo la familia!" : "Se activo la familia!";
                        LOG.info("Se ha eliminado la familia: " + existing.getNombre() + " | user=" + String.valueOf(currentUser()) + " | source=" + "CategoriaResource.softDeleteFamilia" + " | antes=" + String.valueOf(antes) + " | despues=" + String.valueOf(DiffUtils.snapshotEntity(existing)));
            if (isHxRequest()) {
                return tableFragment(TAB_FAMILIAS, 1, 20, null, "asc", null,
                        resultado == Tipo_SoftDelete.DEACTIVATED ? "warn" : "info", mensaje);
            }
            return Response.ok(ApiResponse.ok(
                    new SoftDeleteResult(resultado.name(), mensaje))).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error toggling familia " + id, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error cambiando el estado de la familia"))
                    .build();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Departamentos CRUD
    // ════════════════════════════════════════════════════════════════════

    /** Creates a departamento — legacy {@code createDepartamento()} parity. */
    @POST
    @Path("/departamentos")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Create a departamento (legacy createDepartamento parity)")
    @APIResponses({
        @APIResponse(responseCode = "201", description = "Created"),
        @APIResponse(responseCode = "400", description = "Blank nombre"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response createDepartamento(@Nullable DepartamentoDTO body) {
        return doCreateDepartamento(
                nombreOf(body == null ? null : body.getNombre()),
                body == null ? null : body.getContactoNombre(),
                body == null ? null : body.getContactoTelefono(),
                body == null ? null : body.getContactoEmail(),
                body == null ? null : body.getPlazoPagoDias(),
                body == null ? null : body.getTiempoEntregaDias(),
                body == null ? null : body.getNotas());
    }

    /** Form-urlencoded twin of {@link #createDepartamento} (HTMX dialogs). */
    @POST
    @Path("/departamentos")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Operation(summary = "Create a departamento from an HTMX form", hidden = true)
    public Response createDepartamentoForm(
            @FormParam("nombre") @Nullable String nombre,
            @FormParam("contactoNombre") @Nullable String contactoNombre,
            @FormParam("contactoTelefono") @Nullable String contactoTelefono,
            @FormParam("contactoEmail") @Nullable String contactoEmail,
            @FormParam("plazoPagoDias") @Nullable String plazoPagoDias,
            @FormParam("tiempoEntregaDias") @Nullable String tiempoEntregaDias,
            @FormParam("notas") @Nullable String notas) {
        return doCreateDepartamento(nombreOf(nombre), contactoNombre, contactoTelefono,
                contactoEmail, parseIntOrNull(plazoPagoDias), parseIntOrNull(tiempoEntregaDias),
                emptyToNull(notas));
    }

    private Response doCreateDepartamento(@Nonnull String nombre, @Nullable String contactoNombre,
                                          @Nullable String contactoTelefono, @Nullable String contactoEmail,
                                          @Nullable Integer plazoPagoDias, @Nullable Integer tiempoEntregaDias,
                                          @Nullable String notas) {
        if (nombre.isEmpty()) {
            return formFailure(TAB_DEPARTAMENTOS, "crear", "error", MSG_NOMBRE_REQUERIDO);
        }
        try {
            Departamento departamento = new Departamento();
            departamento.setNombre(nombre);
            departamento.setContactoNombre(contactoNombre);
            departamento.setContactoTelefono(contactoTelefono);
            departamento.setContactoEmail(contactoEmail);
            departamento.setPlazoPagoDias(plazoPagoDias);
            departamento.setTiempoEntregaDias(tiempoEntregaDias);
            departamento.setNotas(notas);
            departamento.setStatus(true); // legacy: newDepartamento.setStatus(true)
            departamento.setUsuario(currentUser());
            departamentoService.create(departamento);
                        LOG.info("Se creó el departamento: " + nombre + " | user=" + String.valueOf(currentUser()) + " | source=" + "CategoriaResource.createDepartamento" + " | antes=" + String.valueOf("") + " | despues=" + String.valueOf(departamento.toString()));
            if (isHxRequest()) {
                return hxRedirect("/api/app/categorias/table?tab=" + TAB_DEPARTAMENTOS);
            }
            return Response.status(Response.Status.CREATED)
                    .entity(ApiResponse.ok(toDTO(departamento))).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error creating departamento", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error creando el departamento"))
                    .build();
        }
    }

    /** Updates a departamento — legacy {@code updateDepartamento()} parity. */
    @PUT
    @Path("/departamentos/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Update a departamento (legacy updateDepartamento parity)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Updated"),
        @APIResponse(responseCode = "400", description = "Blank nombre"),
        @APIResponse(responseCode = "404", description = "Unknown id"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response updateDepartamento(@PathParam("id") int id, @Nullable DepartamentoDTO body) {
        return doUpdateDepartamento(id,
                nombreOf(body == null ? null : body.getNombre()),
                body == null ? null : body.getContactoNombre(),
                body == null ? null : body.getContactoTelefono(),
                body == null ? null : body.getContactoEmail(),
                body == null ? null : body.getPlazoPagoDias(),
                body == null ? null : body.getTiempoEntregaDias(),
                body == null ? null : body.getNotas());
    }

    /** Form-urlencoded twin of {@link #updateDepartamento} (HTMX dialogs). */
    @PUT
    @Path("/departamentos/{id}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Operation(summary = "Update a departamento from an HTMX form", hidden = true)
    public Response updateDepartamentoForm(
            @PathParam("id") int id,
            @FormParam("nombre") @Nullable String nombre,
            @FormParam("contactoNombre") @Nullable String contactoNombre,
            @FormParam("contactoTelefono") @Nullable String contactoTelefono,
            @FormParam("contactoEmail") @Nullable String contactoEmail,
            @FormParam("plazoPagoDias") @Nullable String plazoPagoDias,
            @FormParam("tiempoEntregaDias") @Nullable String tiempoEntregaDias,
            @FormParam("notas") @Nullable String notas) {
        return doUpdateDepartamento(id, nombreOf(nombre), contactoNombre, contactoTelefono,
                contactoEmail, parseIntOrNull(plazoPagoDias), parseIntOrNull(tiempoEntregaDias),
                emptyToNull(notas));
    }

    private Response doUpdateDepartamento(int id, @Nonnull String nombre, @Nullable String contactoNombre,
                                          @Nullable String contactoTelefono, @Nullable String contactoEmail,
                                          @Nullable Integer plazoPagoDias, @Nullable Integer tiempoEntregaDias,
                                          @Nullable String notas) {
        if (nombre.isEmpty()) {
            return formFailure(TAB_DEPARTAMENTOS, "editar", "error", MSG_NOMBRE_REQUERIDO);
        }
        try {
            Departamento existing = departamentoService.findById(id);
            if (existing == null) {
                return notFound("No se encontró el departamento solicitado");
            }
            String antes = DiffUtils.snapshotEntity(existing);
            existing.setNombre(nombre);
            existing.setContactoNombre(contactoNombre);
            existing.setContactoTelefono(contactoTelefono);
            existing.setContactoEmail(contactoEmail);
            existing.setPlazoPagoDias(plazoPagoDias);
            existing.setTiempoEntregaDias(tiempoEntregaDias);
            existing.setNotas(notas);
            existing.setUsuario(currentUser());
            departamentoService.update(existing);
                        LOG.info("Se actualizó el departamento: " + nombre + " | user=" + String.valueOf(currentUser()) + " | source=" + "CategoriaResource.updateDepartamento" + " | antes=" + String.valueOf(antes) + " | despues=" + String.valueOf(DiffUtils.snapshotEntity(existing)));
            if (isHxRequest()) {
                return hxRedirect("/api/app/categorias/table?tab=" + TAB_DEPARTAMENTOS);
            }
            Departamento updated = departamentoService.findById(id);
            if (updated == null) {
                return notFound("No se encontró el departamento solicitado");
            }
            return Response.ok(ApiResponse.ok(toDTO(updated))).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error updating departamento " + id, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error actualizando el departamento"))
                    .build();
        }
    }

    /** Soft-delete toggle — legacy {@code deleteDepartamento()} parity. */
    @DELETE
    @Path("/departamentos/{id}")
    @Operation(summary = "Toggle departamento soft-delete state (legacy deleteDepartamento parity)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Toggled (or HTML fragment when HX-Request)"),
        @APIResponse(responseCode = "404", description = "Unknown id"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response softDeleteDepartamento(@PathParam("id") int id) {
        try {
            Departamento existing = departamentoService.findById(id);
            if (existing == null) {
                return notFound("No se encontró el departamento solicitado");
            }
            String antes = DiffUtils.snapshotEntity(existing);
            Tipo_SoftDelete resultado = departamentoService.softDelete(existing);
            if (resultado == null) {
                return notFound("No se encontró el departamento solicitado");
            }
            String mensaje = resultado == Tipo_SoftDelete.DEACTIVATED
                    ? "Se desactivo el departamento!" : "Se activo el departamento!";
                        LOG.info("Se eliminó el departamento: " + existing.getNombre() + " | user=" + String.valueOf(currentUser()) + " | source=" + "CategoriaResource.softDeleteDepartamento" + " | antes=" + String.valueOf(antes) + " | despues=" + String.valueOf(DiffUtils.snapshotEntity(existing)));
            if (isHxRequest()) {
                return tableFragment(TAB_DEPARTAMENTOS, 1, 20, null, "asc", null,
                        resultado == Tipo_SoftDelete.DEACTIVATED ? "warn" : "info", mensaje);
            }
            return Response.ok(ApiResponse.ok(
                    new SoftDeleteResult(resultado.name(), mensaje))).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error toggling departamento " + id, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error cambiando el estado del departamento"))
                    .build();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Métricas de Proveedores (legacy DepartamentoMetricoController)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Read model of the stored supplier metrics plus the summary cards of
     * the legacy Métricas de Proveedores page (total proveedores, score
     * promedio, compras totales).
     */
    @GET
    @Path("/metricas")
    @Operation(summary = "Supplier metrics read model (legacy DepartamentoMetricoController parity)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Metrics + summary"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response metricas() {
        try {
            List<DepartamentoMetrico> metricas = orEmpty(departamentoMetricoService.listAll());
            List<DepartamentoMetricoDTO> data = metricas.stream()
                    .map(CategoriaResource::toDTO).toList();
            MetricasResponse payload = new MetricasResponse(
                    new MetricasResumen(
                            data.size(),
                            departamentoMetricoService.avgScore(),
                            departamentoMetricoService.sumMontoTotalCompras()),
                    data);
            return Response.ok(ApiResponse.ok(payload)).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error leyendo métricas de proveedores", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error leyendo las métricas"))
                    .build();
        }
    }

    /**
     * Recalculates all supplier metrics — the legacy page's
     * {@code calcularMetricas} action, admin-gated there via
     * {@code rendered="#{SessionController.admin}"}; narrowed here with a
     * method-level role check (most restrictive wins over the class gate).
     */
    @POST
    @Path("/metricas/recalcular")
    @RolesAllowed("admin")
    @Operation(summary = "Recalculate all supplier metrics (admin only, legacy parity)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Recalculated"),
        @APIResponse(responseCode = "403", description = "Non-admin role"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response recalcularMetricas() {
        try {
            departamentoMetricoService.calcularTodasLasMetricas();
            if (isHxRequest()) {
                return hxRedirect("/api/app/categorias/table?tab=" + TAB_DEPARTAMENTOS);
            }
            return Response.ok(ApiResponse.ok(
                    Map.of("mensaje", "Métricas recalculadas correctamente"))).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error recalculando métricas", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error recalculando las métricas"))
                    .build();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Fragment endpoint (docs/ui-kit.md §2.9 dual-mode contract)
    // ════════════════════════════════════════════════════════════════════

    /**
     * GET /table?page&size&sort&dir&tab&q — with the {@code HX-Request}
     * header returns ONLY the requested data-table include (fragment swap
     * into the page's table container); without it renders the FULL page.
     * This mirrors the SERVER-SIDE CONTRACT comment of
     * {@code templates/_kit/data-table.html} exactly: the same endpoint
     * renders page and fragments, and all paging/sorting state lives in
     * the URL.
     *
     * @param tab which table to swap: familias (default) | departamentos
     */
    @GET
    @Path("/table")
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Data-table fragment (HX-Request) or full categorías page")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "HTML fragment or full page"),
        @APIResponse(responseCode = "500", description = "Template rendering failure")
    })
    public Response table(
            @QueryParam("tab") @DefaultValue(TAB_FAMILIAS) String tab,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("sort") @Nullable String sort,
            @QueryParam("dir") @DefaultValue("asc") String dir,
            @QueryParam("q") @Nullable String q) {
        try {
            if (isHxRequest()) {
                return tableFragment(
                        TAB_DEPARTAMENTOS.equalsIgnoreCase(tab) ? TAB_DEPARTAMENTOS : TAB_FAMILIAS,
                        page, size, sort, dir, q, null, null);
            }
            return htmlOk(renderFullPage());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error renderizando la página de categorías", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error renderizando la página"))
                    .build();
        }
    }

    // ── Modal-body form endpoints (hx-get targets of _kit/modal) ────────

    /** Empty familia creation form (modal body). */
    @GET
    @Path("/formularios/familia/nueva")
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "New-familia form fragment (modal body)")
    public Response formNuevaFamilia() {
        return htmlOk(formFamilia
                .data("modo", "crear")
                .data("familia", null)
                .data("errorNombre", null)
                .data("toastSeverity", null)
                .data("toastMessage", null));
    }

    /** Prefilled familia edit form (modal body). via id lookup. */
    @GET
    @Path("/formularios/familia/{id}")
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Edit-familia form fragment (modal body)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Form HTML"),
        @APIResponse(responseCode = "404", description = "Unknown id")
    })
    public Response formEditarFamilia(@PathParam("id") int id) {
        Familia familia = familiaService.findById(id);
        if (familia == null) {
            return notFound("No se encontró la familia solicitada");
        }
        return htmlOk(formFamilia
                .data("modo", "editar")
                .data("familia", familia)
                .data("errorNombre", null)
                .data("toastSeverity", null)
                .data("toastMessage", null));
    }

    /** Empty departamento creation form (modal body). */
    @GET
    @Path("/formularios/departamento/nueva")
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "New-departamento form fragment (modal body)")
    public Response formNuevoDepartamento() {
        return htmlOk(formDepartamento
                .data("modo", "crear")
                .data("departamento", null)
                .data("errorNombre", null)
                .data("toastSeverity", null)
                .data("toastMessage", null));
    }

    /** Prefilled departamento edit form (modal body). */
    @GET
    @Path("/formularios/departamento/{id}")
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Edit-departamento form fragment (modal body)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Form HTML"),
        @APIResponse(responseCode = "404", description = "Unknown id")
    })
    public Response formEditarDepartamento(@PathParam("id") int id) {
        Departamento departamento = departamentoService.findById(id);
        if (departamento == null) {
            return notFound("No se encontró el departamento solicitado");
        }
        return htmlOk(formDepartamento
                .data("modo", "editar")
                .data("departamento", departamento)
                .data("errorNombre", null)
                .data("toastSeverity", null)
                .data("toastMessage", null));
    }

    // ════════════════════════════════════════════════════════════════════
    // Request classification & shared responses
    // ════════════════════════════════════════════════════════════════════

    /** True when the call comes from HTMX (layout hx-headers carries it). */
    private boolean isHxRequest() {
        String header = routing.request().getHeader("HX-Request");
        return header != null && !"false".equalsIgnoreCase(header);
    }

    private static String nombreOf(@Nullable String raw) {
        return raw == null ? "" : raw.trim();
    }

    /** Legacy p:inputNumber parity: empty form value → null, not 0. */
    @Nullable
    private static Integer parseIntOrNull(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    private static String emptyToNull(@Nullable String raw) {
        return raw == null || raw.isBlank() ? null : raw;
    }

    private static <T> List<T> orEmpty(@Nullable List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }

    private Response notFound(@Nonnull String mensaje) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(ApiResponse.error("NOT_FOUND", mensaje)).build();
    }

    private static Response htmlOk(@Nonnull TemplateInstance template) {
        return Response.ok(template.render())
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).build();
    }

    /**
     * Failure branch shared by the mutating endpoints: HTMX callers get the
     * redisplayed form + an out-of-band toast (ui-kit.md Pattern A); API
     * callers get the structured envelope (409 for the legacy duplicate
     * warning, 400 otherwise).
     */
    private Response formFailure(@Nonnull String dominio, @Nonnull String modo,
                                 @Nonnull String severity, @Nonnull String mensaje) {
        if (isHxRequest()) {
            TemplateInstance template = TAB_DEPARTAMENTOS.equals(dominio)
                    ? formDepartamento.data("modo", modo).data("departamento", null)
                    : formFamilia.data("modo", modo).data("familia", null);
            template.data("errorNombre", mensaje)
                    .data("toastSeverity", severity)
                    .data("toastMessage", mensaje);
            return Response.status(Response.Status.BAD_REQUEST)
                    .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                    .entity(template.render()).build();
        }
        boolean duplicado = MSG_FAMILIA_DUPLICADA.equals(mensaje);
        return Response.status(duplicado
                        ? Response.Status.CONFLICT : Response.Status.BAD_REQUEST)
                .entity(ApiResponse.error(
                        duplicado ? "DUPLICATE_NAME" : "VALIDATION_ERROR", mensaje))
                .build();
    }

    /** HTMX redirect: the client navigates and the page re-renders fresh. */
    private static Response hxRedirect(@Nonnull String url) {
        return Response.status(Response.Status.OK)
                .header("HX-Redirect", url)
                .build();
    }

    // ════════════════════════════════════════════════════════════════════
    // Template models
    // ════════════════════════════════════════════════════════════════════

    /**
     * Renders ONLY one data-table include (the fragment swap target).
     * Model keys mirror the _kit/data-table DATA CONTRACT verbatim:
     * id, baseUrl, headers(columnas), rows(filas via the {#rows} slot),
     * sortKey, sortDir, page, size, total, totalPages, pages(paginas),
     * params(filtros — reserved keys excluded).
     */
    private Response tableFragment(@Nonnull String tab, int page, int size,
                                   @Nullable String sort, @Nullable String dir,
                                   @Nullable String q,
                                   @Nullable String toastSeverity, @Nullable String toastMessage) {
        TableModel model = buildTableModel(tab, page, size, sort, dir, q);
        Template template = TAB_DEPARTAMENTOS.equals(tab) ? tablaDepartamentos : tablaFamilias;
        return htmlOk(template
                .data("modelo", model.asMap())
                .data("q", model.q())
                .data("toastSeverity", toastSeverity)
                .data("toastMessage", toastMessage));
    }

    /** Full-page model: both tables + stat counters + metrics summary. */
    private TemplateInstance renderFullPage() {
        TableModel tf = buildTableModel(TAB_FAMILIAS, 1, 20, null, "asc", null);
        TableModel td = buildTableModel(TAB_DEPARTAMENTOS, 1, 20, null, "asc", null);

        List<Map<String, Object>> metricasFilas = new ArrayList<>();
        for (DepartamentoMetrico m : orEmpty(departamentoMetricoService.listAll())) {
            Map<String, Object> fila = new LinkedHashMap<>();
            fila.put("id", m.getId());
            fila.put("proveedor", m.getDepartamento() != null && m.getDepartamento().getNombre() != null
                    ? m.getDepartamento().getNombre() : "Sin nombre");
            fila.put("score", m.getScore());
            fila.put("scoreSeverity", scoreSeverity(m.getScore()));
            fila.put("barWidth", barWidth(m.getScore()));
            fila.put("barColor", barColor(m.getScore()));
            fila.put("facturas", m.getTotalFacturasRecibidas());
            fila.put("pagadas", m.getFacturasPagadas());
            fila.put("montoTotal", m.getMontoTotalCompras());
            fila.put("onTime", m.getTasaOnTimeDelivery());
            metricasFilas.add(fila);
        }

        return pageIndex
                .data("familiasTabla", tf.asMap())
                .data("departamentosTabla", td.asMap())
                .data("familiaCount", familiaService.count())
                .data("departamentoCount", departamentoService.count())
                .data("familiasActivasCount", familiaService.countActivas())
                .data("familiasInactivasCount", familiaService.countInactivas())
                .data("departamentosActivosCount", departamentoService.countActivos())
                .data("departamentosInactivosCount", departamentoService.countInactivos())
                .data("isAdmin", isAdmin())
                .data("metricasTotalProveedores", metricasFilas.size())
                .data("metricasScorePromedio", departamentoMetricoService.avgScore())
                .data("metricasComprasTotales", departamentoMetricoService.sumMontoTotalCompras())
                .data("metricasFilas", metricasFilas);
    }

    /** Immutable view of everything one tabla-*.html include needs. */
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

    /** Builds one table's full model (filter → sort → slice → columns). */
    private TableModel buildTableModel(@Nonnull String tab, int page, int size,
                                       @Nullable String sort, @Nullable String dir,
                                       @Nullable String q) {
        boolean departamentos = TAB_DEPARTAMENTOS.equals(tab);
        List<?> filtered = departamentos
                ? filterDepartamentos(orEmpty(departamentoService.listAll()), q)
                : filterFamilias(orEmpty(familiaService.listAll()), q);
        sortEntities(filtered, sort, dir);

        long total = filtered.size();
        Window w = windowOf(total, page, size);
        List<?> filas = filtered.subList(w.from(), w.to());

        // Column definitions as List<Map<String,Object>> with label/key —
        // null key ⇒ non-sortable (docs/ui-kit.md §3.1). Column sets mirror
        // the legacy Categorias tabs + Detalladas report tables.
        List<Map<String, Object>> columnas = new ArrayList<>();
        columnas.add(col("Estado", null));
        columnas.add(col("Nombre", "nombre"));
        if (departamentos) {
            columnas.add(col("Contacto", "contacto"));
            columnas.add(col("Telefono", null));
            columnas.add(col("Email", null));
            columnas.add(col("Plazo Pago", "plazoPagoDias"));
            columnas.add(col("Entrega", "tiempoEntregaDias"));
        }
        columnas.add(col("Creado por", "usuario"));
        columnas.add(col("Acciones", null));

        Map<String, Object> filtros = new LinkedHashMap<>();
        filtros.put("tab", tab);
        if (q != null && !q.isBlank()) {
            filtros.put("q", q);
        }

        return new TableModel(
                departamentos ? "tabla-departamentos" : "tabla-familias",
                "/api/app/categorias/table",
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

    // ── Filtering/sorting (in-memory; Services layer untouched) ─────────

    private static List<Familia> filterFamilias(@Nonnull List<Familia> source, @Nullable String q) {
        if (q == null || q.trim().isEmpty()) {
            return new ArrayList<>(source);
        }
        String needle = q.trim().toLowerCase(Locale.ROOT);
        List<Familia> out = new ArrayList<>();
        for (Familia f : source) {
            if (matches(f.getNombre(), needle)
                    || String.valueOf(f.getId()).contains(needle)
                    || (f.getUsuario() != null && matches(f.getUsuario().getUsername(), needle))) {
                out.add(f);
            }
        }
        return out;
    }

    private static List<Departamento> filterDepartamentos(@Nonnull List<Departamento> source, @Nullable String q) {
        if (q == null || q.trim().isEmpty()) {
            return new ArrayList<>(source);
        }
        String needle = q.trim().toLowerCase(Locale.ROOT);
        List<Departamento> out = new ArrayList<>();
        for (Departamento d : source) {
            if (matches(d.getNombre(), needle)
                    || String.valueOf(d.getId()).contains(needle)
                    || (d.getUsuario() != null && matches(d.getUsuario().getUsername(), needle))
                    || matches(d.getContactoNombre(), needle)
                    || matches(d.getContactoTelefono(), needle)
                    || matches(d.getContactoEmail(), needle)) {
                out.add(d);
            }
        }
        return out;
    }

    private static boolean matches(@Nullable String value, @Nonnull String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    /** Typed comparator dispatch over a whitelisted key set. */
    private static void sortEntities(@Nonnull List<?> entities, @Nullable String sort, @Nullable String dir) {
        if (entities.isEmpty() || sort == null || sort.isBlank()) {
            return;
        }
        Comparator<Object> cmp = comparatorFor(entities.get(0), sort);
        if (cmp != null) {
            entities.sort("desc".equalsIgnoreCase(dir) ? cmp.reversed() : cmp);
        }
    }

    @Nullable
    private static Comparator<Object> comparatorFor(@Nonnull Object sample, @Nonnull String sort) {
        boolean isDep = sample instanceof Departamento;
        switch (sort) {
            case "id":
                return Comparator.comparingInt(o -> isDep ? ((Departamento) o).getId() : ((Familia) o).getId());
            case "nombre":
                return Comparator.comparing(CategoriaResource::nombreDe,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "status":
                return Comparator.comparing(CategoriaResource::statusDe,
                        Comparator.nullsLast(Comparator.naturalOrder()));
            case "fecha":
                return Comparator.comparing(CategoriaResource::fechaDe,
                        Comparator.nullsLast(Comparator.naturalOrder()));
            case "usuario":
                return Comparator.comparing(CategoriaResource::usernameDe,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "contacto":
                return isDep ? Comparator.comparing(
                        o -> ((Departamento) o).getContactoNombre(),
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)) : null;
            case "plazoPagoDias":
                return isDep ? Comparator.comparing(
                        o -> ((Departamento) o).getPlazoPagoDias(),
                        Comparator.nullsLast(Comparator.naturalOrder())) : null;
            case "tiempoEntregaDias":
                return isDep ? Comparator.comparing(
                        o -> ((Departamento) o).getTiempoEntregaDias(),
                        Comparator.nullsLast(Comparator.naturalOrder())) : null;
            default:
                return null;
        }
    }

    private static String nombreDe(Object o) {
        return o instanceof Departamento d ? d.getNombre() : ((Familia) o).getNombre();
    }

    private static Boolean statusDe(Object o) {
        return o instanceof Departamento d ? d.getStatus() : ((Familia) o).getStatus();
    }

    private static Date fechaDe(Object o) {
        return o instanceof Departamento d ? d.getFecha() : ((Familia) o).getFecha();
    }

    private static String usernameDe(Object o) {
        Users u = o instanceof Departamento d ? d.getUsuario() : ((Familia) o).getUsuario();
        return u != null ? u.getUsername() : null;
    }

    // ── DTO mappers (manual, repo convention) ───────────────────────────

    private static FamiliaDTO toDTO(@Nonnull Familia f) {
        return new FamiliaDTO(
                f.getId(),
                f.getNombre(),
                f.getStatus(),
                f.getFecha(),
                f.getUsuario() != null ? f.getUsuario().getId() : null,
                f.getUsuario() != null ? f.getUsuario().getUsername() : null);
    }

    private static DepartamentoDTO toDTO(@Nonnull Departamento d) {
        return new DepartamentoDTO(
                d.getId(),
                d.getNombre(),
                d.getContactoNombre(),
                d.getContactoTelefono(),
                d.getContactoEmail(),
                d.getPlazoPagoDias(),
                d.getTiempoEntregaDias(),
                d.getNotas(),
                d.getStatus(),
                d.getUsuario() != null ? d.getUsuario().getId() : null,
                d.getUsuario() != null ? d.getUsuario().getUsername() : null,
                d.getFecha());
    }

    private static DepartamentoMetricoDTO toDTO(@Nonnull DepartamentoMetrico m) {
        return new DepartamentoMetricoDTO(
                m.getId(),
                m.getDepartamento() != null ? Long.valueOf(m.getDepartamento().getId()) : null,
                m.getDepartamento() != null ? m.getDepartamento().getNombre() : null,
                m.getFechaCalculo(),
                m.getTotalFacturasRecibidas(),
                m.getFacturasPagadas(),
                m.getMontoTotalCompras(),
                m.getMontoPromedioFactura(),
                m.getTiempoEntregaPromedio(),
                m.getTasaOnTimeDelivery(),
                m.getArticulosComprados(),
                m.getScore());
    }

    // ── Legacy presentation helpers (score chips / progress bars) ───────

    /** Legacy getScoreSeverity parity. */
    private static String scoreSeverity(double score) {
        if (score >= 80) {
            return "success";
        }
        if (score >= 60) {
            return "warning";
        }
        return "danger";
    }

    /** Legacy getBarColor parity. */
    private static String barColor(double score) {
        if (score >= 80) {
            return "is-success";
        }
        if (score >= 60) {
            return "is-warning";
        }
        return "is-danger";
    }

    /** Legacy getBarWidth parity. */
    private static String barWidth(double score) {
        return "width: " + Math.min(Math.max(score, 0), 100) + "%";
    }

    // ── Current-user resolution (SessionController.getCurrentUser parity) ──

    /**
     * Resolves the authenticated {@link Users} row the way the legacy
     * SessionController did, through the T12 identity provider's principal.
     * Returns null for anonymous/system contexts (alertas accepts null).
     */
    private Users currentUser() {
        try {
            if (identity.isAnonymous() || identity.getPrincipal() == null) {
                return null;
            }
            return loginService.findByUsername(identity.getPrincipal().getName());
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "No current user resolvable", e);
            return null;
        }
    }

    private boolean isAdmin() {
        return !identity.isAnonymous() && identity.hasRole("admin");
    }

    // ── Small value carriers ────────────────────────────────────────────

    /** Soft-delete toggle outcome surfaced to JSON clients (T3 enum mapped). */
    public record SoftDeleteResult(String resultado, String mensaje) {}

    /** Summary card values of GET /metricas. */
    public record MetricasResumen(long totalProveedores, double scorePromedio, BigDecimal comprasTotales) {}

    /** Payload of GET /metricas: resumen + rows. */
    public record MetricasResponse(MetricasResumen resumen, List<DepartamentoMetricoDTO> metricas) {}
}
