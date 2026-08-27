package Controllers.Api.App;

import Models.Correos.ReporteProgramado;
import Models.Correos.ReportesEnum;
import Models.DTO.ApiResponse;
import Models.DTO.PagedResponse;
import Models.DTO.ReporteProgramadoDTO;
import Services.AlertasService;
import Services.Correos.ReportesProgramadosService;
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
import java.util.Arrays;
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
 * Scheduled-report administration endpoints for the NEW Qute/HTMX app surface
 * (/app world) — strangler-phase REST mirror of the legacy JSF
 * {@code Controllers.Correos.ReportesProgramadosController}.
 *
 * <p><b>Legacy parity</b> (verified against {@code ReportesProgramadosController}
 * createReporte/createReporteDialog/updateReporte/toggleReporte/deleteReporte):</p>
 * <ul>
 *   <li>Creation rejects duplicate perfiles with the legacy warning text
 *       ("Ya existe un reporte programado con ese nombre!") as a 409 — the
 *       legacy guards are {@code findByName(...)} and
 *       {@code createIfNotExists(...)}.</li>
 *   <li>Creation forces {@code status = true} and stamps {@code lastRun = now},
 *       exactly like {@code createReporte()}; delegation goes through
 *       {@link ReportesProgramadosService#create}, which additionally seeds
 *       {@code nextRunTime} when {@code frecuencia} is present (the canonical
 *       legacy creation path).</li>
 *   <li>Updates stamp {@code lastRun = now} on EVERY edit and perform NO
 *       duplicate-perfil re-check, exactly like {@code updateReporte()};
 *       {@code status}/{@code nextRunTime} are left untouched — toggling has
 *       its own endpoint, mirroring the legacy separation.</li>
 *   <li>{@code PUT /{id}/toggle} flips the enabled flag without touching
 *       {@code lastRun}, mirroring {@code toggleReporte()} +
 *       {@code enableReporte()}/{@code disableReporte()}, with the legacy
 *       audit texts preserved verbatim.</li>
 *   <li>Deletion is a HARD delete ({@code deleteReporte()}), with the legacy
 *       audit texts preserved verbatim.</li>
 * </ul>
 *
 * <p><b>Schedule validation</b>: the legacy controller performs NO cron/schedule
 * syntax validation anywhere — {@code frecuencia} is a closed UI picklist
 * (Diario, Semanal, Quincenal, Mensual) that reaches the service unvalidated.
 * This resource mirrors that exactly: only the perfil presence check (legacy
 * JSF required field) plus the duplicate guard are enforced server-side.</p>
 *
 * <p>Role gate mirrors the Correos administration surface: {@code admin} OR
 * {@code tributacion}. Like every {@code /api/app/*} resource, the
 * {@code @RolesAllowed} gates are DORMANT until the form-cookie auth block is
 * enabled in application.properties (see {@link AppAuthResource}).</p>
 *
 * <p>All responses follow the {@link ApiResponse}/{@link PagedResponse}
 * envelope conventions. No {@code FacesContext} anywhere. Write methods carry
 * no resource-level {@code @Transactional} because
 * {@code ReportesProgramadosService} annotates its own transactional
 * create/update/delete (unlike {@code LoginService}, which is why
 * {@link UsersResource} annotates its own).</p>
 */
@Path("/api/app/reportes-programados")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"admin", "tributacion"})
@Tag(name = "App - Reportes Programados")
public class ReporteProgramadoResource {

    private static final Logger LOG = Logger.getLogger(ReporteProgramadoResource.class.getName());

    @Inject
    @Nonnull
    ReportesProgramadosService reportesProgramadosService;

    @Inject
    @Nonnull
    AlertasService alertas;

    @Inject
    @Nonnull
    SecurityIdentity identity;

    /** Request context (quarkus-rest injectable) — source of HX-Request. */
    @Inject
    @Nonnull
    RoutingContext routing;

    // View-half templates (W4B-CORREOS). Rendered to String: no
    // quarkus-rest-qute MessageBodyWriter on this stack — same approach as
    // CategoriaResource/T18 and LoginPageResource/T14.
    @Inject
    @Nonnull
    @Location("pages/correos/reportes.html")
    Template pageIndex;

    @Inject
    @Nonnull
    @Location("pages/correos/tabla-reportes.html")
    Template tablaReportes;

    @Inject
    @Nonnull
    @Location("pages/correos/form-reporte.html")
    Template formReporte;

    /** Paginated scheduled-report listing. */
    @GET
    @Operation(summary = "List scheduled reports with pagination")
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
            @Parameter(description = "Page size (max 100)") int size) {

        // Clamp size to max 100 (SuppliersController/UsersResource convention)
        size = Math.min(Math.max(size, 1), 100);
        page = Math.max(page, 0);

        try {
            long total = reportesProgramadosService.count();
            List<ReporteProgramadoDTO> data = reportesProgramadosService.listPage(page * size, size).stream()
                    .map(reporte -> toDTO(reporte))
                    .toList();
            return Response.ok(new PagedResponse<>(data, total, page, size)).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error listing scheduled reports", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR",
                            "Error listando los reportes programados"))
                    .build();
        }
    }

    /** Fetches one scheduled report by id. */
    @GET
    @Path("/{id}")
    @Operation(summary = "Get a scheduled report by id")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Missing admin/tributacion role"),
        @APIResponse(responseCode = "404", description = "Not found"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response get(@PathParam("id") @Parameter(description = "Scheduled report ID") Long id) {
        try {
            ReporteProgramado reporte = reportesProgramadosService.find(id);
            if (reporte == null) {
                return notFound(id);
            }
            return Response.ok(ApiResponse.ok(toDTO(reporte))).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error fetching scheduled report " + id, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR",
                            "Error obteniendo el reporte programado"))
                    .build();
        }
    }

    /**
     * Creates a scheduled report — createReporte()/createReporteDialog()
     * parity: duplicate-perfil 409 with the legacy warning text, status forced
     * to true, {@code lastRun} stamped now; the service seeds
     * {@code nextRunTime} when frecuencia is present.
     */
    @POST
    @Operation(summary = "Create a scheduled report (duplicate perfiles rejected)")
    @APIResponses({
        @APIResponse(responseCode = "201", description = "Created"),
        @APIResponse(responseCode = "400", description = "Validation error"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Missing admin/tributacion role"),
        @APIResponse(responseCode = "409", description = "Duplicate perfil name"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response create(@Nullable ReporteProgramadoDTO request) {
        try {
            if (request == null || request.getPerfil() == null || request.getPerfil().isBlank()) {
                return badRequest("El perfil del reporte no puede estar vacío.");
            }

            // Parity with createReporte()/createIfNotExists(): duplicate
            // perfiles rejected with the legacy FacesMessage text. NOTE: no
            // cron/schedule syntax validation exists in the legacy controller
            // (frecuencia is a closed UI picklist) — mirrored exactly.
            if (reportesProgramadosService.findByName(request.getPerfil().trim())) {
                return Response.status(Response.Status.CONFLICT)
                        .entity(ApiResponse.error("NAME_TAKEN",
                                "Ya existe un reporte programado con ese nombre!"))
                        .build();
            }

            ReporteProgramado reporte = new ReporteProgramado();
            reporte.setPerfil(request.getPerfil().trim());
            reporte.setFrecuencia(request.getFrecuencia() == null ? null
                    : new ArrayList<>(request.getFrecuencia()));
            reporte.setReportes(request.getReportes() == null ? null
                    : new ArrayList<>(request.getReportes()));
            reporte.setCorreos(toList(request.getCorreos()));
            // Parity: createReporte() always enables new schedules and stamps lastRun.
            reporte.setStatus(true);
            reporte.setLastRun(new Date());

            reportesProgramadosService.create(reporte);

            alertas.registrarAlerta("Reporte programado creado",
                    "Se ha creado el reporte programado: " + reporte.getPerfil(),
                    null, 0, "ReporteProgramadoResource.create()",
                    null, reporte.toString());

            return Response.status(Response.Status.CREATED)
                    .entity(ApiResponse.ok(toDTO(reporte)))
                    .build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error creating scheduled report", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR",
                            "Error creando el reporte programado"))
                    .build();
        }
    }

    /**
     * Updates a scheduled report — updateReporte() parity: {@code lastRun}
     * stamped on EVERY edit, NO duplicate-perfil re-check, and status/
     * nextRunTime untouched (toggling is the dedicated endpoint). Omitted
     * (null) fields keep their stored value.
     */
    @PUT
    @Path("/{id}")
    @Operation(summary = "Update a scheduled report (lastRun stamped on every edit, legacy parity)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Updated"),
        @APIResponse(responseCode = "400", description = "Validation error"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Missing admin/tributacion role"),
        @APIResponse(responseCode = "404", description = "Not found"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response update(
            @PathParam("id") @Parameter(description = "Scheduled report ID") Long id,
            @Nullable ReporteProgramadoDTO request) {
        try {
            if (request == null) {
                return badRequest("El cuerpo de la petición es requerido.");
            }
            if (request.getPerfil() != null && request.getPerfil().isBlank()) {
                return badRequest("El perfil del reporte no puede estar vacío.");
            }

            ReporteProgramado reporte = reportesProgramadosService.find(id);
            if (reporte == null) {
                return notFound(id);
            }

            String antes = DiffUtils.snapshotEntity(reporte);
            if (request.getPerfil() != null) {
                reporte.setPerfil(request.getPerfil().trim());
            }
            if (request.getFrecuencia() != null) {
                reporte.setFrecuencia(new ArrayList<>(request.getFrecuencia()));
            }
            if (request.getReportes() != null) {
                reporte.setReportes(new ArrayList<>(request.getReportes()));
            }
            if (request.getCorreos() != null) {
                reporte.setCorreos(toList(request.getCorreos()));
            }
            if (request.getNextRunTime() != null) {
                reporte.setNextRunTime(request.getNextRunTime());
            }
            // Parity: updateReporte() stamps lastRun on every save.
            reporte.setLastRun(new Date());

            reportesProgramadosService.update(reporte);

            alertas.registrarAlerta("Reporte programado actualizado",
                    "Se ha actualizado el reporte programado: " + reporte.getPerfil(),
                    null, 0, "ReporteProgramadoResource.update()",
                    antes, DiffUtils.snapshotEntity(reporte));

            return Response.ok(ApiResponse.ok(toDTO(reporte))).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error updating scheduled report " + id, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR",
                            "Error actualizando el reporte programado"))
                    .build();
        }
    }

    /**
     * Flips the enabled flag — toggleReporte()/enableReporte()/
     * disableReporte() parity: flip, persist, never touch {@code lastRun}.
     */
    @PUT
    @Path("/{id}/toggle")
    @Operation(summary = "Toggle the enabled flag of a scheduled report")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Toggled"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Missing admin/tributacion role"),
        @APIResponse(responseCode = "404", description = "Not found"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response toggle(@PathParam("id") @Parameter(description = "Scheduled report ID") Long id) {
        try {
            ReporteProgramado reporte = reportesProgramadosService.find(id);
            if (reporte == null) {
                return notFound(id);
            }

            String antes = DiffUtils.snapshotEntity(reporte);
            // Parity: toggleReporte() flips the flag via enable/disable helpers
            // and persists; lastRun is NOT touched.
            reporte.setStatus(!reporte.isStatus());
            reportesProgramadosService.update(reporte);

            String action = reporte.isStatus() ? "habilitado" : "deshabilitado";
            alertas.registrarAlerta("Estado del reporte programado cambiado",
                    "Se ha " + action + " el reporte programado: " + reporte.getPerfil(),
                    null, 0, "ReporteProgramadoResource.toggle()",
                    antes, DiffUtils.snapshotEntity(reporte));

            // View-half branch (docs/ui-kit.md §2.9 dual-mode contract): HTMX
            // callers get the refreshed table fragment plus an out-of-band
            // toast; the JSON envelope below stays byte-identical for API
            // clients (CategoriaResource/T18 precedent).
            if (isHxRequest()) {
                return tableFragment(1, 20, null, "asc", null, isAdmin(),
                        "success", "Se ha " + action + " el reporte programado");
            }

            return Response.ok(ApiResponse.ok(toDTO(reporte))).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error toggling scheduled report " + id, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR",
                            "Error cambiando el estado del reporte programado"))
                    .build();
        }
    }

    /**
     * Deletes a scheduled report — deleteReporte() parity: HARD delete with
     * the legacy audit texts.
     */
    @DELETE
    @Path("/{id}")
    @Operation(summary = "Delete a scheduled report (hard delete, legacy parity)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Deleted"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Missing admin/tributacion role"),
        @APIResponse(responseCode = "404", description = "Not found"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response delete(@PathParam("id") @Parameter(description = "Scheduled report ID") Long id) {
        try {
            ReporteProgramado reporte = reportesProgramadosService.find(id);
            if (reporte == null) {
                return notFound(id);
            }

            String antes = DiffUtils.snapshotEntity(reporte);
            reportesProgramadosService.delete(reporte);

            alertas.registrarAlerta("Reporte programado eliminado",
                    "Se ha eliminado el reporte programado: " + reporte.getPerfil(),
                    null, 0, "ReporteProgramadoResource.delete()",
                    antes, null);

            // View-half branch — same dual-mode contract as toggle().
            if (isHxRequest()) {
                return tableFragment(1, 20, null, "asc", null, isAdmin(),
                        "success", "Reporte programado eliminado");
            }

            return Response.ok(ApiResponse.ok(toDTO(reporte))).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error deleting scheduled report " + id, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR",
                            "Error eliminando el reporte programado"))
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
     * table container); without it renders the FULL reportes programados
     * admin page. Mirrors the SERVER-SIDE CONTRACT of
     * {@code templates/_kit/data-table.html} exactly: the same endpoint
     * renders page and fragments, all paging/sorting state lives in the URL,
     * and {@code page} is 1-based here (kit convention — deliberately NOT the
     * 0-based JSON list above, whose contract is untouched).
     */
    @GET
    @Path("/table")
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Data-table fragment (HX-Request) or full reportes programados page")
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
                return tableFragment(page, size, sort, dir, q, isAdmin(), null, null);
            }
            return htmlOk(renderFullPage());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error renderizando la página de reportes programados", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error renderizando la página"))
                    .build();
        }
    }

    /** Empty scheduled-report creation form (modal body). */
    @GET
    @Path("/formularios/nueva")
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "New-reporte form fragment (modal body)")
    public Response formNuevaReporte() {
        return htmlOk(formReporte
                .data("modo", "crear")
                .data("reporte", null)
                .data("errorPerfil", null)
                .data("errorFrecuencia", null)
                .data("correosTexto", null)
                .data("toastSeverity", null)
                .data("toastMessage", null));
    }

    /** Prefilled scheduled-report edit form (modal body), looked up by id. */
    @GET
    @Path("/formularios/{id}")
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Edit-reporte form fragment (modal body)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Form HTML"),
        @APIResponse(responseCode = "404", description = "Unknown id")
    })
    public Response formEditarReporte(@PathParam("id") Long id) {
        ReporteProgramado reporte = reportesProgramadosService.find(id);
        if (reporte == null) {
            return notFound(id);
        }
        return htmlOk(formReporte
                .data("modo", "editar")
                .data("reporte", reporte)
                .data("errorPerfil", null)
                .data("errorFrecuencia", null)
                .data("correosTexto", joinLines(reporte.getCorreos()))
                .data("toastSeverity", null)
                .data("toastMessage", null));
    }

    /**
     * Form-urlencoded twin of {@link #create(ReporteProgramadoDTO)} for the
     * HTMX dialog form (JAX-RS selects by Content-Type). Legacy
     * createReporte()/createReporteDialog() parity: duplicate-perfil guard
     * with the legacy warning text, status forced true, lastRun stamped now;
     * the service seeds nextRunTime when frecuencia is present.
     *
     * <p>The frecuencia checkboxes are the legacy "cron field": the JSF UI
     * constrained them client-side to the closed picklist (Diario, Semanal,
     * Quincenal, Mensual), so this twin enforces that whitelist server-side.
     * Invalid values re-render the form fragment with the field error
     * instead of persisting (ui-kit.md Pattern A).</p>
     */
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Operation(summary = "Create a scheduled report from an HTMX form", hidden = true)
    public Response createReporteForm(
            @FormParam("perfil") @Nullable String perfil,
            @FormParam("frecuencia") @Nullable List<String> frecuencia,
            @FormParam("reportes") @Nullable List<String> reportes,
            @FormParam("correos") @Nullable String correos) {
        if (perfil == null || perfil.isBlank()) {
            return reporteFormFailure("crear", null, null, true, false, null,
                    "error", "El nombre no puede estar vacío");
        }
        String invalidFrecuencia = firstInvalid(frecuencia, FRECUENCIA_VALIDAS);
        if (invalidFrecuencia != null) {
            return reporteFormFailure("crear", null, perfil.trim(), false, true, correos,
                    "error", MSG_FRECUENCIA_INVALIDA);
        }
        try {
            if (reportesProgramadosService.findByName(perfil.trim())) {
                return reporteFormFailure("crear", null, perfil.trim(), false, false, correos,
                        "warn", "Ya existe un reporte programado con ese nombre!");
            }

            ReporteProgramado reporte = new ReporteProgramado();
            reporte.setPerfil(perfil.trim());
            reporte.setFrecuencia(cleanWhitelist(frecuencia, FRECUENCIA_VALIDAS));
            reporte.setReportes(cleanWhitelist(reportes, REPORTES_VALIDOS));
            reporte.setCorreos(parseLines(correos));
            reporte.setStatus(true);
            reporte.setLastRun(new Date());

            reportesProgramadosService.create(reporte);

            alertas.registrarAlerta("Reporte programado creado",
                    "Se ha creado el reporte programado: " + reporte.getPerfil(),
                    null, 0, "ReporteProgramadoResource.createReporteForm()",
                    null, reporte.toString());

            if (isHxRequest()) {
                return hxRedirect("/api/app/reportes-programados/table");
            }
            return Response.status(Response.Status.CREATED)
                    .entity(ApiResponse.ok(toDTO(reporte))).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error creating scheduled report from form", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR",
                            "Error creando el reporte programado"))
                    .build();
        }
    }

    /**
     * Form-urlencoded twin of {@link #update(Long, ReporteProgramadoDTO)} for
     * the HTMX dialog form. Legacy updateReporte() parity: lastRun stamped on
     * EVERY edit, NO duplicate-perfil re-check, status/nextRunTime untouched.
     *
     * <p>Divergence from the JSON PUT's partial semantics, deliberate and
     * form-faithful: a browser form always submits every control, so an
     * unchecked checkbox group arrives as an ABSENT key meaning the user
     * cleared it — frecuencia/reportes/correos are therefore full-replace
     * here (null ⇒ empty list), while the JSON PUT keeps null ⇒ stored
     * value.</p>
     */
    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Operation(summary = "Update a scheduled report from an HTMX form", hidden = true)
    public Response updateReporteForm(
            @PathParam("id") Long id,
            @FormParam("perfil") @Nullable String perfil,
            @FormParam("frecuencia") @Nullable List<String> frecuencia,
            @FormParam("reportes") @Nullable List<String> reportes,
            @FormParam("correos") @Nullable String correos) {
        if (perfil != null && perfil.isBlank()) {
            return reporteFormFailure("editar", id, null, true, false, correos,
                    "error", "El nombre no puede estar vacío");
        }
        String invalidFrecuencia = firstInvalid(frecuencia, FRECUENCIA_VALIDAS);
        if (invalidFrecuencia != null) {
            return reporteFormFailure("editar", id, perfil, false, true, correos,
                    "error", MSG_FRECUENCIA_INVALIDA);
        }
        try {
            ReporteProgramado reporte = reportesProgramadosService.find(id);
            if (reporte == null) {
                return notFound(id);
            }

            String antes = DiffUtils.snapshotEntity(reporte);
            if (perfil != null) {
                reporte.setPerfil(perfil.trim());
            }
            reporte.setFrecuencia(cleanWhitelist(frecuencia, FRECUENCIA_VALIDAS));
            reporte.setReportes(cleanWhitelist(reportes, REPORTES_VALIDOS));
            reporte.setCorreos(parseLines(correos));
            reporte.setLastRun(new Date());

            reportesProgramadosService.update(reporte);

            alertas.registrarAlerta("Reporte programado actualizado",
                    "Se ha actualizado el reporte programado: " + reporte.getPerfil(),
                    null, 0, "ReporteProgramadoResource.updateReporteForm()",
                    antes, DiffUtils.snapshotEntity(reporte));

            if (isHxRequest()) {
                return hxRedirect("/api/app/reportes-programados/table");
            }
            return Response.ok(ApiResponse.ok(toDTO(reporte))).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error updating scheduled report " + id + " from form", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR",
                            "Error actualizando el reporte programado"))
                    .build();
        }
    }

    // ── View-half helpers ────────────────────────────────────────────────────

    /** Legacy closed frecuencia picklist (the scheduler's cron vocabulary). */
    private static final List<String> FRECUENCIA_VALIDAS =
            List.of("Diario", "Semanal", "Quincenal", "Mensual");

    /** Legacy closed reportes picklist (Models.Correos.ReportesEnum names). */
    private static final List<String> REPORTES_VALIDOS = List.of(
            ReportesEnum.MOVIMIENTOS.name(),
            ReportesEnum.FACTURACION.name(),
            ReportesEnum.ARTICULOS.name(),
            ReportesEnum.DEPARTAMENTOS.name(),
            ReportesEnum.FAMILIAS.name(),
            ReportesEnum.INVENTARIOS.name());

    private static final String MSG_FRECUENCIA_INVALIDA =
            "Frecuencia inválida. Valores permitidos: Diario, Semanal, Quincenal, Mensual.";

    /** True when the call comes from HTMX (layout hx-headers carries it). */
    private boolean isHxRequest() {
        String header = routing.request().getHeader("HX-Request");
        return header != null && !"false".equalsIgnoreCase(header);
    }

    /** Legacy delete-button gate (rendered="#{SessionController.admin}"). */
    private boolean isAdmin() {
        return !identity.isAnonymous() && identity.hasRole("admin");
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
     * out-of-band toast with HTTP 422 (ui-kit.md Pattern A); non-HTMX callers
     * get the structured envelope with the same status codes as the JSON
     * contract (409 duplicate / 400 validation).
     */
    private Response reporteFormFailure(@Nonnull String modo, @Nullable Long id,
                                        @Nullable String perfilEco, boolean perfilError,
                                        boolean frecuenciaError,
                                        @Nullable String correosTextoEco,
                                        @Nonnull String severity, @Nonnull String mensaje) {
        boolean duplicado = "Ya existe un reporte programado con ese nombre!".equals(mensaje);
        if (isHxRequest()) {
            ReporteProgramado eco = new ReporteProgramado();
            eco.setId(id);
            eco.setPerfil(perfilEco);
            TemplateInstance template = formReporte
                    .data("modo", modo)
                    .data("reporte", eco)
                    .data("errorPerfil", perfilError ? mensaje : null)
                    .data("errorFrecuencia", frecuenciaError ? mensaje : null)
                    .data("correosTexto", correosTextoEco)
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

    /** First submitted value outside the whitelist, or null when all valid. */
    @Nullable
    private static String firstInvalid(@Nullable List<String> values,
                                       @Nonnull List<String> whitelist) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !whitelist.contains(value)) {
                return value;
            }
        }
        return null;
    }

    /** Keeps only whitelisted values (order preserved); never null. */
    @Nonnull
    private static List<String> cleanWhitelist(@Nullable List<String> values,
                                               @Nonnull List<String> whitelist) {
        List<String> out = new ArrayList<>();
        if (values != null) {
            for (String value : values) {
                if (value != null && whitelist.contains(value) && !out.contains(value)) {
                    out.add(value);
                }
            }
        }
        return out;
    }

    /** Textarea semantics: one recipient per line, trimmed, blanks dropped. */
    @Nullable
    private static List<String> parseLines(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (String line : raw.split("\r?\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out.isEmpty() ? null : out;
    }

    /** Inverse of {@link #parseLines(List)} for redisplay in the textarea. */
    @Nullable
    private static String joinLines(@Nullable List<String> values) {
        return values == null || values.isEmpty() ? null : String.join("\n", values);
    }

    /**
     * Renders ONLY one data-table include (the fragment swap target).
     * Model keys mirror the _kit/data-table DATA CONTRACT verbatim.
     */
    private Response tableFragment(int page, int size, @Nullable String sort,
                                   @Nullable String dir, @Nullable String q,
                                   boolean admin, @Nullable String toastSeverity,
                                   @Nullable String toastMessage) {
        TableModel model = buildTableModel(page, size, sort, dir, q);
        return htmlOk(tablaReportes
                .data("modelo", model.asMap())
                .data("q", model.q())
                .data("isAdmin", admin)
                .data("toastSeverity", toastSeverity)
                .data("toastMessage", toastMessage));
    }

    /** Full-page model for pages/correos/reportes.html. */
    private TemplateInstance renderFullPage() {
        TableModel tr = buildTableModel(1, 20, null, "asc", null);
        return pageIndex
                .data("tablaReportes", tr.asMap())
                .data("totalReportes", reportesProgramadosService.count())
                .data("isAdmin", isAdmin());
    }

    /** Immutable view of everything tabla-reportes.html needs. */
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
        List<ReporteProgramado> filtered =
                filterReportes(orEmpty(reportesProgramadosService.listAll()), q);
        sortReportes(filtered, sort, dir);

        long total = filtered.size();
        Window w = windowOf(total, page, size);

        List<Map<String, Object>> filas = new ArrayList<>();
        for (ReporteProgramado r : filtered.subList(w.from(), w.to())) {
            Map<String, Object> fila = new LinkedHashMap<>();
            fila.put("id", r.getId());
            fila.put("estado", r.isStatus());
            fila.put("perfil", r.getPerfil());
            fila.put("frecuencia", joinComma(r.getFrecuencia()));
            fila.put("reportes", joinComma(r.getReportes()));
            fila.put("correos", joinComma(r.getCorreos()));
            fila.put("lastRun", formatFecha(r.getLastRun()));
            fila.put("nextRunTime", formatFecha(r.getNextRunTime()));
            filas.add(fila);
        }

        // Column sets mirror the legacy Reportes p:dataTable (chip Estado /
        // Nombre(perfil) / Correos / Frecuencia / Reportes / Acciones); null
        // key ⇒ not sortable (docs/ui-kit.md §3.1).
        List<Map<String, Object>> columnas = new ArrayList<>();
        columnas.add(col("Estado", null));
        columnas.add(col("Nombre", "perfil"));
        columnas.add(col("Correos", null));
        columnas.add(col("Frecuencia", null));
        columnas.add(col("Reportes", null));
        columnas.add(col("Acciones", null));

        Map<String, Object> filtros = new LinkedHashMap<>();
        if (q != null && !q.isBlank()) {
            filtros.put("q", q);
        }

        return new TableModel(
                "tabla-reportes",
                "/api/app/reportes-programados/table",
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
     * Legacy {@code globalFilterFunction()} parity: perfil text plus the
     * string representation of correos/reportes/frecuencia, case-insensitive
     * contains.
     */
    private static List<ReporteProgramado> filterReportes(@Nonnull List<ReporteProgramado> source,
                                                          @Nullable String q) {
        if (q == null || q.trim().isEmpty()) {
            return new ArrayList<>(source);
        }
        String needle = q.trim().toLowerCase(Locale.ROOT);
        List<ReporteProgramado> out = new ArrayList<>();
        for (ReporteProgramado r : source) {
            if (matches(r.getPerfil(), needle)
                    || listMatches(r.getCorreos(), needle)
                    || listMatches(r.getReportes(), needle)
                    || listMatches(r.getFrecuencia(), needle)) {
                out.add(r);
            }
        }
        return out;
    }

    private static boolean matches(@Nullable String value, @Nonnull String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static boolean listMatches(@Nullable List<String> values, @Nonnull String needle) {
        if (values == null) {
            return false;
        }
        for (String value : values) {
            if (matches(value, needle)) {
                return true;
            }
        }
        return false;
    }

    /** Typed comparator dispatch over a whitelisted key set. */
    private static void sortReportes(@Nonnull List<ReporteProgramado> rows,
                                     @Nullable String sort, @Nullable String dir) {
        if (rows.isEmpty() || sort == null || sort.isBlank()) {
            return;
        }
        Comparator<ReporteProgramado> cmp;
        switch (sort) {
            case "id":
                cmp = Comparator.comparing(ReporteProgramado::getId,
                        Comparator.nullsLast(Comparator.naturalOrder()));
                break;
            case "perfil":
                cmp = Comparator.comparing(ReporteProgramado::getPerfil,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
                break;
            case "status":
                cmp = Comparator.comparing(ReporteProgramado::isStatus);
                break;
            case "lastRun":
                cmp = Comparator.comparing(ReporteProgramado::getLastRun,
                        Comparator.nullsLast(Comparator.naturalOrder()));
                break;
            case "nextRunTime":
                cmp = Comparator.comparing(ReporteProgramado::getNextRunTime,
                        Comparator.nullsLast(Comparator.naturalOrder()));
                break;
            default:
                return;
        }
        rows.sort("desc".equalsIgnoreCase(dir) ? cmp.reversed() : cmp);
    }

    /** Comma-joined display text for the list columns (legacy toString parity). */
    @Nullable
    private static String joinComma(@Nullable List<String> values) {
        return values == null || values.isEmpty() ? null : String.join(", ", values);
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
                        "No se encontró el reporte programado: " + id))
                .build();
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(ApiResponse.error("VALIDATION_ERROR", message))
                .build();
    }

    /** DTO flattened {@code String[]} correos → entity {@code List<String>} (null-safe). */
    @Nullable
    private static List<String> toList(@Nullable String[] correos) {
        return correos == null ? null : new ArrayList<>(Arrays.asList(correos));
    }

    /**
     * Read-side mapping ONLY — copies the collections defensively so no live
     * JPA collection escapes the entity graph through the DTO layer.
     */
    private static ReporteProgramadoDTO toDTO(@Nonnull ReporteProgramado reporte) {
        return new ReporteProgramadoDTO(
                reporte.getId(),
                reporte.getPerfil(),
                reporte.getFrecuencia() == null ? null : new ArrayList<>(reporte.getFrecuencia()),
                reporte.getReportes() == null ? null : new ArrayList<>(reporte.getReportes()),
                reporte.getCorreos() == null ? null : reporte.getCorreos().toArray(String[]::new),
                reporte.getLastRun(),
                reporte.isStatus(),
                reporte.getNextRunTime());
    }
}
