package Controllers.Api.App.Reportes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * <p>Backed by {@link ArticulosService#countEtiquetas} /
 * {@link ArticulosService#pageEtiquetas}: the enabled catalogue (252k
 * articles, each with its EAGER price history) used to be materialised on every
 * request just to render 15 label rows, which is why the page exceeded 30 s.
 * The {@code status = true} predicate, the text filter, the ORDER BY and the
 * window now run in SQL and only the six projected columns of the visible page
 * are read; prices come from one extra query for those codes
 * ({@link ArticulosService#ultimosPreciosEtiquetas}). The rendered label data
 * — codigo, nombre, codigoBarra, precio, familia, departamento — is unchanged,
 * and so is the client-side selection/preview contract.</p>
 *
 * <p>Selection semantics are preserved: the template has always selected from
 * the rendered rows only, so "Seleccionar Todos" covers one page (max
 * {@value #TAMANO_MAXIMO} rows) both before and after this change. Optional
 * {@code familia}/{@code departamento} id filters narrow the catalogue in SQL
 * (they round-trip through {@code params} even though the current template
 * does not render inputs for them); the Excel/PDF export path
 * ({@code /api/app/export}, dataset {@code etiquetas}) is untouched and keeps
 * using {@code ArticulosService#ListAllEnabled()}.</p>
 *
 * <p>Printing stays the legacy behavior — a client-side
 * {@code window.print()} hint (the legacy {@code imprimirEtiquetas} action only
 * raised an informational message). No mutation endpoints exist here.</p>
 */
@Path("/app/reportes/inventario/etiquetas")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"admin", "inventario"})
public class EtiquetasResource {

    private static final String BASE_URL = "/app/reportes/inventario/etiquetas";

    /** Same per-page cap the old in-memory {@code Tablas.paginaDe} applied. */
    private static final int TAMANO_MAXIMO = 200;

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
            @QueryParam("q") @Nullable String q,
            @QueryParam("familia") @Nullable String familia,
            @QueryParam("departamento") @Nullable String departamento) {

        Map<String, String> filtros = new LinkedHashMap<>();
        filtros.put("q", q);
        filtros.put("familia", familia);
        filtros.put("departamento", departamento);

        Integer familiaId = entero(familia);
        Integer departamentoId = entero(departamento);
        String filtro = q != null ? q.trim() : null;

        // Same clamps the old pager applied, so `size` keeps meaning "rows per
        // page" for the offset and the pager alike.
        int tamanoPagina = Math.min(Math.max(size, 1), TAMANO_MAXIMO);
        int paginaActual = Math.max(page, 1);
        int offset = (int) Math.min((long) (paginaActual - 1) * tamanoPagina, Integer.MAX_VALUE);

        // The table is counted, filtered, sorted and sliced in SQL: only the
        // visible page is materialised (no 252k-entity catalogue).
        long totalFilas = articulosService.countEtiquetas(filtro, familiaId, departamentoId);
        List<Map<String, Object>> filas = filasDePagina(articulosService.pageEtiquetas(
                filtro, familiaId, departamentoId, sort, dir, offset, tamanoPagina));
        int totalPages = Tablas.totalPaginas(totalFilas, tamanoPagina);

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("titulo", "Generar Etiquetas");
        model.put("baseUrl", BASE_URL);
        model.put("columnas", columnas());
        model.put("filas", filas);
        model.put("sortKey", sort);
        model.put("sortDir", dir);
        model.put("page", paginaActual);
        model.put("size", tamanoPagina);
        model.put("total", totalFilas);
        model.put("totalPages", totalPages);
        model.put("pages", Tablas.ventanaPaginas(paginaActual, totalPages));
        model.put("params", Tablas.params(filtros));
        model.put("filtros", filtros);

        boolean fragmento = headers.getHeaderString("HX-Request") != null;
        Template plantilla = fragmento ? tabla : pagina;
        String html = plantilla.data(model).render();
        return Response.ok(html)
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                .build();
    }

    /**
     * Turns the projected page rows into the display maps the table fragment
     * renders. The last price is fetched in one query for these codes only —
     * never for the whole catalogue.
     *
     * <p>Value parity with the old entity loop: an article without a price (or
     * whose last price has no final price) renders {@code "-"}, and a missing
     * familia/departamento renders {@code "-"} too.</p>
     */
    @Nonnull
    private List<Map<String, Object>> filasDePagina(@Nonnull List<Object[]> pagina) {
        List<Long> codigos = new ArrayList<>(pagina.size());
        for (Object[] fila : pagina) {
            codigos.add(((Number) fila[0]).longValue());
        }
        Map<Long, BigDecimal> precios = articulosService.ultimosPreciosEtiquetas(codigos);

        List<Map<String, Object>> filas = new ArrayList<>(pagina.size());
        for (Object[] fila : pagina) {
            long codigo = ((Number) fila[0]).longValue();
            filas.add(Tablas.fila(
                    "codigo", codigo,
                    "nombre", fila[1],
                    "codigoBarra", fila[2],
                    "precio", Tablas.fmtColones(precios.get(codigo)),
                    "familia", fila[3] != null ? fila[3] : "-",
                    "departamento", fila[4] != null ? fila[4] : "-"));
        }
        return filas;
    }

    /** Optional id query param; blank/invalid means "no filter". */
    @Nullable
    private static Integer entero(@Nullable String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(valor.trim());
        } catch (NumberFormatException e) {
            // Optional filter: a non-numeric id means "no filter", not a user
            // error, so it is not logged (same contract as ResumenResource).
            return null;
        }
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
