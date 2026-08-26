package Controllers.Api.App;

import Models.Cabys;
import Models.DTO.ApiResponse;
import Models.DTO.CabysDTO;
import Models.DTO.PagedResponse;
import Services.AlertasService;
import Services.CabysService;
import Utils.DiffUtils;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
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
 * CABYS catalog endpoints for the NEW Qute/HTMX app surface (/app world).
 *
 * <p>Mirrors the legacy JSF {@code CabysController} behaviors as REST:
 * listing/filtering the catalog ({@code globalFilterFunction} parity),
 * lookup by {@code codigo}, and description/status updates
 * ({@code updateCabys()} parity, including the audit trail via
 * {@link AlertasService#registrarAlerta}). Catalog sync from Hacienda
 * ({@code listAllAPI()}) intentionally stays a legacy-controller action.</p>
 *
 * <p>The {@code @RolesAllowed} gate is dormant until the form-cookie auth
 * block is enabled in application.properties (see {@link AppAuthResource});
 * once active, every path under {@code /api/app/*} requires an authenticated
 * user with the {@code admin} or {@code inventario} role.</p>
 *
 * <p>All responses follow the {@link ApiResponse}/{@link PagedResponse}
 * envelope conventions.</p>
 */
@Path("/api/app/cabys")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"admin", "inventario"})
@Tag(name = "App - Cabys")
public class CabysResource {

    private static final Logger LOG = Logger.getLogger(CabysResource.class.getName());

    @Inject
    @Nonnull
    CabysService cabysService;

    @Inject
    @Nonnull
    AlertasService alertas;

    @GET
    @Operation(summary = "List CABYS entries with pagination and optional code/description filter")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Missing admin/inventario role"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response list(
            @QueryParam("page") @DefaultValue("0") @Parameter(description = "Page number (0-based)") int page,
            @QueryParam("size") @DefaultValue("20") @Parameter(description = "Page size (max 100)") int size,
            @QueryParam("q") @Nullable @Parameter(description = "Filter by codigo or descripcion (case-insensitive contains)") String q) {

        // Clamp size to max 100 (SuppliersController convention)
        size = Math.min(Math.max(size, 1), 100);
        page = Math.max(page, 0);

        try {
            if (q != null && !q.isBlank()) {
                // Parity with CabysController.globalFilterFunction(): the legacy
                // datatable filters the FULL in-memory catalog with a
                // case-insensitive contains over codigo/descripcion. CabysService
                // exposes no combined code+description filter, so the same
                // full-scan + in-memory filter/paginate is reproduced here.
                String filter = q.trim().toLowerCase(Locale.ROOT);
                List<CabysDTO> filtered = cabysService.listAll().stream()
                        .filter(c -> matchesFilter(c, filter))
                        .map(this::toDTO)
                        .toList();

                List<CabysDTO> data = paginate(filtered, page, size);
                return Response.ok(new PagedResponse<>(data, filtered.size(), page, size)).build();
            }

            long total = cabysService.count();
            List<CabysDTO> data = cabysService.listPage(page * size, size).stream()
                    .map(this::toDTO)
                    .toList();
            return Response.ok(new PagedResponse<>(data, total, page, size)).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error listing CABYS", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error listando el catálogo CABYS"))
                    .build();
        }
    }

    @GET
    @Path("/{codigo}")
    @Operation(summary = "Get a single CABYS entry by codigo")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Missing admin/inventario role"),
        @APIResponse(responseCode = "404", description = "Not found"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response get(@PathParam("codigo") @Parameter(description = "CABYS code") String codigo) {
        try {
            Cabys cabys = cabysService.find(codigo);
            if (cabys == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("NOT_FOUND", "No se encontró el CABYS: " + codigo))
                        .build();
            }
            return Response.ok(ApiResponse.ok(toDTO(cabys))).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error getting CABYS " + codigo, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error obteniendo el CABYS"))
                    .build();
        }
    }

    @PUT
    @Path("/{codigo}")
    @Operation(summary = "Update the descripcion/estado of a CABYS entry")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Updated"),
        @APIResponse(responseCode = "400", description = "Missing request body"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Missing admin/inventario role"),
        @APIResponse(responseCode = "404", description = "Not found"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response update(
            @PathParam("codigo") @Parameter(description = "CABYS code") String codigo,
            @Nullable CabysDTO payload) {
        try {
            if (payload == null) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(ApiResponse.error("VALIDATION_ERROR", "El cuerpo de la petición es requerido."))
                        .build();
            }

            Cabys cabys = cabysService.find(codigo);
            if (cabys == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("NOT_FOUND", "No se encontró el CABYS: " + codigo))
                        .build();
            }

            // Partial update: only the editable fields (descripcion/estado) are
            // applied, and only when provided — mirrors CabysController.updateCabys()
            // which persists whatever the edit dialog bound onto the entity.
            String antes = DiffUtils.snapshotEntity(cabys);
            if (payload.getDescripcion() != null) {
                cabys.setDescripcion(payload.getDescripcion());
            }
            if (payload.getEstado() != null) {
                cabys.setEstado(payload.getEstado());
            }
            cabysService.update(cabys);

            // Audit parity with CabysController.updateCabys() (usuario=null in the
            // REST world; attribution attaches when form auth lands).
            alertas.registrarAlerta("CABYS actualizado",
                    "Se ha actualizado el CABYS: " + codigo,
                    null, 0, "CabysResource.update()",
                    antes, DiffUtils.snapshotEntity(cabys));

            return Response.ok(ApiResponse.ok(toDTO(cabys))).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error updating CABYS " + codigo, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error actualizando el CABYS"))
                    .build();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Case-insensitive contains match over codigo/descripcion, mirroring the
     * relevant columns of {@code CabysController.globalFilterFunction()}.
     */
    private static boolean matchesFilter(@Nonnull Cabys cabys, @Nonnull String filter) {
        return (cabys.getCodigo() != null && cabys.getCodigo().toLowerCase(Locale.ROOT).contains(filter))
                || (cabys.getDescripcion() != null
                        && cabys.getDescripcion().toLowerCase(Locale.ROOT).contains(filter));
    }

    private static <T> List<T> paginate(List<T> items, int page, int size) {
        int from = page * size;
        if (from >= items.size()) {
            return List.of();
        }
        return items.subList(from, Math.min(from + size, items.size()));
    }

    private CabysDTO toDTO(Cabys cabys) {
        return new CabysDTO(cabys.getCodigo(), cabys.getDescripcion(), cabys.getCategorias(),
                cabys.getImpuesto(), cabys.getUri(), cabys.getEstado());
    }
}
