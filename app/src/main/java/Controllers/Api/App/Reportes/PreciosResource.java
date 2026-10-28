package Controllers.Api.App.Reportes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import Models.Articulos.ArticuloPrecio;
import Services.ArticuloPrecioService;
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
 * Histórico de Precios for the NEW app surface — port of
 * {@code secured/pages/Inventario/Precios/index.xhtml}
 * (plan task T19, read-only).
 *
 * <p>Backed by {@link ArticuloPrecioService#findReportePage(String, String, boolean, int, int)}
 * with the filter, sort and page pushed into SQL: the history holds 16k+ rows
 * and only the visible page is hydrated. The same listing the legacy
 * {@code ArticulosPrecioController.getFilteredPrecios()}
 * rendered (the legacy controller's price-edit mutation is NOT ported here;
 * it belongs to the Articulos module wave). The legacy client-side global
 * filter becomes the {@code q} query param with legacy filter parity
 * (id/artículo/barra/departamento/familia/usuario contains).</p>
 */
@Path("/app/reportes/inventario/precios")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"admin", "inventario"})
public class PreciosResource {

    private static final String BASE_URL = "/app/reportes/inventario/precios";

    @Inject
    @Nonnull
    ArticuloPrecioService precioService;

    @Inject
    @Location("pages/reportes/precios")
    Template pagina;

    @Inject
    @Location("pages/reportes/_tablas/precios")
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

        String filtro = (q == null || q.isBlank())
                ? null
                : "%" + q.trim().toLowerCase(Locale.ROOT) + "%";

        int current = ReportePageSupport.clampPage(page);
        int pageSize = ReportePageSupport.clampSize(size);
        long totalFilas = precioService.countReporte(filtro);
        int totalPages = ReportePageSupport.totalPages(totalFilas, pageSize);

        // Filter/sort/page in SQL: the history holds 16k+ rows and the old
        // code hydrated all of them for every request just to show 15.
        List<ArticuloPrecio> precios = precioService.findReportePage(
                filtro, sort, !ReportePageSupport.isDescending(dir),
                (current - 1) * pageSize, pageSize);

        List<Map<String, Object>> filas = new ArrayList<>();
        for (ArticuloPrecio precio : precios) {
            filas.add(Tablas.fila(
                    "articulo", precio.getArticulo() != null
                            ? precio.getArticulo().getNombre() : "-",
                    "porcentajeUtilidad", Tablas.fmtNumero(precio.getPorcentajeUtilidad()),
                    "precioCostoSinIVA", Tablas.fmtColones(precio.getPrecioCostoSinIVA()),
                    "precioConUtilidad", Tablas.fmtColones(precio.getPrecioConUtilidad()),
                    "precioFinal", Tablas.fmtColones(precio.getPrecioFinal()),
                    "fechaCompra", Tablas.fmtFecha(precio.getFechaCompra()),
                    "usuario", precio.getUsuario() != null
                            && precio.getUsuario().getUsername() != null
                            ? precio.getUsuario().getUsername() : "-"));
        }

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("titulo", "Hist\u00f3rico de Precios");
        model.put("baseUrl", BASE_URL);
        model.put("columnas", columnas());
        model.put("filas", filas);
        model.put("sortKey", sort);
        model.put("sortDir", dir);
        model.put("page", current);
        model.put("size", pageSize);
        model.put("total", totalFilas);
        model.put("totalPages", totalPages);
        model.put("pages", Tablas.ventanaPaginas(current, totalPages));
        model.put("params", Tablas.params(filtros));
        model.put("filtros", filtros);

        boolean fragmento = headers.getHeaderString("HX-Request") != null;
        Template plantilla = fragmento ? tabla : pagina;
        String html = plantilla.data(model).render();
        return Response.ok(html)
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                .build();
    }

    @Nonnull
    private static List<Map<String, Object>> columnas() {
        return List.of(
                Map.of("label", "Artículo", "key", "articulo"),
                Map.of("label", "Utilidad %", "key", "porcentajeUtilidad"),
                Map.of("label", "Costo S/IVA", "key", "precioCostoSinIVA"),
                Map.of("label", "Precio", "key", "precioConUtilidad"),
                Map.of("label", "Precio C/IVA", "key", "precioFinal"),
                Map.of("label", "Fecha", "key", "fechaCompra"),
                Map.of("label", "Usuario", "key", "usuario"));
    }
}
