package Controllers.Api.App;

import Models.Correos.ReporteProgramado;
import Models.DTO.ApiResponse;
import Models.DTO.PagedResponse;
import Models.DTO.ReporteProgramadoDTO;
import Services.AlertasService;
import Services.Correos.ReportesProgramadosService;
import Utils.DiffUtils;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
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

            return Response.ok(ApiResponse.ok(toDTO(reporte))).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error deleting scheduled report " + id, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR",
                            "Error eliminando el reporte programado"))
                    .build();
        }
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
