package Controllers;

import Models.DepartamentoMetrico;
import Services.DepartamentoMetricoService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Controller para el reporte de Métricas de Proveedores (Departamentos).
 * Proporciona datos para charts, ranking, y resumen.
 */
@Getter
@Setter
@ToString
@EqualsAndHashCode
@Named("departamentoMetricoController")
@ViewScoped
public class DepartamentoMetricoController implements Serializable {

    private static final Logger LOG = Logger.getLogger(DepartamentoMetricoController.class.getName());
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Inject
    @Nonnull
    private DepartamentoMetricoService departamentoMetricoService;

    @Nullable
    private String topVolumeChartConfig;

    @Nullable
    private String onTimeDeliveryChartConfig;

    @Nullable
    private String scoreDistributionConfig;

    @PostConstruct
    public void init() {
        refreshCharts();
    }

    public void refreshCharts() {
        buildTopVolumeChart();
        buildOnTimeDeliveryChart();
        buildScoreDistributionChart();
    }

    /**
     * Returns all metrics sorted by score descending.
     */
    @Nonnull
    public List<DepartamentoMetrico> getMetricas() {
        List<DepartamentoMetrico> list = departamentoMetricoService.listAll();
        return list != null ? list : Collections.emptyList();
    }

    /**
     * Triggers full recalculation of all department metrics.
     */
    public void calcularMetricas() {
        departamentoMetricoService.calcularTodasLasMetricas();
        refreshCharts();
        FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Éxito", "Métricas recalculadas correctamente"));
    }

    /**
     * Returns Bulma CSS color class based on score.
     */
    @Nonnull
    public String getScoreClass(double score) {
        if (score >= 80) return "has-text-success";
        if (score >= 60) return "has-text-warning";
        return "has-text-danger";
    }

    /**
     * Returns Bulma tag severity for score display.
     */
    @Nonnull
    public String getScoreSeverity(double score) {
        if (score >= 80) return "success";
        if (score >= 60) return "warning";
        return "danger";
    }

    /**
     * Returns CSS width percentage for progress bar.
     */
    @Nonnull
    public String getBarWidth(double score) {
        return "width: " + Math.min(Math.max(score, 0), 100) + "%";
    }

    /**
     * Returns Bulma progress bar color class.
     */
    @Nonnull
    public String getBarColor(double score) {
        if (score >= 80) return "is-success";
        if (score >= 60) return "is-warning";
        return "is-danger";
    }

    /**
     * Summary: total proveedores con métricas.
     */
    public long getTotalProveedores() {
        return getMetricas().size();
    }

    /**
     * Summary: score promedio.
     */
    public double getScorePromedio() {
        return departamentoMetricoService.avgScore();
    }

    /**
     * Summary: compras totales del mes.
     */
    @Nonnull
    public BigDecimal getComprasTotalesMes() {
        return departamentoMetricoService.sumMontoTotalCompras();
    }

    // ─── Chart builders (following SeasonalityController pattern) ───────

    private void buildTopVolumeChart() {
        List<DepartamentoMetrico> metricas = getMetricas();

        // Sort by purchase volume descending, take top 5
        List<DepartamentoMetrico> top5 = metricas.stream()
                .sorted((a, b) -> b.getMontoTotalCompras().compareTo(a.getMontoTotalCompras()))
                .limit(5)
                .toList();

        List<String> labels = new ArrayList<>();
        List<Number> values = new ArrayList<>();

        for (DepartamentoMetrico m : top5) {
            String nombre = m.getDepartamento() != null ? m.getDepartamento().getNombre() : "Sin nombre";
            labels.add(nombre.length() > 15 ? nombre.substring(0, 15) + "..." : nombre);
            values.add(m.getMontoTotalCompras());
        }

        Map<String, Object> dataset = new LinkedHashMap<>();
        dataset.put("label", "Volumen de Compras (₡)");
        dataset.put("data", values);
        dataset.put("backgroundColor", "rgba(54, 162, 235, 0.6)");
        dataset.put("borderColor", "rgb(54, 162, 235)");
        dataset.put("borderWidth", 1);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("labels", labels);
        data.put("datasets", Collections.singletonList(dataset));

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("type", "bar");
        config.put("data", data);
        config.put("options", defaultOptions(false));

        topVolumeChartConfig = toJson(config);
    }

    private void buildOnTimeDeliveryChart() {
        List<DepartamentoMetrico> metricas = getMetricas();

        // All suppliers for comparison
        List<String> labels = new ArrayList<>();
        List<Number> values = new ArrayList<>();
        List<String> barColors = new ArrayList<>();

        for (DepartamentoMetrico m : metricas) {
            String nombre = m.getDepartamento() != null ? m.getDepartamento().getNombre() : "Sin nombre";
            labels.add(nombre.length() > 15 ? nombre.substring(0, 15) + "..." : nombre);
            values.add(m.getTasaOnTimeDelivery());

            if (m.getTasaOnTimeDelivery() >= 80) barColors.add("rgba(75, 192, 192, 0.6)");
            else if (m.getTasaOnTimeDelivery() >= 60) barColors.add("rgba(255, 206, 86, 0.6)");
            else barColors.add("rgba(255, 99, 132, 0.6)");
        }

        Map<String, Object> dataset = new LinkedHashMap<>();
        dataset.put("label", "Entregas a Tiempo (%)");
        dataset.put("data", values);
        dataset.put("backgroundColor", barColors);
        dataset.put("borderWidth", 1);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("labels", labels);
        data.put("datasets", Collections.singletonList(dataset));

        Map<String, Object> options = defaultOptions(false);
        // Add max y-axis at 100
        @SuppressWarnings("unchecked")
        Map<String, Object> scales = (Map<String, Object>) options.get("scales");
        @SuppressWarnings("unchecked")
        Map<String, Object> y = (Map<String, Object>) scales.get("y");
        y.put("max", 100);

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("type", "bar");
        config.put("data", data);
        config.put("options", options);

        onTimeDeliveryChartConfig = toJson(config);
    }

    private void buildScoreDistributionChart() {
        List<DepartamentoMetrico> metricas = getMetricas();

        // Count score distribution: Excelente (80+), Bueno (60-79), Regular (<60)
        int excelentes = 0, buenos = 0, regulares = 0;
        for (DepartamentoMetrico m : metricas) {
            if (m.getScore() >= 80) excelentes++;
            else if (m.getScore() >= 60) buenos++;
            else regulares++;
        }

        List<String> labels = List.of("Excelente (80+)", "Bueno (60-79)", "Regular (<60)");
        List<Number> values = List.of(excelentes, buenos, regulares);

        Map<String, Object> dataset = new LinkedHashMap<>();
        dataset.put("data", values);
        dataset.put("backgroundColor", List.of(
                "rgba(75, 192, 192, 0.6)",
                "rgba(255, 206, 86, 0.6)",
                "rgba(255, 99, 132, 0.6)"
        ));
        dataset.put("borderWidth", 1);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("labels", labels);
        data.put("datasets", Collections.singletonList(dataset));

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("type", "doughnut");
        config.put("data", data);

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("responsive", true);
        options.put("maintainAspectRatio", false);
        Map<String, Object> plugins = new LinkedHashMap<>();
        Map<String, Object> legend = new LinkedHashMap<>();
        legend.put("display", true);
        legend.put("position", "bottom");
        plugins.put("legend", legend);
        options.put("plugins", plugins);
        config.put("options", options);

        scoreDistributionConfig = toJson(config);
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private static Map<String, Object> defaultOptions(boolean showLegend) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("responsive", true);
        options.put("maintainAspectRatio", false);

        Map<String, Object> scales = new LinkedHashMap<>();
        Map<String, Object> y = new LinkedHashMap<>();
        y.put("beginAtZero", true);
        scales.put("y", y);
        options.put("scales", scales);

        Map<String, Object> plugins = new LinkedHashMap<>();
        Map<String, Object> legend = new LinkedHashMap<>();
        legend.put("display", showLegend);
        legend.put("position", "top");
        plugins.put("legend", legend);
        options.put("plugins", plugins);

        return options;
    }

    private static String toJson(Map<String, Object> map) {
        try {
            return OBJECT_MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
