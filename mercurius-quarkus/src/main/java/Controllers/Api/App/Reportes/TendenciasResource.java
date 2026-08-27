package Controllers.Api.App.Reportes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import Services.SalesTrendService;
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
 * Tendencias de Ventas for the NEW app surface — port of
 * {@code secured/pages/Articulos/Reportes/Tendencias/index.xhtml}
 * (plan task T19, read-only).
 *
 * <p>Unlike the legacy {@code SalesTrendUIBean} (sample data in a @ViewScoped
 * bean), this resource is backed DIRECTLY by the existing
 * {@link SalesTrendService}: daily/weekly/monthly time series plus trend
 * indicators and growth metrics — no HTTP self-calls. The legacy four-tab
 * layout becomes a {@code seccion} query param
 * (diarias|semanales|mensuales|indicadores) driving one server-rendered kit
 * table.</p>
 *
 * <p>The page embeds a &lt;canvas&gt; fed by an inline script pulling JSON from
 * the EXISTING {@code /api/sales-trend/daily} endpoint with
 * {@code credentials=same-origin}.</p>
 */
@Path("/app/reportes/articulos/tendencias")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"admin", "inventario"})
public class TendenciasResource {

    private static final String BASE_URL = "/app/reportes/articulos/tendencias";

    private static final String[] MESES = {
            "Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio", "Julio",
            "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre"};

    @Inject
    @Nonnull
    SalesTrendService salesTrendService;

    @Inject
    @Location("pages/reportes/tendencias")
    Template pagina;

    @Inject
    @Location("pages/reportes/_tablas/tendencias")
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
            // Legacy SalesTrendUIBean defaults: last 90 days.
            Calendar cal = Calendar.getInstance();
            fin = new Date();
            cal.add(Calendar.DAY_OF_MONTH, -90);
            inicio = cal.getTime();
        }

        List<Map<String, Object>> filas = new ArrayList<>();
        boolean seccionIndicadores = "indicadores".equals(vista);

        if (seccionIndicadores) {
            // Legacy "Resumen de Tendencias" box: indicators + growth metrics.
            SalesTrendService.TrendIndicators indicadores =
                    salesTrendService.getTrendIndicators(inicio, fin);
            if (indicadores != null) {
                filas.add(Tablas.fila(
                        "concepto", "Crecimiento vs Período Anterior",
                        "valor", porcentaje(indicadores.getRevenueGrowth())));
                filas.add(Tablas.fila(
                        "concepto", "Crecimiento de Transacciones",
                        "valor", porcentaje(indicadores.getTransactionGrowth())));
                filas.add(Tablas.fila(
                        "concepto", "Ingresos Primer Período",
                        "valor", Tablas.fmtColones(indicadores.getFirstPeriodRevenue())));
                filas.add(Tablas.fila(
                        "concepto", "Ingresos Segundo Período",
                        "valor", Tablas.fmtColones(indicadores.getSecondPeriodRevenue())));
            }
            SalesTrendService.GrowthMetrics metricas =
                    salesTrendService.getGrowthMetrics(inicio, fin);
            if (metricas != null) {
                filas.add(Tablas.fila(
                        "concepto", "Ventas Promedio Diarias",
                        "valor", Tablas.fmtColones(metricas.getDailyAverage())));
                filas.add(Tablas.fila(
                        "concepto", "Promedio Semanal",
                        "valor", Tablas.fmtColones(metricas.getWeeklyAverage())));
                filas.add(Tablas.fila(
                        "concepto", "Promedio Mensual",
                        "valor", Tablas.fmtColones(metricas.getMonthlyAverage())));
                filas.add(Tablas.fila(
                        "concepto", "Ticket Promedio",
                        "valor", Tablas.fmtColones(metricas.getAverageTicket())));
            }
        } else {
            List<SalesTrendService.TimeSeriesData> serie = switch (vista) {
                case "semanales" -> salesTrendService.getWeeklySalesTimeSeries(inicio, fin);
                case "mensuales" -> salesTrendService.getMonthlySalesTimeSeries(inicio, fin);
                default -> salesTrendService.getDailySalesTimeSeries(inicio, fin);
            };
            if (serie != null) {
                for (SalesTrendService.TimeSeriesData punto : serie) {
                    filas.add(Tablas.fila(
                            "periodo", etiquetaPeriodo(vista, punto),
                            "ventas", Tablas.fmtColones(punto.getTotalSales()),
                            "transacciones", String.valueOf(punto.getTransactionCount())));
                }
            }
        }

        Tablas.ordenar(filas, sort, dir);
        long totalFilas = filas.size();
        int totalPages = Tablas.totalPaginas(totalFilas, size);

        // Header stats (legacy stat-cards) from the REAL service.
        SalesTrendService.GrowthMetrics metricas =
                salesTrendService.getGrowthMetrics(inicio, fin);
        SalesTrendService.TrendIndicators indicadores =
                salesTrendService.getTrendIndicators(inicio, fin);

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("titulo", "Tendencias de Ventas");
        model.put("baseUrl", BASE_URL);
        model.put("columnas", columnas(seccionIndicadores));
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
        model.put("tasaCrecimiento", indicadores != null
                ? porcentaje(indicadores.getRevenueGrowth()) : "-");
        model.put("ventaDiariaPromedio", metricas != null
                ? Tablas.fmtColones(metricas.getDailyAverage()) : "-");
        model.put("ingresosTotales", metricas != null
                ? Tablas.fmtColones(metricas.getTotalRevenue()) : "-");
        model.put("direccionTendencia", indicadores != null
                && indicadores.getTrendDirection() != null
                ? indicadores.getTrendDirection() : "-");

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
            return "diarias";
        }
        return switch (seccion) {
            case "semanales", "mensuales", "indicadores" -> seccion;
            default -> "diarias";
        };
    }

    /** Human label per series kind (legacy column parity). */
    @Nonnull
    private static String etiquetaPeriodo(@Nonnull String vista,
            @Nonnull SalesTrendService.TimeSeriesData punto) {
        if ("semanales".equals(vista)) {
            Integer anio = punto.getYear();
            Integer semana = punto.getWeekOrMonth();
            return "Semana " + (semana != null ? semana : "-")
                    + " " + (anio != null ? anio : "");
        }
        if ("mensuales".equals(vista)) {
            Integer mes = punto.getWeekOrMonth();
            Integer anio = punto.getYear();
            String nombreMes = mes != null && mes >= 1 && mes <= 12 ? MESES[mes - 1] : "-";
            return nombreMes + " " + (anio != null ? anio : "");
        }
        return punto.getDate() != null ? punto.getDate().toString() : "-";
    }

    @Nonnull
    private static String porcentaje(@Nullable BigDecimal crecimiento) {
        if (crecimiento == null) {
            return "-";
        }
        return crecimiento.multiply(BigDecimal.valueOf(100))
                .setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + "%";
    }

    @Nonnull
    private static List<Map<String, Object>> columnas(boolean seccionIndicadores) {
        if (seccionIndicadores) {
            return List.of(
                    Map.of("label", "Concepto", "key", "concepto"),
                    Map.of("label", "Valor", "key", "valor"));
        }
        return List.of(
                Map.of("label", "Fecha", "key", "periodo"),
                Map.of("label", "Ventas", "key", "ventas"),
                Map.of("label", "Transacciones", "key", "transacciones"));
    }
}
