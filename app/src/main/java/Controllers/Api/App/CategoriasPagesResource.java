package Controllers.Api.App;

import static Controllers.Api.App.AppRecursoBase.*;
import Models.Departamento;
import Models.Familia;
import Services.DepartamentoService;
import Services.FamiliaService;
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
 * HTML page of the Categorías module for the NEW Qute/HTMX app surface:
 * {@code GET /app/categorias} — the route the T11 navbar reserved for the
 * legacy secured/pages/Categorias/index.xhtml.
 *
 * <p>Read-only page renderer: builds the full-page model (both data tables and
 * the stat counters) exactly like
 * {@link CategoriaResource#renderFullPage()} and renders
 * {@code pages/categorias/index.html}. All mutation, fragment and JSON
 * endpoints live in the API twin {@link CategoriaResource}
 * ({@code /api/app/categorias}).</p>
 *
 * <p><b>D5</b>: the supplier ranking is no longer part of this page; it is the
 * independent report {@code /app/reportes/proveedores}. This management screen
 * only links to it.</p>
 *
 * <p><b>Role gate</b>: {@code admin} + {@code inventario}, mirroring the
 * module's managing roles (the métricas recalcular button narrows to
 * {@code admin} via the {@code isAdmin} model key).</p>
 */
@Path("/app/categorias")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"admin", "inventario"})
public class CategoriasPagesResource {

    private static final Logger LOG = Logger.getLogger(CategoriasPagesResource.class);

    private static final String TAB_FAMILIAS = "familias";
    private static final String TAB_DEPARTAMENTOS = "departamentos";

    /** Legacy p:dataTable rows=20 on the Categorias tabs. */
    private static final int PAGE_SIZE = 20;

    @Inject
    @Nonnull
    DepartamentoService departamentoService;

    @Inject
    @Nonnull
    FamiliaService familiaService;

    @Inject
    @Nonnull
    SecurityIdentity identity;

    @Inject
    @Nonnull
    @Location("pages/categorias/index")
    Template page;

    @GET
    public Response index() {
        try {
            TemplateInstance instance = page.instance();
            model().forEach(instance::data);
            return Response.ok(instance.render())
                    .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).build();
        } catch (RuntimeException e) {
            LOG.warn("Error renderizando la página de categorías", e);
            return Response.serverError()
                    .entity(Models.DTO.ApiResponse.error("INTERNAL_ERROR",
                            "No se pudieron cargar las categorías"))
                    .build();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Page model (mirrors CategoriaResource#renderFullPage)
    // ════════════════════════════════════════════════════════════════════

    private Map<String, Object> model() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("familiasTabla", tableModel(TAB_FAMILIAS));
        model.put("departamentosTabla", tableModel(TAB_DEPARTAMENTOS));
        model.put("familiaCount", familiaService.count());
        model.put("departamentoCount", departamentoService.count());
        model.put("familiasActivasCount", familiaService.countActivas());
        model.put("familiasInactivasCount", familiaService.countInactivas());
        model.put("departamentosActivosCount", departamentoService.countActivos());
        model.put("departamentosInactivosCount", departamentoService.countInactivos());
        model.put("isAdmin", isAdmin());
        return model;
    }

    /**
     * One data-table model (page 1, size 20, no sort/filter) — keys mirror
     * the _kit/data-table DATA CONTRACT verbatim: id, baseUrl, columnas,
     * filas, sortKey, sortDir, page, size, total, totalPages, paginas,
     * filtros, q.
     */
    private Map<String, Object> tableModel(@Nonnull String tab) {
        boolean departamentos = TAB_DEPARTAMENTOS.equals(tab);
        List<?> all = departamentos
                ? orEmpty(departamentoService.listAll())
                : orEmpty(familiaService.listAll());
        long total = all.size();
        int totalPages = (int) Math.max(1L, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        int from = Math.min(0, (int) total);
        int to = Math.min(from + PAGE_SIZE, (int) total);
        List<?> filas = all.subList(from, to);

        // Column definitions as List<Map<String,Object>> with label/key —
        // null key ⇒ non-sortable (docs/ui-kit.md §3.1). Same sets as the
        // API twin's buildTableModel.
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

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("id", departamentos ? "tabla-departamentos" : "tabla-familias");
        model.put("baseUrl", "/api/app/categorias/table");
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

    /** Legacy SessionController.admin parity (recalcular gate). */
    private boolean isAdmin() {
        return !identity.isAnonymous() && identity.hasRole("admin");
    }
}
