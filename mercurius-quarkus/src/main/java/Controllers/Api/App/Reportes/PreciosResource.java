package Controllers.Api.App.Reportes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 * <p>Backed DIRECTLY by {@link ArticuloPrecioService#listAll()} — the same
 * listing the legacy {@code ArticulosPrecioController.getFilteredPrecios()}
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

        List<ArticuloPrecio> precios = precioService.listAll();
        String filtro = q != null ? q.trim().toLowerCase() : null;

        List<Map<String, Object>> filas = new ArrayList<>();
        if (precios != null) {
            for (ArticuloPrecio precio : precios) {
                if (filtro != null && !filtro.isEmpty() && !coincide(precio, filtro)) {
                    continue;
                }
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
        }

        Tablas.ordenar(filas, sort, dir);
        long totalFilas = filas.size();
        int totalPages = Tablas.totalPaginas(totalFilas, size);

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("titulo", "Hist\u00f3rico de Precios");
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

    /** Legacy globalFilterFunction parity. */
    private static boolean coincide(@Nonnull ArticuloPrecio precio, @Nonnull String filtro) {
        String articulo = precio.getArticulo() != null
                && precio.getArticulo().getNombre() != null
                ? precio.getArticulo().getNombre().toLowerCase() : "";
        String barra = precio.getArticulo() != null
                && precio.getArticulo().getCodigoBarra() != null
                ? precio.getArticulo().getCodigoBarra().toLowerCase() : "";
        String departamento = precio.getArticulo() != null
                && precio.getArticulo().getDepartamento() != null
                && precio.getArticulo().getDepartamento().getNombre() != null
                ? precio.getArticulo().getDepartamento().getNombre().toLowerCase() : "";
        String familia = precio.getArticulo() != null
                && precio.getArticulo().getFamilia() != null
                && precio.getArticulo().getFamilia().getNombre() != null
                ? precio.getArticulo().getFamilia().getNombre().toLowerCase() : "";
        String usuario = precio.getUsuario() != null
                && precio.getUsuario().getUsername() != null
                ? precio.getUsuario().getUsername().toLowerCase() : "";
        return String.valueOf(precio.getId()).contains(filtro)
                || articulo.contains(filtro)
                || barra.contains(filtro)
                || departamento.contains(filtro)
                || familia.contains(filtro)
                || usuario.contains(filtro);
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
