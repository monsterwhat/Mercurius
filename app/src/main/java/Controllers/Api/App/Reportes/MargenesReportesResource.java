package Controllers.Api.App.Reportes;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import Models.Familia;
import Models.CorteMargen;
import Services.DepartamentoService;
import Services.FamiliaService;
import Services.ProfitAnalysisService;

/**
 * Read-only "Análisis de Márgenes de Utilidad" page
 * ({@code GET /app/reportes/margenes}) replacing
 * {@code secured/pages/Facturas/Reportes/Margenes/index.xhtml} (plan T20).
 *
 * <p>Reproduces the general-analysis path of
 * {@code ProfitAnalysisController.loadProfitAnalysis()} directly over
 * {@link ProfitAnalysisService}: average margin, revenue/profit sums over the
 * department snapshots, the snapshot table plus top/worst-margin article
 * tables, with optional department/family focus (same branch order as the
 * legacy controller: department wins over family). The Excel export goes
 * through the T17 endpoint ({@code dataset=profit-margins}).</p>
 *
 * <p>Role gate mirrors web.xml {@code /secured/pages/Facturas/*}:
 * {@code facturacion} + {@code admin}. Mutations: none.</p>
 */
@Path("/app/reportes/margenes")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"facturacion", "admin"})
public class MargenesReportesResource {

    private static final String BASE_URL = "/app/reportes/margenes";

    @Inject
    @Nonnull
    @Location("pages/reportes/margenes")
    Template pagina;

    @Inject
    @Nonnull
    ProfitAnalysisService profitAnalysisService;

    @Inject
    @Nonnull
    DepartamentoService departamentoService;

    @Inject
    @Nonnull
    FamiliaService familiaService;

    @Inject
    @Nonnull
    HttpHeaders httpHeaders;

    @GET
    public Response render(
            @QueryParam("page") @Nullable Integer page,
            @QueryParam("size") @Nullable Integer size,
            @QueryParam("sort") @Nullable String sort,
            @QueryParam("dir") @DefaultValue("asc") @Nullable String dir,
            @QueryParam("inicio") @Nullable String inicio,
            @QueryParam("fin") @Nullable String fin,
            @QueryParam("departamento") @Nullable String departamento,
            @QueryParam("familia") @Nullable String familia,
            @QueryParam("seccion") @Nullable String seccion) {

        // Sin fechas el reporte muestra TODO el historico: el rango por defecto
        // (30 dias) dejaba fuera once anos de historia.
        LocalDate finDefecto = LocalDate.now();
        LocalDate inicioDefecto = profitAnalysisService.primeraFechaMargen();
        if (inicioDefecto == null) {
            // sin la primera fecha conocida el reporte muestra todo el
            // historico (mejor que recortarlo a 30 dias sin avisar)
            inicioDefecto = LocalDate.of(2000, 1, 1);
        }
        Date endDate = toDate(ReportePageSupport.parseDate(fin), finDefecto);
        Date startDate = toDate(ReportePageSupport.parseDate(inicio), inicioDefecto);

        String tipoGrupo = "departamento";
        String nombreGrupo = null;
        if (departamento != null && !departamento.isBlank()) {
            nombreGrupo = departamento;
        } else if (familia != null && !familia.isBlank()) {
            tipoGrupo = "familia";
            nombreGrupo = familia;
        }

        List<Map<String, Object>> margenes = profitAnalysisService.margenesPorGrupo(
                tipoGrupo, startDate, endDate, nombreGrupo);
        margenes = orEmpty(margenes);

        BigDecimal averageMargin = profitAnalysisService.getAverageProfitMargin(startDate, endDate);
        BigDecimal totalRevenue = margenes.stream()
                .map(f -> (BigDecimal) f.get("ventas"))
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalProfit = margenes.stream()
                .map(f -> (BigDecimal) f.get("utilidad"))
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Map<String, Object>> filasBase = new ArrayList<>();
        for (Map<String, Object> margen : margenes) {
            Map<String, Object> fila = new LinkedHashMap<>(margen);
            fila.put("etiqueta", etiquetaDesempeno((BigDecimal) margen.get("margen")));
            filasBase.add(fila);
        }
        List<Map<String, Object>> filas = sortFilas(filasBase, sort, dir);

        // La grafica se queda con los 12 grupos mas grandes: con todo el
        // historico serian decenas de miles de barras.
        List<Map<String, Object>> paraGrafica = new ArrayList<>(filasBase);
        paraGrafica.sort((a, b) -> ((BigDecimal) b.get("ventas")).compareTo((BigDecimal) a.get("ventas")));
        List<String> chartLabels = new ArrayList<>();
        List<BigDecimal> chartValues = new ArrayList<>();
        for (Map<String, Object> margen : paraGrafica.stream().limit(12).toList()) {
            chartLabels.add(String.valueOf(margen.get("grupo")));
            chartValues.add((BigDecimal) margen.get("ventas"));
        }

        int current = ReportePageSupport.clampPage(page);
        int pageSize = ReportePageSupport.clampSize(size);
        long total = filas.size();
        int totalPages = ReportePageSupport.totalPages(total, pageSize);

        Map<String, Object> model = ReportePageSupport.model(
                "margenesColumnas", columnas(),
                "filas", ReportePageSupport.pageOf(filas, current, pageSize),
                "total", total,
                "totalPages", totalPages,
                "pages", ReportePageSupport.pageWindow(current, totalPages),
                "page", current,
                "size", pageSize,
                "sortKey", sort == null ? "" : sort,
                "sortDir", ReportePageSupport.isDescending(dir) ? "desc" : "asc",
                "filtros", ReportePageSupport.params(
                        "inicio", inicio, "fin", fin,
                        "departamento", departamento, "familia", familia),
                "averageMargin", averageMargin,
                "totalRevenue", totalRevenue,
                "totalProfit", totalProfit,
                "registros", filas.size(),
                "topArticulos", filasArticulo(profitAnalysisService.getTopProfitMarginArticles(10, startDate, endDate)),
                "peoresArticulos", filasArticulo(profitAnalysisService.getWorstProfitMarginArticles(10, startDate, endDate)),
                "comparativoDepartamentos", profitAnalysisService.getDepartmentMarginComparison(startDate, endDate),
                "departamentos", departamentoService.listAll().stream().map(d -> d.getNombre()).toList(),
                "familias", familiaService.listAll().stream()
                        .filter(f -> Boolean.TRUE.equals(f.getStatus()))
                        .map(Familia::getNombre).toList(),
                "filtroInicio", inicio == null ? "" : inicio,
                "filtroFin", fin == null ? "" : fin,
                "filtroDepartamento", departamento == null ? "" : departamento,
                "chartLabels", chartLabels,
                "chartValues", chartValues,
                "filtroFamilia", familia == null ? "" : familia,
                "seccion", seccion == null || seccion.isBlank() ? "resumen" : seccion,
                "baseUrl", BASE_URL);

        TemplateInstance instance = ReportePageSupport.isHxRequest(httpHeaders)
                ? pagina.getFragment("tabla").instance()
                : pagina.instance();
        model.forEach(instance::data);
        return Response.ok(instance.render())
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                .build();
    }

    /**
     * Legacy parity port of ProfitAnalysisController.getMarginPerformanceLabel.
     */
    public static @Nonnull String etiquetaDesempeno(@Nullable BigDecimal margen) {
        if (margen == null) {
            return "Sin Datos";
        }
        if (margen.compareTo(BigDecimal.valueOf(20)) >= 0) {
            return "Excelente";
        }
        if (margen.compareTo(BigDecimal.valueOf(15)) >= 0) {
            return "Bueno";
        }
        if (margen.compareTo(BigDecimal.valueOf(10)) >= 0) {
            return "Regular";
        }
        if (margen.compareTo(BigDecimal.ZERO) > 0) {
            return "Bajo";
        }
        return "Sin Utilidad";
    }

    private static @Nonnull List<Map<String, Object>> columnas() {
        return List.of(
                Map.of("label", "Departamento", "key", "departamento"),
                ReportePageSupport.columna("Familia", null),
                Map.of("label", "Total Ventas", "key", "ventas"),
                Map.of("label", "Utilidad", "key", "utilidad"),
                Map.of("label", "Precio Venta", "key", "precioVenta"),
                Map.of("label", "Precio Costo", "key", "precioCosto"),
                Map.of("label", "Diferencia", "key", "diferencia"),
                Map.of("label", "Diferencia %", "key", "diferenciaPct"),
                Map.of("label", "Margen Promedio", "key", "margen"),
                ReportePageSupport.columna("Desempeño", null));
    }

    private static @Nonnull List<Map<String, Object>> sortFilas(
            @Nonnull List<Map<String, Object>> filas,
            @Nullable String sort, @Nullable String dir) {
        boolean ascending = !ReportePageSupport.isDescending(dir);
        Comparator<Map<String, Object>> comparator = switch (sort == null ? "" : sort) {
            case "departamento" -> ReportePageSupport.sortBy(f -> String.valueOf(f.get("grupo")), ascending);
            case "ventas" -> ReportePageSupport.sortBy(f -> (BigDecimal) f.get("ventas"), ascending);
            case "utilidad" -> ReportePageSupport.sortBy(f -> (BigDecimal) f.get("utilidad"), ascending);
            case "margen" -> ReportePageSupport.sortBy(f -> (BigDecimal) f.get("margen"), ascending);
            case "precioVenta" -> ReportePageSupport.sortBy(f -> (BigDecimal) f.get("precioVenta"), ascending);
            case "precioCosto" -> ReportePageSupport.sortBy(f -> (BigDecimal) f.get("precioCosto"), ascending);
            case "diferencia" -> ReportePageSupport.sortBy(f -> (BigDecimal) f.get("diferencia"), ascending);
            case "diferenciaPct" -> ReportePageSupport.sortBy(f -> (BigDecimal) f.get("diferenciaPct"), ascending);
            default -> null;
        };
        if (comparator != null) {
            List<Map<String, Object>> copy = new ArrayList<>(filas);
            copy.sort(comparator);
            return copy;
        }
        return filas;
    }

    private static Date toDate(@Nullable LocalDate parsed, @Nonnull LocalDate fallback) {
        LocalDate effective = parsed != null ? parsed : fallback;
        return Date.from(effective.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    /**
     * Filas de articulo con la diferencia de precio calculada (en colones y en
     * porcentaje).
     *
     * <p>La diferencia se calcula en el servidor: en la plantilla solo se
     * muestra, y asi no depende de que el motor de expresiones sepa restar
     * BigDecimals.</p>
     */
    private static @Nonnull List<Map<String, Object>> filasArticulo(
            @Nullable List<Models.Articulos.Articulos> articulos) {
        List<Map<String, Object>> filas = new ArrayList<>();
        for (Models.Articulos.Articulos articulo : orEmpty(articulos)) {
            Models.Articulos.ArticuloPrecio precio = articulo.getLastPrecio();
            BigDecimal venta = precio == null || precio.getPrecioFinal() == null
                    ? BigDecimal.ZERO : precio.getPrecioFinal();
            BigDecimal costo = precio == null || precio.getPrecioCostoSinIVA() == null
                    ? BigDecimal.ZERO : precio.getPrecioCostoSinIVA();
            BigDecimal diferencia = venta.subtract(costo);
            BigDecimal diferenciaPct = venta.signum() == 0
                    ? BigDecimal.ZERO
                    : diferencia.multiply(BigDecimal.valueOf(100))
                            .divide(venta, 2, java.math.RoundingMode.HALF_UP);
            Map<String, Object> fila = new LinkedHashMap<>();
            fila.put("codigo", articulo.getCodigo());
            fila.put("nombre", articulo.getNombre());
            fila.put("precioVenta", venta);
            fila.put("precioCosto", costo);
            fila.put("diferencia", diferencia);
            fila.put("diferenciaPct", diferenciaPct);
            filas.add(fila);
        }
        return filas;
    }

    private static @Nonnull <T> List<T> orEmpty(@Nullable List<T> list) {
        return list != null ? list : new ArrayList<>();
    }
}
