package Controllers.Api.App.Reportes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import Services.StockForecastService;
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
 * Pronósticos de Inventario for the NEW app surface — port of
 * {@code secured/pages/Inventario/Reportes/Pronosticos/index.xhtml}
 * (plan task T19, read-only).
 *
 * <p>Unlike the legacy {@code StockForecastUIBean} (hardcoded sample rows),
 * this resource is backed DIRECTLY by the existing
 * {@link StockForecastService}: {@link StockForecastService#generateBulkForecast(int)}
 * for the demand forecast section and
 * {@link StockForecastService#getInventoryHealthReport()} for the health
 * section — no HTTP self-calls. The legacy three-tab layout becomes a
 * {@code seccion} query param (pronosticos|salud|reorden) driving one
 * server-rendered kit table; the {@code dias} filter maps to the legacy
 * "Días de Pronóstico" select.</p>
 *
 * <p>The page embeds a &lt;canvas&gt; fed by an inline script pulling JSON from
 * the EXISTING {@code /api/stock-forecast/bulk-forecast} endpoint with
 * {@code credentials=same-origin}.</p>
 */
@Path("/app/reportes/inventario/pronosticos")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"admin", "inventario"})
public class PronosticosResource {

    private static final String BASE_URL = "/app/reportes/inventario/pronosticos";

    @Inject
    @Nonnull
    StockForecastService stockForecastService;

    @Inject
    @Location("pages/reportes/pronosticos")
    Template pagina;

    @Inject
    @Location("pages/reportes/_tablas/pronosticos")
    Template tabla;

    @GET
    @Transactional
    public Response get(
            @Context @Nonnull HttpHeaders headers,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("10") int size,
            @QueryParam("sort") @Nullable String sort,
            @QueryParam("dir") @DefaultValue("asc") String dir,
            @QueryParam("dias") @DefaultValue("30") String dias,
            @QueryParam("seccion") @Nullable String seccion) {

        String vista = normalizarSeccion(seccion);
        int diasPronostico = normalizarDias(dias);
        Map<String, String> filtros = new LinkedHashMap<>();
        filtros.put("dias", String.valueOf(diasPronostico));
        filtros.put("seccion", vista);

        List<Map<String, Object>> filas = new ArrayList<>();
        if ("pronosticos".equals(vista)) {
            List<StockForecastService.ProductForecast> pronosticos =
                    stockForecastService.generateBulkForecast(diasPronostico);
            if (pronosticos != null) {
                for (StockForecastService.ProductForecast pronostico : pronosticos) {
                    filas.add(Tablas.fila(
                            "articulo", pronostico.articuloNombre(),
                            "stockActual", String.valueOf(pronostico.currentStock()),
                            "demandaEstimada", String.valueOf(pronostico.predictedSales()),
                            "diasRestantes", diasRestantes(pronostico),
                            "recomendacion", recomendacion(pronostico)));
                }
            }
        } else if ("salud".equals(vista)) {
            StockForecastService.InventoryHealthReport salud =
                    stockForecastService.getInventoryHealthReport();
            // The service report carries aggregate counters + critical item names.
            if (salud != null && salud.criticalItems() != null) {
                for (String critico : salud.criticalItems()) {
                    filas.add(Tablas.fila(
                            "articulo", critico,
                            "estado", "Cr\u00edtico"));
                }
            }
        } else {
            List<StockForecastService.ProductForecast> pronosticos =
                    stockForecastService.generateBulkForecast(diasPronostico);
            if (pronosticos != null) {
                for (StockForecastService.ProductForecast pronostico : pronosticos) {
                    int sugerido = Math.max(0,
                            pronostico.avgDailySales().multiply(
                                    BigDecimal.valueOf(diasPronostico))
                                    .setScale(0, java.math.RoundingMode.HALF_UP).intValue()
                                    - pronostico.currentStock());
                    filas.add(Tablas.fila(
                            "articulo", pronostico.articuloNombre(),
                            "stockActual", String.valueOf(pronostico.currentStock()),
                            "cantidadSugerida", String.valueOf(sugerido),
                            "prioridad", recomendacion(pronostico)));
                }
            }
        }

        Tablas.ordenar(filas, sort, dir);
        long totalFilas = filas.size();
        int totalPages = Tablas.totalPaginas(totalFilas, size);

        StockForecastService.InventoryHealthReport salud =
                stockForecastService.getInventoryHealthReport();

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("titulo", "Pron\u00f3sticos de Inventario");
        model.put("baseUrl", BASE_URL);
        model.put("columnas", columnas(vista));
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
        model.put("dias", diasPronostico);
        model.put("totalProductos", salud != null ? salud.totalProducts() : 0);
        model.put("saludables", salud != null ? salud.optimal() : 0);
        model.put("stockBajo", salud != null ? salud.lowStock() : 0);
        model.put("sinStock", salud != null ? salud.outOfStock() : 0);

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
            return "pronosticos";
        }
        return switch (seccion) {
            case "salud", "reorden" -> seccion;
            default -> "pronosticos";
        };
    }

    private static int normalizarDias(@Nullable String dias) {
        try {
            int valor = Integer.parseInt(dias == null ? "30" : dias.trim());
            if (valor == 7 || valor == 14 || valor == 30 || valor == 60) {
                return valor;
            }
        } catch (NumberFormatException e) {
            // fall through to default
        }
        return 30;
    }

    /** Legacy tag semantics: Comprar (danger) / Pronto (warning) / OK (success). */
    @Nonnull
    private static String recomendacion(@Nonnull StockForecastService.ProductForecast p) {
        if (p.shouldReorder() && p.predictedStock() <= 0) {
            return "Comprar";
        }
        if (p.shouldReorder()) {
            return "Pronto";
        }
        return "OK";
    }

    /** Days of cover left at the average daily sales rate. */
    @Nonnull
    private static String diasRestantes(@Nonnull StockForecastService.ProductForecast p) {
        BigDecimal promedio = p.avgDailySales();
        if (promedio == null || promedio.compareTo(BigDecimal.ZERO) <= 0) {
            return "-";
        }
        int dias = BigDecimal.valueOf(p.currentStock())
                .divide(promedio, 0, java.math.RoundingMode.DOWN).intValue();
        return String.valueOf(Math.max(0, dias));
    }

    @Nonnull
    private static List<Map<String, Object>> columnas(@Nonnull String vista) {
        if ("salud".equals(vista)) {
            return List.of(
                    Map.of("label", "Artículo", "key", "articulo"),
                    Map.of("label", "Estado", "key", "estado"));
        }
        if ("reorden".equals(vista)) {
            return List.of(
                    Map.of("label", "Artículo", "key", "articulo"),
                    Map.of("label", "Stock Actual", "key", "stockActual"),
                    Map.of("label", "Cantidad Sugerida", "key", "cantidadSugerida"),
                    Map.of("label", "Prioridad", "key", "prioridad"));
        }
        return List.of(
                Map.of("label", "Artículo", "key", "articulo"),
                Map.of("label", "Stock Actual", "key", "stockActual"),
                Map.of("label", "Demanda Pronosticada", "key", "demandaEstimada"),
                Map.of("label", "Días Restantes", "key", "diasRestantes"),
                Map.of("label", "Recomendación", "key", "recomendacion"));
    }
}
