package Controllers.Api.App.Reportes;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
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
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import Services.SeasonalityService;

/**
 * Read-only "Análisis de Estacionalidad" page
 * ({@code GET /app/reportes/estacionalidad}) replacing
 * {@code secured/pages/Reportes/Estacionalidad/index.xhtml} (plan T20).
 *
 * <p>Same data as the legacy {@code seasonalityController} over
 * {@link SeasonalityService}: monthly sales, sales by day of week (Lunes…
 * Domingo, zero-filled), by department and by family, plus the daily-sales
 * table — all rendered as server-side tables with the legacy date-range
 * defaults (last 12 months).</p>
 *
 * <p>{! TODO(T29): the four p:chart visualizations are intentionally NOT
 * ported yet; Chart.js wiring is plan task T29 ("Estacionalidad charts if not
 * done in T20"). Until then this page renders the same aggregates as tables.
 * !}</p>
 *
 * <p>Role gate mirrors web.xml: Reportes/Estacionalidad sat under the generic
 * {@code /secured/*} any-authenticated constraint. Mutations: none.</p>
 */
@Path("/app/reportes/estacionalidad")
@Produces(MediaType.TEXT_HTML)
public class EstacionalidadReportesResource {

    private static final String BASE_URL = "/app/reportes/estacionalidad";
    private static final String[] DAY_NAMES = {
            "Lunes", "Martes", "Mi\u00e9rcoles", "Jueves", "Viernes", "S\u00e1bado", "Domingo"};

    @Inject
    @Nonnull
    @Location("pages/reportes/estacionalidad")
    Template page;

    @Inject
    @Nonnull
    SeasonalityService seasonalityService;

    @Inject
    @Nonnull
    HttpHeaders httpHeaders;

    @GET
    public Response render(
            @QueryParam("inicio") @Nullable String inicio,
            @QueryParam("fin") @Nullable String fin) {

        LocalDate end = orFallback(ReportePageSupport.parseDate(fin), LocalDate.now());
        LocalDate start = orFallback(ReportePageSupport.parseDate(inicio), end.minusMonths(12));
        Date startDate = Date.from(start.atStartOfDay(ZoneId.systemDefault()).toInstant());
        Date endDate = Date.from(end.atStartOfDay(ZoneId.systemDefault()).toInstant());

        List<Map<String, Object>> mensual = new ArrayList<>();
        for (Map.Entry<YearMonth, BigDecimal> e : seasonalityService.getMonthlySales(startDate, endDate).entrySet()) {
            mensual.add(Map.of(
                    "mes", e.getKey().format(java.time.format.DateTimeFormatter.ofPattern("MMM yyyy", Locale.forLanguageTag("es"))),
                    "total", e.getValue()));
        }

        Map<Integer, BigDecimal> porDia = seasonalityService.getSalesByDayOfWeek(startDate, endDate);
        List<Map<String, Object>> dias = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            dias.add(Map.of(
                    "dia", DAY_NAMES[i - 1],
                    "total", porDia.getOrDefault(i, BigDecimal.ZERO)));
        }

        List<Map<String, Object>> departamentos = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : seasonalityService.getSalesByDepartment(startDate, endDate).entrySet()) {
            departamentos.add(Map.of("nombre", e.getKey(), "total", e.getValue().abs()));
        }

        List<Map<String, Object>> familias = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : seasonalityService.getSalesByFamily(startDate, endDate).entrySet()) {
            familias.add(Map.of("nombre", e.getKey(), "total", e.getValue().abs()));
        }

        List<Map<String, Object>> diarios = new ArrayList<>();
        for (Object[] row : seasonalityService.getDailySales(startDate, endDate)) {
            diarios.add(Map.of(
                    "fecha", row[0],
                    "total", row[1] != null ? row[1] : BigDecimal.ZERO));
        }

        Map<String, Object> model = ReportePageSupport.model(
                "mensual", mensual,
                "dias", dias,
                "departamentos", departamentos,
                "familias", familias,
                "diarios", diarios,
                "filtroInicio", start.toString(),
                "filtroFin", end.toString(),
                "filtros", ReportePageSupport.params("inicio", inicio, "fin", fin),
                "baseUrl", BASE_URL);

        TemplateInstance instance = ReportePageSupport.isHxRequest(httpHeaders)
                ? page.getFragment("tabla").instance()
                : page.instance();
        model.forEach(instance::data);
        return Response.ok(instance.render())
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                .build();
    }

    private static LocalDate orFallback(@Nullable LocalDate parsed, @Nonnull LocalDate fallback) {
        return parsed != null ? parsed : fallback;
    }
}
