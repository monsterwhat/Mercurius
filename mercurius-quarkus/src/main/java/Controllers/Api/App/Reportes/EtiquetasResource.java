package Controllers.Api.App.Reportes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import Models.Articulos.Articulos;
import Services.ArticulosService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Generación de etiquetas de precio for the NEW app surface — port of
 * {@code secured/pages/Inventario/Reportes/Etiquetas/index.xhtml}
 * (plan task T19, READ-ONLY).
 *
 * <p>Backed DIRECTLY by {@link ArticulosService#ListAllEnabled()} — the same
 * listing the legacy {@code EtiquetasController.getFilteredArticulos()} showed.
 * Selection, per-article quantities and the label preview are now CLIENT-SIDE
 * (Alpine in the page template): the legacy server round-trips
 * ({@code toggleSelection}/{@code selectAll}) only mutated view state, never
 * the database. Printing stays the legacy behavior — a client-side
 * {@code window.print()} hint (the legacy {@code imprimirEtiquetas} action only
 * raised an informational message). No mutation endpoints exist here.</p>
 */
@Path("/app/reportes/inventario/etiquetas")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"admin", "inventario"})
public class EtiquetasResource {

    private static final String BASE_URL = "/app/reportes/inventario/etiquetas";

    @Inject
    @Nonnull
    ArticulosService articulosService;

    @Inject
    @Location("pages/reportes/etiquetas")
    Template pagina;

    @Inject
    @Location("pages/reportes/_tablas/etiquetas")
    Template tabla;

    @GET
    @Transactional
    public Response get(
            @Context @Nonnull HttpHeaders headers,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("15") int size,
            @QueryParam("sort") @Nullable String sort,
            @QueryParam("dir") @DefaultValue("asc") String dir,
            @QueryParam("q") @Nullable String q) {

        Map<String, String> filtros = new LinkedHashMap<>();
        filtros.put("q", q);

        List<Map<String, Object>> filas = new ArrayList<>();
        List<Articulos> articulos = articulosService.ListAllEnabled();
        String filtro = q != null ? q.trim().toLowerCase() : null;
        if (articulos != null) {
            for (Articulos articulo : articulos) {
                if (filtro != null && !filtro.isEmpty() && !coincide(articulo, filtro)) {
                    continue;
                }
                filas.add(Tablas.fila(
                        "codigo", articulo.getCodigo(),
                        "nombre", articulo.getNombre(),
                        "codigoBarra", articulo.getCodigoBarra(),
                        // getLastPrecioArticulo() NPEs on price-less articles; guard it.
                        "precio", articulo.getLastPrecio() != null
                                && articulo.getLastPrecio().getPrecioFinal() != null
                                ? Tablas.fmtColones(articulo.getLastPrecio().getPrecioFinal())
                                : "-",
                        "familia", articulo.getFamilia() != null
                                ? articulo.getFamilia().getNombre() : "-",
                        "departamento", articulo.getDepartamento() != null
                                ? articulo.getDepartamento().getNombre() : "-"));
            }
        }

        Tablas.ordenar(filas, sort, dir);
        long totalFilas = filas.size();
        int totalPages = Tablas.totalPaginas(totalFilas, size);

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("titulo", "Generar Etiquetas");
        model.put("baseUrl", BASE_URL);
        model.put("columnas", columnas());
        model.put("filas", Tablas.paginaDe(filas, page, size));
        model.put("sortKey", sort);
        model.put("sortDir", dir);
        model.put("page", Math.max(page, 1));
        model.put("size", size);
        model.put("total", totalFilas);
        model.put("totalPages", totalPages);
        model.put("pages", Tablas.ventanaPaginas(page, totalPages));
        model.put("params", Tablas.params(filtros));
        model.put("filtros", filtros);

        boolean fragmento = headers.getHeaderString("HX-Request") != null;
        Template plantilla = fragmento ? tabla : pagina;
        String html = plantilla.data(model).render();
        return Response.ok(html)
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                .build();
    }

    /** Legacy globalFilterFunction parity: codigo/nombre/codigoBarra contains. */
    private static boolean coincide(@Nonnull Articulos articulo, @Nonnull String filtro) {
        return String.valueOf(articulo.getCodigo()).contains(filtro)
                || (articulo.getNombre() != null
                        && articulo.getNombre().toLowerCase().contains(filtro))
                || (articulo.getCodigoBarra() != null
                        && articulo.getCodigoBarra().toLowerCase().contains(filtro));
    }

    @Nonnull
    private static List<Map<String, Object>> columnas() {
        // First column is the client-side selector checkbox (no key -> not sortable).
        List<Map<String, Object>> columnas = new ArrayList<>();
        columnas.add(Tablas.fila("label", "Sel."));
        columnas.add(Map.of("label", "Código", "key", "codigo"));
        columnas.add(Map.of("label", "Nombre", "key", "nombre"));
        columnas.add(Map.of("label", "Código Barra", "key", "codigoBarra"));
        columnas.add(Map.of("label", "Precio", "key", "precio"));
        columnas.add(Map.of("label", "Familia", "key", "familia"));
        columnas.add(Map.of("label", "Departamento", "key", "departamento"));
        return columnas;
    }
}
