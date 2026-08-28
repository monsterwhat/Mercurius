package Controllers.Api.App;

import Models.Cabys;
import Models.DTO.ApiResponse;
import Models.DTO.CabysDTO;
import Models.DTO.PagedResponse;
import Services.CabysService;
import Utils.DiffUtils;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Comparator;
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
 * CABYS catalog endpoints for the NEW Qute/HTMX app surface (/app world).
 *
 * <p>Mirrors the legacy JSF {@code CabysController} behaviors as REST:
 * listing/filtering the catalog ({@code globalFilterFunction} parity),
 * lookup by {@code codigo}, and description/status updates
 * ({@code updateCabys()} parity, including the audit trail via
 * {@code LOG.log(...)}). Catalog sync from Hacienda
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

    @Nonnull
    CabysService cabysService;

    /** Request context (quarkus-rest injectable) — source of HX-Request. */
    @Nonnull
    RoutingContext routing;

    // Templates (rendered to String: no quarkus-rest-qute MessageBodyWriter
    // on this stack — same approach as CategoriaResource, T18).
    @Nonnull
    @Location("pages/cabys/index.html")
    Template pageIndex;

    @Nonnull
    @Location("pages/cabys/tabla.html")
    Template tablaPage;

    @Nonnull
    @Location("pages/cabys/form.html")
    Template formCabys;

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
            LOG.log(Level.INFO, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s",
                    "CABYS actualizado", "Se ha actualizado el CABYS: " + codigo,
                    "Sistema",
                    0, "CabysResource.update()",
                    antes, DiffUtils.snapshotEntity(cabys)));

            return Response.ok(ApiResponse.ok(toDTO(cabys))).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error updating CABYS " + codigo, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error actualizando el CABYS"))
                    .build();
        }
    }

    // ── W4B view-half: dual-mode table endpoint + dialog fragments ─────────

    /**
     * GET /table?page&size&sort&dir&q — with the {@code HX-Request} header
     * returns ONLY the data-table include (fragment swap into the page's
     * table container); without it renders the FULL CaByS page. This mirrors
     * the SERVER-SIDE CONTRACT comment of {@code templates/_kit/data-table.html}
     * exactly: the same endpoint renders page and fragments, all
     * paging/sorting state lives in the URL, and {@code page} is 1-based here
     * (the JSON list endpoint above keeps its own 0-based contract untouched).
     */
    @GET
    @Path("/table")
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Data-table fragment (HX-Request) or full CaByS page")
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
                return htmlOk(tableInstance(page, size, sort, dir, q, null, null));
            }
            return htmlOk(renderFullPage());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error renderizando la página de CABYS", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error renderizando la página"))
                    .build();
        }
    }

    /** Edit-form fragment (modal body) for one catalog entry. */
    @GET
    @Path("/formularios/{codigo}")
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Edit-CABYS form fragment (modal body)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Form HTML"),
        @APIResponse(responseCode = "404", description = "Unknown codigo")
    })
    public Response formEditar(@PathParam("codigo") String codigo) {
        Cabys cabys = cabysService.find(codigo);
        if (cabys == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ApiResponse.error("NOT_FOUND", "No se encontró el CABYS: " + codigo))
                    .build();
        }
        return htmlOk(formCabys
                .data("cabys", cabys)
                .data("errorDescripcion", null)
                .data("toastSeverity", null)
                .data("toastMessage", null));
    }

    /**
     * Form-urlencoded twin of {@link #update} for the HTMX edit dialog
     * (JAX-RS selects by Content-Type; the JSON contract above is untouched).
     * Validation failure re-displays the form with an out-of-band toast
     * (ui-kit.md Pattern A); success answers HX-Redirect so the page reloads
     * fresh (ui-kit.md §5).
     */
    @PUT
    @Path("/{codigo}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Operation(summary = "Update a CABYS entry from an HTMX form", hidden = true)
    public Response updateForm(
            @PathParam("codigo") String codigo,
            @FormParam("descripcion") @Nullable String descripcion,
            @FormParam("estado") @Nullable String estado) {
        String descripcionLimpia = descripcion == null ? "" : descripcion.trim();
        if (descripcionLimpia.isEmpty()) {
            Cabys cabys = cabysService.find(codigo);
            if (cabys == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("NOT_FOUND", "No se encontró el CABYS: " + codigo))
                        .build();
            }
            String mensaje = "La descripción no puede estar vacía";
            return Response.status(Response.Status.BAD_REQUEST)
                    .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                    .entity(formCabys
                            .data("cabys", cabys)
                            .data("errorDescripcion", mensaje)
                            .data("toastSeverity", "error")
                            .data("toastMessage", mensaje)
                            .render())
                    .build();
        }

        String estadoLimpio = (estado == null || estado.isBlank()) ? null : estado.trim();
        Response result = update(codigo,
                new CabysDTO(codigo, descripcionLimpia, null, null, null, estadoLimpio));
        if (isHxRequest() && result.getStatus() == Response.Status.OK.getStatusCode()) {
            return hxRedirect("/api/app/cabys/table");
        }
        return result;
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

    // ── W4B template-model helpers (CategoriaResource/T18 conventions) ──────

    /** True when the call comes from HTMX (layout hx-headers carries it). */
    private boolean isHxRequest() {
        String header = routing.request().getHeader("HX-Request");
        return header != null && !"false".equalsIgnoreCase(header);
    }

    private static Response htmlOk(@Nonnull TemplateInstance template) {
        return Response.ok(template.render())
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).build();
    }

    private static Response hxRedirect(@Nonnull String url) {
        return Response.status(Response.Status.OK)
                .header("HX-Redirect", url)
                .build();
    }

    private TemplateInstance renderFullPage() {
        TableModel model = buildTableModel(1, 20, null, "asc", null);
        return pageIndex
                .data("tablaCabys", model.asMap())
                .data("totalCodigos", cabysService.count());
    }

    private TemplateInstance tableInstance(int page, int size, @Nullable String sort,
                                           @Nullable String dir, @Nullable String q,
                                           @Nullable String toastSeverity,
                                           @Nullable String toastMessage) {
        TableModel model = buildTableModel(page, size, sort, dir, q);
        return tablaPage
                .data("modelo", model.asMap())
                .data("q", model.q())
                .data("toastSeverity", toastSeverity)
                .data("toastMessage", toastMessage);
    }

    private TableModel buildTableModel(int page, int size, @Nullable String sort,
                                       @Nullable String dir, @Nullable String q) {
        List<Cabys> filas;
        if (q != null && !q.isBlank()) {
            String filter = q.trim().toLowerCase(Locale.ROOT);
            filas = new ArrayList<>(cabysService.listAll().stream()
                    .filter(c -> matchesFilter(c, filter))
                    .toList());
        } else {
            filas = new ArrayList<>(cabysService.listAll());
        }
        sortCabys(filas, sort, dir);

        long total = filas.size();
        Window w = windowOf(total, page, size);
        filas = filas.subList(w.from(), w.to());

        List<Map<String, Object>> columnas = new ArrayList<>();
        columnas.add(col("Código", "codigo"));
        columnas.add(col("Categoría", "categorias"));
        columnas.add(col("Descripción", "descripcion"));
        columnas.add(col("Impuesto", "impuesto"));
        columnas.add(col("Estado", "estado"));
        columnas.add(col("Acciones", null));

        Map<String, Object> filtros = new LinkedHashMap<>();
        if (q != null && !q.isBlank()) {
            filtros.put("q", q.trim());
        }

        return new TableModel("tabla-cabys", "/api/app/cabys/table", columnas, filas,
                sort, "desc".equalsIgnoreCase(dir) ? "desc" : "asc",
                w.page(), w.size(), total, w.totalPages(), pageWindow(w.page(), w.totalPages()),
                filtros, q);
    }

    private static void sortCabys(@Nonnull List<Cabys> rows, @Nullable String sort,
                                  @Nullable String dir) {
        if (rows.isEmpty() || sort == null || sort.isBlank()) {
            return;
        }
        Comparator<Cabys> cmp = switch (sort) {
            case "codigo" -> Comparator.comparing(Cabys::getCodigo,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "descripcion" -> Comparator.comparing(Cabys::getDescripcion,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "categorias" -> Comparator.comparing(Cabys::getCategorias,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "impuesto" -> Comparator.comparing(Cabys::getImpuesto,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "estado" -> Comparator.comparing(Cabys::getEstado,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            default -> null;
        };
        if (cmp != null) {
            rows.sort("desc".equalsIgnoreCase(dir) ? cmp.reversed() : cmp);
        }
    }

    private static Map<String, Object> col(@Nonnull String label, @Nullable String key) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("label", label);
        map.put("key", key);
        return map;
    }

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

    private static Window windowOf(long total, int page, int size) {
        int s = Math.min(Math.max(size, 1), 100);
        int totalPages = (int) Math.max(1L, (total + s - 1) / s);
        int p = Math.min(Math.max(page, 1), totalPages);
        int from = Math.min((p - 1) * s, (int) total);
        int to = Math.min(from + s, (int) total);
        return new Window(p, s, from, to, totalPages);
    }

    /** Immutable view of everything pages/cabys/tabla.html needs. */
    public record TableModel(String id, String baseUrl, List<Map<String, Object>> columnas,
                             List<?> filas, String sortKey, String sortDir, int page, int size,
                             long total, int totalPages, List<Integer> paginas,
                             Map<String, Object> filtros, String q) {

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
}
