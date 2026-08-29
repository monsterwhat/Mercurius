package Controllers.Api.App;

import Models.Cabys;
import Services.CabysService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jboss.logging.Logger;

/**
 * HTML page of the CaByS catalog module for the NEW Qute/HTMX app surface:
 * {@code GET /app/cabys} — the catalog data table plus the total-codes stat
 * card. Read-only page renderer: builds the full-page model (data table +
 * {@code totalCodigos}) exactly like {@link CabysResource#renderFullPage()}
 * and renders {@code pages/cabys/index.html}. All mutation, fragment and
 * JSON endpoints live in the API twin {@link CabysResource}
 * ({@code /api/app/cabys}).
 *
 * <p><b>Role gate</b>: {@code admin} + {@code tributacion}, mirroring the
 * module's managing roles.</p>
 */
@Path("/app/cabys")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"admin", "tributacion"})
public class CabysPagesResource {

    private static final Logger LOG = Logger.getLogger(CabysPagesResource.class);

    /** Legacy p:dataTable rows=20 on the CaByS catalog. */
    private static final int PAGE_SIZE = 20;

    @Inject
    @Nonnull
    CabysService cabysService;

    @Inject
    @Nonnull
    SecurityIdentity identity;

    @Inject
    @Nonnull
    @Location("pages/cabys/index")
    Template page;

    @GET
    public Response index() {
        try {
            TemplateInstance instance = page.instance();
            model().forEach(instance::data);
            return Response.ok(instance.render())
                    .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).build();
        } catch (RuntimeException e) {
            LOG.warn("Error renderizando la página de cabys", e);
            return Response.serverError()
                    .entity(Models.DTO.ApiResponse.error("INTERNAL_ERROR",
                            "No se pudieron cargar los códigos CAByS"))
                    .build();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Page model (mirrors CabysResource#renderFullPage)
    // ════════════════════════════════════════════════════════════════════

    private Map<String, Object> model() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("tablaCabys", tableModel());
        model.put("totalCodigos", cabysService.count());
        model.put("canImport", canImport());
        return model;
    }

    private boolean canImport() {
        return !identity.isAnonymous()
                && (identity.hasRole("admin") || identity.hasRole("tributacion"));
    }

    /**
     * One data-table model (page 1, size 20, no sort/filter) — keys mirror
     * the _kit/data-table DATA CONTRACT verbatim: id, baseUrl, columnas,
     * filas, sortKey, sortDir, page, size, total, totalPages, paginas,
     * filtros, q.
     */
    private Map<String, Object> tableModel() {
        List<Cabys> all = orEmpty(cabysService.listAll());
        long total = all.size();
        int totalPages = (int) Math.max(1L, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        int from = Math.min(0, (int) total);
        int to = Math.min(from + PAGE_SIZE, (int) total);
        List<Cabys> filas = all.subList(from, to);

        // Column definitions mirror the legacy p:dataTable column set; null
        // key ⇒ non-sortable (docs/ui-kit.md §3.1).
        List<Map<String, Object>> columnas = new ArrayList<>();
        columnas.add(col("Código", "codigo"));
        columnas.add(col("Categoría", "categorias"));
        columnas.add(col("Descripción", "descripcion"));
        columnas.add(col("Impuesto", "impuesto"));
        columnas.add(col("Estado", "estado"));
        columnas.add(col("Acciones", null));

        Map<String, Object> filtros = new LinkedHashMap<>();

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("id", "tabla-cabys");
        model.put("baseUrl", "/api/app/cabys/table");
        model.put("columnas", columnas);
        model.put("filas", filas);
        model.put("sortKey", null);
        model.put("sortDir", "asc");
        model.put("page", 1);
        model.put("size", PAGE_SIZE);
        model.put("total", total);
        model.put("totalPages", totalPages);
        model.put("paginas", pageWindow(1, totalPages));
        model.put("filtros", filtros);
        model.put("q", null);
        return model;
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

    private static <T> List<T> orEmpty(@Nullable List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }
}
