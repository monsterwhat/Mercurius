package Controllers.Api.App.Reportes;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import Models.Inventario;
import Models.Users;
import Services.InventarioService;
import Services.UserService;
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
 * Reporte de movimientos de inventario por fechas for the NEW app surface —
 * port of {@code secured/pages/Articulos/Reportes/Fechas/index.xhtml}
 * (plan task T19, read-only).
 *
 * <p>Backed DIRECTLY by {@link InventarioService#findByDateRangeAndUserId(Date,
 * Date, Long)} — the same query the legacy
 * {@code ReportesDiariosController.cargar()} ran. The legacy "Cargar Reportes"
 * postback becomes a plain GET with {@code usuario}/{@code desde}/{@code hasta}
 * query params; missing values show the legacy prompt.</p>
 *
 * <p>Kit contract: full page without {@code HX-Request}, table fragment with it.
 * Export via the shared T17 endpoint (§6 form pattern).</p>
 */
@Path("/app/reportes/articulos/fechas")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"admin", "inventario"})
public class FechasResource {

    private static final String BASE_URL = "/app/reportes/articulos/fechas";

    @Inject
    @Nonnull
    UserService userService;

    @Inject
    @Nonnull
    InventarioService inventarioService;

    @Inject
    @Location("pages/reportes/fechas")
    Template pagina;

    @Inject
    @Location("pages/reportes/_tablas/fechas")
    Template tabla;

    @GET
    @Transactional
    public Response get(
            @Context @Nonnull HttpHeaders headers,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("sort") @Nullable String sort,
            @QueryParam("dir") @DefaultValue("asc") String dir,
            @QueryParam("usuario") @Nullable String usuario,
            @QueryParam("desde") @Nullable String desde,
            @QueryParam("hasta") @Nullable String hasta) {

        Map<String, String> filtros = new LinkedHashMap<>();
        filtros.put("usuario", usuario);
        filtros.put("desde", desde);
        filtros.put("hasta", hasta);

        List<Map<String, Object>> usuarios = new ArrayList<>();
        for (Users u : userService.listAll()) {
            usuarios.add(Tablas.fila("id", u.getId(), "username", u.getUsername()));
        }

        Date inicio = Tablas.fecha(desde, false);
        Date fin = Tablas.fecha(hasta, true);

        List<Map<String, Object>> filas = new ArrayList<>();
        boolean consultaCompleta = usuario != null && !usuario.isBlank()
                && inicio != null && fin != null;

        if (consultaCompleta) {
            List<Inventario> movimientos = inventarioService.findByDateRangeAndUserId(
                    inicio, fin, Long.valueOf(usuario.trim()));
            if (movimientos != null) {
                for (Inventario movimiento : movimientos) {
                    filas.add(Tablas.fila(
                            "fechaMovimiento", Tablas.fmtFechaHora(movimiento.getFechaMovimiento()),
                            "codigoBarra", movimiento.getArticulo() != null
                                    ? movimiento.getArticulo().getCodigoBarra() : "-",
                            "articulo", movimiento.getArticulo() != null
                                    ? movimiento.getArticulo().getNombre() : "-",
                            "cantidad", Tablas.fmtNumero(movimiento.getCantidad()),
                            "usuario", movimiento.getUsuario() != null
                                    ? movimiento.getUsuario().getUsername() : "-",
                            "tipoMovimiento", movimiento.getTipoMovimiento(),
                            "notas", movimiento.getNotas()));
                }
            }
        }

        Tablas.ordenar(filas, sort, dir);
        long totalFilas = filas.size();
        int totalPages = Tablas.totalPaginas(totalFilas, size);

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("titulo", "Reporte de Movimientos de Inventario");
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
        model.put("usuarios", usuarios);
        model.put("consultaCompleta", consultaCompleta);

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
                Map.of("label", "Fecha", "key", "fechaMovimiento"),
                Map.of("label", "Cod.Barra", "key", "codigoBarra"),
                Map.of("label", "Articulo", "key", "articulo"),
                Map.of("label", "Cantidad", "key", "cantidad"),
                Map.of("label", "Usuario", "key", "usuario"),
                Map.of("label", "Tipo", "key", "tipoMovimiento"),
                Map.of("label", "Detalles", "key", "notas"));
    }
}
