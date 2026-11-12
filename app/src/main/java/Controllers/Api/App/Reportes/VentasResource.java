package Controllers.Api.App.Reportes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.jboss.logging.Logger;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import Models.ComprobantesEmitidos;
import Models.Detalles.LineaDetalle;
import Models.Usuarios;
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
 * {@link ComprobantesEmitidosService#listAllEmitidosBy(Usuarios, Date, Date)}
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

    private static final Logger LOG = Logger.getLogger(VentasResource.class);

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
        for (Usuarios u : userService.listAll()) {
            usuarios.add(Tablas.fila("id", u.getId(), "username", u.getUsername()));
        }

        Date inicio = Tablas.fecha(desde, false);
        Date fin = Tablas.fecha(hasta, true);
        if (inicio == null || fin == null) {
            // Default last 30 days for invoice-line sales reports.
            Calendar cal = Calendar.getInstance();
            fin = new Date();
            cal.add(Calendar.DAY_OF_MONTH, -30);
            inicio = cal.getTime();
        }
        if (inicio == null || fin == null) {
            Calendar cal = Calendar.getInstance();
            fin = new Date();
            cal.add(Calendar.DAY_OF_MONTH, -30);
            inicio = cal.getTime();
        }
        if (desde == null || desde.isBlank()) {
            filtros.put("desde", new java.text.SimpleDateFormat("yyyy-MM-dd").format(inicio));
        }
        if (hasta == null || hasta.isBlank()) {
            filtros.put("hasta", new java.text.SimpleDateFormat("yyyy-MM-dd").format(fin));
        }

        List<Map<String, Object>> filas = new ArrayList<>();
        // Sin usuario = todos los cajeros. La consulta trae SOLO las líneas
        // (fecha, cajero, artículo, cantidad y monto) con LIMIT/OFFSET en SQL;
        // antes hidrataba los comprobantes completos con cinco colecciones y
        // Hibernate abortaba el reporte.
        boolean consultaCompleta = inicio != null && fin != null;
        boolean todos = usuario == null || usuario.isBlank();
        Usuarios cajero = null;
        if (!todos) {
            try {
                cajero = userService.find(Long.valueOf(usuario.trim()));
            } catch (NumberFormatException e) {
                LOG.warn("Cajero invalido en ventas por cajero, se listan todos: " + usuario
                        + " | source=VentasResource.get()");
            }
        }

        BigDecimal total = BigDecimal.ZERO;
        long totalFilas = 0;
        int pageSize = Math.min(Math.max(size, 1), 100);
        int totalPages = 1;
        int current = 1;
        if (consultaCompleta) {
            totalFilas = comprobanteService.countVentasLineas(cajero, inicio, fin);
            BigDecimal suma = comprobanteService.sumVentasLineas(cajero, inicio, fin);
            total = suma == null ? BigDecimal.ZERO : suma;
            totalPages = Tablas.totalPaginas(totalFilas, pageSize);
            current = Math.min(Math.max(page, 1), Math.max(totalPages, 1));
            for (Object[] fila : comprobanteService.pageVentasLineas(
                    cajero, inicio, fin, sort, dir, (current - 1) * pageSize, pageSize)) {
                filas.add(Tablas.fila(
                        "fecha", fmtFecha((LocalDateTime) fila[0]),
                        "cajero", fila[1] == null ? "-" : fila[1].toString(),
                        "detalle", fila[2],
                        "cantidad", Tablas.fmtNumero((BigDecimal) fila[3]),
                        "montoTotalLinea", Tablas.fmtColones((BigDecimal) fila[4])));
            }
        }

        boolean sinOrden = sort == null || sort.isBlank();
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("titulo", "Reporte de Ventas por Cajero");
        model.put("baseUrl", BASE_URL);
        model.put("columnas", columnas());
        model.put("filas", filas);
        model.put("sortKey", sinOrden ? "fecha" : sort);
        model.put("sortDir", sinOrden ? "desc" : dir);
        model.put("page", current);
        model.put("size", pageSize);
        model.put("total", totalFilas);
        model.put("totalPages", totalPages);
        model.put("pages", Tablas.ventanaPaginas(current, totalPages));
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
                Map.of("label", "Fecha", "key", "fecha"),
                Map.of("label", "Cajero", "key", "cajero"),
                Map.of("label", "Artículo", "key", "detalle"),
                Map.of("label", "Cantidad", "key", "cantidad"),
                Map.of("label", "Precio", "key", "montoTotalLinea"));
    }

    @Nonnull
    private static String fmtFecha(@Nullable LocalDateTime fecha) {
        return fecha == null ? "-" : fecha.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
    }
}


