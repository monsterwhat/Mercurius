package Controllers.Api.App;

import static Controllers.Api.App.AppRecursoBase.*;
import Models.Inventario;
import Services.InventarioService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jboss.logging.Logger;

/**
 * HTML page of the Inventario module for the NEW Qute/HTMX app surface:
 * {@code GET /app/inventarios} — the four-tab board (Activos / Inactivos /
 * Procesados / Pendientes) plus the badges stat cards and the XML upload
 * card. Read-only page renderer: builds the full-page model (four data
 * tables, badge counts, admin flag) exactly like
 * {@link InventarioResource#renderFullPage()} and renders
 * {@code pages/inventario/index.html}. All mutation, fragment and JSON
 * endpoints live in the API twin {@link InventarioResource}
 * ({@code /api/app/inventario}).
 *
 * <p><b>Role gate</b>: {@code admin} + {@code inventario}, mirroring the
 * module's managing roles (the export button narrows to {@code registro} in
 * the template via {@code inject:securityIdentity}).</p>
 */
@Path("/app/inventarios")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"admin", "inventario"})
public class InventariosPagesResource {

    private static final Logger LOG = Logger.getLogger(InventariosPagesResource.class);

    private static final String TAB_ACTIVOS = "activos";
    private static final String TAB_INACTIVOS = "inactivos";
    private static final String TAB_PROCESADOS = "procesados";
    private static final String TAB_PENDIENTES = "pendientes";

    /** Legacy p:dataTable rows=20 on the Inventario tabs. */
    private static final int PAGE_SIZE = 20;

    @Inject
    @Nonnull
    InventarioService inventarioService;

    @Inject
    @Nonnull
    SecurityIdentity identity;

    @Inject
    @Nonnull
    @Location("pages/inventario/index")
    Template page;

    @GET
    public Response index() {
        try {
            TemplateInstance instance = page.instance();
            model().forEach(instance::data);
            return Response.ok(instance.render())
                    .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).build();
        } catch (RuntimeException e) {
            LOG.warn("Error renderizando la página de inventarios", e);
            return Response.serverError()
                    .entity(Models.DTO.ApiResponse.error("INTERNAL_ERROR",
                            "No se pudieron cargar los inventarios"))
                    .build();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Page model (mirrors InventarioResource#renderFullPage)
    // ════════════════════════════════════════════════════════════════════

    private Map<String, Object> model() {
        Map<String, Object> model = new LinkedHashMap<>();
        Map<String, Object> badges = badgeModel();
        model.put("tablaActivos", tableModel(TAB_ACTIVOS, totalDe(badges, TAB_ACTIVOS)));
        model.put("tablaInactivos", tableModel(TAB_INACTIVOS, totalDe(badges, TAB_INACTIVOS)));
        model.put("tablaProcesados", tableModel(TAB_PROCESADOS, totalDe(badges, TAB_PROCESADOS)));
        model.put("tablaPendientes", tableModel(TAB_PENDIENTES, totalDe(badges, TAB_PENDIENTES)));
        model.put("badges", badges);
        model.put("isAdmin", isAdmin());
        return model;
    }

    /** Total de una pestaña a partir de los contadores ya calculados. */
    private static long totalDe(@Nonnull Map<String, Object> badges, @Nonnull String tab) {
        return switch (tab) {
            case TAB_INACTIVOS -> ((Number) badges.get("inactivos")).longValue();
            case TAB_PROCESADOS -> ((Number) badges.get("procesados")).longValue();
            case TAB_PENDIENTES -> ((Number) badges.get("pendientes")).longValue();
            default -> ((Number) badges.get("activos")).longValue();
        };
    }

    /** Badge counts (legacy stat cards + Procesados counter expression). */
    private Map<String, Object> badgeModel() {
        long activos = inventarioService.countActivos();
        long pendientes = inventarioService.countPendientes();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("activos", activos);
        map.put("pendientes", pendientes);
        map.put("inactivos", inventarioService.countInactivos());
        map.put("procesados", Math.max(0L, activos - pendientes)); // legacy tab-counter arithmetic
        return map;
    }

    /**
     * One data-table model (page 1, size 20, no sort/filter) — keys mirror
     * the _kit/data-table DATA CONTRACT verbatim: id, baseUrl, columnas,
     * filas, sortKey, sortDir, page, size, total, totalPages, paginas,
     * filtros, q. Service-query dispatch per tab matches the API twin.
     */
    private Map<String, Object> tableModel(@Nonnull String tab, long total) {
        // Solo la página visible: antes se traían las 673.326 filas de cada
        // pestaña para quedarse con 20. El LIMIT lo hace PostgreSQL.
        List<Inventario> filas = orEmpty(switch (tab) {
            case TAB_INACTIVOS -> inventarioService.listInactivosPage(0, PAGE_SIZE);
            case TAB_PROCESADOS -> inventarioService.listActivosYProcesadosPage(0, PAGE_SIZE);
            case TAB_PENDIENTES -> inventarioService.listSinProcesarPage(0, PAGE_SIZE);
            default -> inventarioService.listEnabledPage(0, PAGE_SIZE);
        });
        int totalPages = (int) Math.max(1L, (total + PAGE_SIZE - 1) / PAGE_SIZE);

        // Column definitions mirror the legacy per-tab p:column sets; null
        // key ⇒ non-sortable (docs/ui-kit.md §3.1).
        List<Map<String, Object>> columnas = new ArrayList<>();
        columnas.add(col("Estado", null));
        columnas.add(col("Artículo", "articulo"));
        columnas.add(col("Código Barra", null));
        columnas.add(col("Cantidad", "cantidad"));
        columnas.add(col("Tipo Movimiento", "tipoMovimiento"));
        columnas.add(col("Fecha Movimiento", "fechaMovimiento"));
        columnas.add(col("Usuario", "usuario"));
        columnas.add(col("Acciones", null));

        Map<String, Object> filtros = new LinkedHashMap<>();
        filtros.put("tab", tab);

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("id", "tabla-inventario-" + tab);
        model.put("baseUrl", "/api/app/inventario/table");
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
    /** Server-computed pager window: current ±2 clamped to [1,totalPages]. */
    /** Legacy SessionController.admin parity (admin-only affordances). */
    private boolean isAdmin() {
        return !identity.isAnonymous() && identity.hasRole("admin");
    }
}
