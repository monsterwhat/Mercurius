package Controllers.Api.App.Reportes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import Models.ComprobantesEmitidos;
import Models.Detalles.LineaDetalle;
import Models.Users;
import Services.ComprobantesEmitidosService;
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
 * Reporte de ventas por cajero for the NEW Qute/HTMX app surface — port of the
 * legacy JSF page {@code secured/pages/Articulos/Reportes/Ventas/index.xhtml}
 * (plan task T19, read-only).
 *
 * <p>Backed DIRECTLY by the existing services (no HTTP self-calls):
 * {@link UserService#listAll()} fills the cashier filter and
 * {@link ComprobantesEmitidosService#listAllEmitidosBy(Users, Date, Date)}
 * feeds the flattened {@link LineaDetalle} table — exactly what
 * {@code ReportesDiariosController.cargarVentasPorCajero()} did for the JSF
 * view. The legacy "Cargar Reportes" postback becomes a plain GET with query
 * params ({@code usuario}, {@code desde}, {@code hasta}); when any of them is
 * missing the page shows the legacy prompt instead of a table.</p>
 *
 * <p>Kit contract (docs/ui-kit.md §3.1/§2.9): GET without the {@code HX-Request}
 * header renders the full page; with it, only the table fragment. Export goes
 * through the shared T17 endpoint via the §6 form pattern.</p>
 */
@Path("/app/reportes/articulos/ventas")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"admin", "inventario"})
public class VentasResource {

    private static final String BASE_URL = "/app/reportes/articulos/ventas";

    @Inject
    @Nonnull
    UserService userService;

    @Inject
    @Nonnull
    ComprobantesEmitidosService comprobanteService;

    @Inject
    @Location("pages/reportes/ventas")
    Template pagina;

    @Inject
    @Location("pages/reportes/_tablas/ventas")
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

        // Cashier dropdown (legacy f:selectItems over usuarios).
        List<Map<String, Object>> usuarios = new ArrayList<>();
        for (Users u : userService.listAll()) {
            usuarios.add(Tablas.fila("id", u.getId(), "username", u.getUsername()));
        }

        Date inicio = Tablas.fecha(desde, false);
        Date fin = Tablas.fecha(hasta, true);

        List<Map<String, Object>> filas = new ArrayList<>();
        BigDecimal total = null;
        boolean consultaCompleta = usuario != null && !usuario.isBlank()
                && inicio != null && fin != null;

        if (consultaCompleta) {
            Users cajero = userService.find(Long.valueOf(usuario.trim()));
            if (cajero != null) {
                total = BigDecimal.ZERO;
                List<ComprobantesEmitidos> comprobantes =
                        comprobanteService.listAllEmitidosBy(cajero, inicio, fin);
                if (comprobantes != null) {
                    for (ComprobantesEmitidos comprobante : comprobantes) {
                        if (comprobante.getDetalles() == null
                                || comprobante.getDetalles().getLineasDetalle() == null) {
                            continue;
                        }
                        for (LineaDetalle linea : comprobante.getDetalles().getLineasDetalle()) {
                            BigDecimal monto = linea.getMontoTotalLinea();
                            if (monto != null) {
                                total = total.add(monto);
                            }
                            filas.add(Tablas.fila(
                                    "detalle", linea.getDetalle(),
                                    "cantidad", Tablas.fmtNumero(linea.getCantidad()),
                                    "montoTotalLinea", Tablas.fmtColones(monto)));
                        }
                    }
                }
            }
        }

        Tablas.ordenar(filas, sort, dir);
        long totalFilas = filas.size();
        int totalPages = Tablas.totalPaginas(totalFilas, size);
        List<Map<String, Object>> visibles = Tablas.paginaDe(filas, page, size);

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("titulo", "Reporte de Ventas por Cajero");
        model.put("baseUrl", BASE_URL);
        model.put("columnas", columnas());
        model.put("filas", visibles);
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
        model.put("totalGeneral", total != null ? Tablas.fmtColones(total) : null);

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
                Map.of("label", "Nombre", "key", "detalle"),
                Map.of("label", "Cantidad", "key", "cantidad"),
                Map.of("label", "Precio", "key", "montoTotalLinea"));
    }
}
