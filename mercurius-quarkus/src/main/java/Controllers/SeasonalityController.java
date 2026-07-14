package Controllers;

import Services.SeasonalityService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Getter @Setter @ToString @EqualsAndHashCode
@Named("seasonalityController")
@ViewScoped
public class SeasonalityController implements Serializable {

    @Inject
    @Nonnull
    private SeasonalityService seasonalityService;

    @Nonnull
    private Date startDate;
    @Nonnull
    private Date endDate;

    @Nullable
    private String monthlyTrendConfig;
    @Nullable
    private String dayOfWeekConfig;
    @Nullable
    private String departmentConfig;
    @Nullable
    private String familyConfig;

    @Nullable
    private List<DailySalesRow> dailySalesData;

    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("MMM yyyy", Locale.forLanguageTag("es"));
    private static final String[] DAY_NAMES = {"Lunes", "Martes", "Mi\u00e9rcoles", "Jueves", "Viernes", "S\u00e1bado", "Domingo"};
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @PostConstruct
    public void init() {
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusMonths(12);
        startDate = Date.from(start.atStartOfDay(ZoneId.systemDefault()).toInstant());
        endDate = Date.from(end.atStartOfDay(ZoneId.systemDefault()).toInstant());
        refreshData();
    }

    public void refreshData() {
        buildMonthlyTrendConfig();
        buildDayOfWeekConfig();
        buildDepartmentConfig();
        buildFamilyConfig();
        loadDailySalesData();
    }

    private void buildMonthlyTrendConfig() {
        Map<YearMonth, BigDecimal> monthlySales = seasonalityService.getMonthlySales(startDate, endDate);

        List<String> labels = new ArrayList<>();
        List<Number> values = new ArrayList<>();

        for (Map.Entry<YearMonth, BigDecimal> entry : monthlySales.entrySet()) {
            labels.add(entry.getKey().format(MONTH_FORMATTER));
            values.add(entry.getValue());
        }

        Map<String, Object> dataset = new LinkedHashMap<>();
        dataset.put("label", "Ventas Mensuales");
        dataset.put("data", values);
        dataset.put("fill", false);
        dataset.put("borderColor", "rgb(75, 192, 192)");
        dataset.put("tension", 0.1);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("labels", labels);
        data.put("datasets", Collections.singletonList(dataset));

        Map<String, Object> options = defaultOptions(true);

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("type", "line");
        config.put("data", data);
        config.put("options", options);

        monthlyTrendConfig = toJson(config);
    }

    private void buildDayOfWeekConfig() {
        Map<Integer, BigDecimal> daySales = seasonalityService.getSalesByDayOfWeek(startDate, endDate);

        List<String> labels = new ArrayList<>();
        List<Number> values = new ArrayList<>();

        for (int i = 1; i <= 7; i++) {
            labels.add(DAY_NAMES[i - 1]);
            values.add(daySales.getOrDefault(i, BigDecimal.ZERO));
        }

        Map<String, Object> dataset = new LinkedHashMap<>();
        dataset.put("label", "Ventas por D\u00eda de la Semana");
        dataset.put("data", values);
        dataset.put("backgroundColor", Arrays.asList(
            "rgba(54, 162, 235, 0.6)",
            "rgba(255, 99, 132, 0.6)",
            "rgba(255, 206, 86, 0.6)",
            "rgba(75, 192, 192, 0.6)",
            "rgba(153, 102, 255, 0.6)",
            "rgba(255, 159, 64, 0.6)",
            "rgba(201, 203, 207, 0.6)"
        ));
        dataset.put("borderColor", Arrays.asList(
            "rgb(54, 162, 235)",
            "rgb(255, 99, 132)",
            "rgb(255, 206, 86)",
            "rgb(75, 192, 192)",
            "rgb(153, 102, 255)",
            "rgb(255, 159, 64)",
            "rgb(201, 203, 207)"
        ));
        dataset.put("borderWidth", 1);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("labels", labels);
        data.put("datasets", Collections.singletonList(dataset));

        Map<String, Object> options = defaultOptions(false);

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("type", "bar");
        config.put("data", data);
        config.put("options", options);

        dayOfWeekConfig = toJson(config);
    }

    private void buildDepartmentConfig() {
        Map<String, BigDecimal> deptSales = seasonalityService.getSalesByDepartment(startDate, endDate);

        List<String> labels = new ArrayList<>(deptSales.keySet());
        List<Number> values = new ArrayList<>();
        for (BigDecimal v : deptSales.values()) {
            values.add(v.abs());
        }

        Map<String, Object> dataset = new LinkedHashMap<>();
        dataset.put("label", "Ventas por Departamento");
        dataset.put("data", values);
        dataset.put("backgroundColor", "rgba(54, 162, 235, 0.6)");
        dataset.put("borderColor", "rgb(54, 162, 235)");
        dataset.put("borderWidth", 1);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("labels", labels);
        data.put("datasets", Collections.singletonList(dataset));

        Map<String, Object> options = defaultOptions(false);

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("type", "bar");
        config.put("data", data);
        config.put("options", options);

        departmentConfig = toJson(config);
    }

    private void buildFamilyConfig() {
        Map<String, BigDecimal> familySales = seasonalityService.getSalesByFamily(startDate, endDate);

        List<String> labels = new ArrayList<>(familySales.keySet());
        List<Number> values = new ArrayList<>();
        for (BigDecimal v : familySales.values()) {
            values.add(v.abs());
        }

        Map<String, Object> dataset = new LinkedHashMap<>();
        dataset.put("label", "Ventas por Familia");
        dataset.put("data", values);
        dataset.put("backgroundColor", "rgba(153, 102, 255, 0.6)");
        dataset.put("borderColor", "rgb(153, 102, 255)");
        dataset.put("borderWidth", 1);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("labels", labels);
        data.put("datasets", Collections.singletonList(dataset));

        Map<String, Object> options = defaultOptions(false);

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("type", "bar");
        config.put("data", data);
        config.put("options", options);

        familyConfig = toJson(config);
    }

    private void loadDailySalesData() {
        List<Object[]> rawData = seasonalityService.getDailySales(startDate, endDate);
        dailySalesData = new ArrayList<>();
        for (Object[] row : rawData) {
            dailySalesData.add(new DailySalesRow((java.time.LocalDate) row[0], (BigDecimal) row[1]));
        }
    }

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

    @Data
    public static class DailySalesRow implements Serializable {
        @Nonnull
        private java.time.LocalDate date;
        @Nonnull
        private BigDecimal total;

        public DailySalesRow(@Nonnull java.time.LocalDate date, @Nullable BigDecimal total) {
            this.date = date;
            this.total = total != null ? total : BigDecimal.ZERO;
        }
    }
}
