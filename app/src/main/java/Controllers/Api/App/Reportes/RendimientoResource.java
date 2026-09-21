package Controllers.Api.App.Reportes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import Services.ProductPerformanceService;
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
 * Rendimiento de Productos for the NEW app surface — port of
 * {@code secured/pages/Articulos/Reportes/Rendimiento/index.xhtml}
 * (plan task T19, read-only).
 *
 * <p>Unlike the legacy {@code ProductPerformanceUIBean} (a @ViewScoped bean
 * that rendered hardcoded sample data), this resource is backed DIRECTLY by the
 * existing {@link ProductPerformanceService}: best/worst sellers, best by
 * revenue, department performance and the performance summary — no HTTP
 * self-calls. The legacy four-tab layout becomes a {@code seccion} query param
 * (mas|menos|ingresos|departamento) driving one server-rendered kit table.</p>
 *
 * <p>The page embeds a &lt;canvas&gt; fed by an inline script pulling JSON from
 * the EXISTING {@code /api/product-performance/department-performance}
 * endpoint with {@code credentials=same-origin}.</p>
 */
@Path("/app/reportes/articulos/rendimiento")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"admin", "inventario"})
public class RendimientoResource {

    private static final String BASE_URL = "/app/reportes/articulos/rendimiento";
    private static final int LIMITE = 10;

    @Inject
    @Nonnull
    ProductPerformanceService productPerformanceService;

    @Inject
    @Location("pages/reportes/rendimiento")
    Template pagina;

    @Inject
    @Location("pages/reportes/_tablas/rendimiento")
    Template tabla;

    @GET
    @Transactional
    public Response get(
            @Context @Nonnull HttpHeaders headers,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("10") int size,
            @QueryParam("sort") @Nullable String sort,
            @QueryParam("dir") @DefaultValue("asc") String dir,
            @QueryParam("desde") @Nullable String desde,
            @QueryParam("hasta") @Nullable String hasta,
            @QueryParam("seccion") @Nullable String seccion) {

        String vista = normalizarSeccion(seccion);
        Map<String, String> filtros = new LinkedHashMap<>();
        filtros.put("desde", desde);
        filtros.put("hasta", hasta);
        filtros.put("seccion", vista);

        Date inicio = Tablas.fecha(desde, false);
        Date fin = Tablas.fecha(hasta, true);
        if (inicio == null || fin == null) {
            // Legacy ProductPerformanceUIBean defaults: last 30 days.
            Calendar cal = Calendar.getInstance();
            fin = new Date();
            cal.add(Calendar.DAY_OF_MONTH, -30);
            inicio = cal.getTime();
        }

        List<Map<String, Object>> filas = new ArrayList<>();
        boolean seccionDepartamento = "departamento".equals(vista);

        if (seccionDepartamento) {
            Map<String, BigDecimal> rendimiento =
                    productPerformanceService.getDepartmentPerformance(inicio, fin);
            BigDecimal totalVentas = BigDecimal.ZERO;
            if (rendimiento != null) {
                for (BigDecimal valor : rendimiento.values()) {
                    totalVentas = totalVentas.add(valor == null ? BigDecimal.ZERO : valor);
                }
                for (Map.Entry<String, BigDecimal> entry : rendimiento.entrySet()) {
                    String porcentaje = BigDecimal.ZERO.compareTo(totalVentas) == 0
                            ? "0.0"
                            : Tablas.nuloACero(entry.getValue())
                                    .multiply(BigDecimal.valueOf(100))
                                    .divide(totalVentas, 1, RoundingMode.HALF_UP)
                                    .toPlainString();
                    filas.add(Tablas.fila(
                            "nombre", entry.getKey(),
                            "ventas", Tablas.fmtColones(entry.getValue()),
                            "porcentaje", porcentaje));
                }
            }
        } else {
            List<ProductPerformanceService.ProductSalesSummary> resumen = switch (vista) {
                case "menos" -> productPerformanceService.getWorstSellingProducts(inicio, fin, LIMITE);
                case "ingresos" -> productPerformanceService.getBestSellingProductsByRevenue(inicio, fin, LIMITE);
                default -> productPerformanceService.getBestSellingProducts(inicio, fin, LIMITE);
            };
            if (resumen != null) {
                for (ProductPerformanceService.ProductSalesSummary producto : resumen) {
                    filas.add(Tablas.fila(
                            "nombre", producto.getProductName(),
                            "cantidad", producto.getQuantitySold() != null
                                    ? producto.getQuantitySold().toString() : "0",
                            "ingresos", Tablas.fmtColones(producto.getTotalRevenue())));
                }
            }
        }

        Tablas.ordenar(filas, sort, dir);
        long totalFilas = filas.size();
        int totalPages = Tablas.totalPaginas(totalFilas, size);

        // Header stats (legacy stat-cards), now from the REAL service summary.
        ProductPerformanceService.ProductPerformanceSummary resumen =
                productPerformanceService.getPerformanceSummary(inicio, fin);
        String categoriaTop = "-";
        Map<String, BigDecimal> departamentos =
                productPerformanceService.getDepartmentPerformance(inicio, fin);
        if (departamentos != null && !departamentos.isEmpty()) {
            Map.Entry<String, BigDecimal> mejor = null;
            for (Map.Entry<String, BigDecimal> entry : departamentos.entrySet()) {
                if (mejor == null || (entry.getValue() != null
                        && entry.getValue().compareTo(
                                mejor.getValue() == null ? BigDecimal.ZERO : mejor.getValue()) > 0)) {
                    mejor = entry;
                }
            }
            if (mejor != null) {
                categoriaTop = mejor.getKey();
            }
        }

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("titulo", "Rendimiento de Productos");
        model.put("baseUrl", BASE_URL);
        model.put("columnas", columnas(seccionDepartamento));
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
        model.put("seccion", vista);
        model.put("totalProductos", resumen != null ? resumen.getUniqueProductsSold() : 0);
        model.put("totalIngresos", resumen != null
                ? Tablas.fmtColones(resumen.getTotalRevenue()) : "-");
        model.put("precioPromedio", resumen != null
                ? Tablas.fmtColones(resumen.getAverageTicket()) : "-");
        model.put("categoriaTop", categoriaTop);

        boolean fragmento = headers.getHeaderString("HX-Request") != null;
        Template plantilla = fragmento ? tabla : pagina;
        String html = plantilla.data(model).render();
        return Response.ok(html)
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                .build();
    }

    @Nonnull
    private static String normalizarSeccion(@Nullable String seccion) {
        if (seccion == null) {
            return "mas";
        }
        return switch (seccion) {
            case "menos", "ingresos", "departamento" -> seccion;
            default -> "mas";
        };
    }

    @Nonnull
    private static List<Map<String, Object>> columnas(boolean seccionDepartamento) {
        if (seccionDepartamento) {
            return List.of(
                    Map.of("label", "Departamento", "key", "nombre"),
                    Map.of("label", "Ventas", "key", "ventas"),
                    Map.of("label", "Porcentaje", "key", "porcentaje"));
        }
        return List.of(
                Map.of("label", "Nombre", "key", "nombre"),
                Map.of("label", "Cantidad Vendida", "key", "cantidad"),
                Map.of("label", "Ingresos", "key", "ingresos"));
    }
}
