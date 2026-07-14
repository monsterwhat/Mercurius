package Controllers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.faces.view.ViewScoped;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import Services.DashboardService;
import Services.DashboardMetricsService;
import Services.AlertasService;
import Models.ComprobantesEmitidos;
import Models.Users;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Named(value = "DashboardController")
@ViewScoped
public class DashboardController implements Serializable {

    @Inject @Nonnull private SessionController sessionController;
    @Inject @Nonnull private DashboardService dashboardService;
    @Inject @Nonnull private DashboardMetricsService dashboardMetricsService;
    @Inject @Nonnull private AlertasService alertasService;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String[] HOUR_LABELS = {
        "00:00","01:00","02:00","03:00","04:00","05:00",
        "06:00","07:00","08:00","09:00","10:00","11:00",
        "12:00","13:00","14:00","15:00","16:00","17:00",
        "18:00","19:00","20:00","21:00","22:00","23:00"
    };
    private static final String[] DAY_NAMES = {
        "Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Dom"
    };

    // Today's stats
    @Nullable private BigDecimal todaySales;
    private int transactionCount;
    private int itemsSold;

    // Last transaction
    @Nullable private ComprobantesEmitidos lastTransaction;
    @Nullable private String lastTransactionDisplay;

    // Recent activity
    @Nullable private List<ComprobantesEmitidos> recentSales;

    // Current date for display
    @Nullable private String currentDate;

    // Metrics from DashboardMetricsService
    @Nullable private BigDecimal yesterdaySales;
    @Nullable private BigDecimal weekSales;
    @Nullable private BigDecimal monthSales;
    @Nullable private BigDecimal avgTicketSize;
    @Nullable private List<DashboardMetricsService.TopProduct> topProducts;
    @Nullable private List<DashboardMetricsService.HourlySales> hourlyDistribution;
    @Nullable private List<DashboardMetricsService.DailySales> weeklyBreakdown;
    @Nullable private DashboardMetricsService.DashboardKPI kpi;

    // Chart config JSON strings
    @Nullable private String hourlyChartConfig;
    @Nullable private String weeklyChartConfig;

    // Flags for lazy loading
    private boolean dataLoaded = false;
    private boolean metricsLoaded = false;

    public DashboardController() {
    }

    @PostConstruct
    public void init() {
    }

    public void loadDashboardData() {
        try {
            if (sessionController != null && sessionController.isValid()) {
                Users currentUser = sessionController.getCurrentUser();
                if (currentUser != null) {
                    todaySales = dashboardService.getTodaySales(currentUser);
                    transactionCount = dashboardService.getTransactionCount(currentUser);
                    itemsSold = dashboardService.getItemsSold(currentUser);
                    lastTransaction = dashboardService.getLastTransaction(currentUser);
                    recentSales = dashboardService.getRecentSales(currentUser, 10);

                    // Format current date
                    currentDate = LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));

                    updateLastTransactionDisplay();
                    dataLoaded = true;
                }
            }
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error loading dashboard data: " + e.toString(), null, 0, "DashboardController.init()", null, e.toString());
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "No se pudieron cargar los datos del dashboard"));
        }
    }

    private void loadMetricsData() {
        try {
            if (sessionController != null && sessionController.isValid()) {
                Users currentUser = sessionController.getCurrentUser();
                if (currentUser != null) {
                    yesterdaySales = dashboardMetricsService.getYesterdaySales(currentUser);
                    weekSales = dashboardMetricsService.getWeekSales(currentUser);
                    monthSales = dashboardMetricsService.getMonthSales(currentUser);
                    avgTicketSize = dashboardMetricsService.getAverageTicket(currentUser, 30);
                    topProducts = dashboardMetricsService.getTopSellingProducts(currentUser, 5);
                    hourlyDistribution = dashboardMetricsService.getHourlySalesDistribution(currentUser, LocalDate.now());
                    weeklyBreakdown = dashboardMetricsService.getWeeklySalesBreakdown(currentUser);
                    kpi = dashboardMetricsService.getKPIs(currentUser);

                    buildHourlyChartConfig();
                    buildWeeklyChartConfig();

                    metricsLoaded = true;
                }
            }
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error loading dashboard metrics: " + e.toString(), null, 0, "DashboardController.loadMetricsData()", null, e.toString());
        }
    }

    // ── Chart config builders (following SeasonalityController pattern) ──

    private void buildHourlyChartConfig() {
        if (hourlyDistribution == null) return;

        List<String> labels = Arrays.asList(HOUR_LABELS);
        List<Number> salesValues = new ArrayList<>();
        List<Number> transactionValues = new ArrayList<>();

        for (DashboardMetricsService.HourlySales hs : hourlyDistribution) {
            salesValues.add(hs.getTotalSales());
            transactionValues.add(hs.getTransactions());
        }

        Map<String, Object> salesDataset = new LinkedHashMap<>();
        salesDataset.put("label", "Ventas (colones)");
        salesDataset.put("data", salesValues);
        salesDataset.put("backgroundColor", "rgba(54, 162, 235, 0.6)");
        salesDataset.put("borderColor", "rgb(54, 162, 235)");
        salesDataset.put("borderWidth", 1);

        Map<String, Object> txDataset = new LinkedHashMap<>();
        txDataset.put("label", "Transacciones");
        txDataset.put("data", transactionValues);
        txDataset.put("backgroundColor", "rgba(255, 99, 132, 0.4)");
        txDataset.put("borderColor", "rgb(255, 99, 132)");
        txDataset.put("borderWidth", 1);

        List<Map<String, Object>> datasets = new ArrayList<>();
        datasets.add(salesDataset);
        datasets.add(txDataset);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("labels", labels);
        data.put("datasets", datasets);

        Map<String, Object> options = buildChartOptions(true, "Ventas por Hora");

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("type", "bar");
        config.put("data", data);
        config.put("options", options);

        hourlyChartConfig = toJson(config);
    }

    private void buildWeeklyChartConfig() {
        if (weeklyBreakdown == null) return;

        List<String> labels = new ArrayList<>();
        List<Number> values = new ArrayList<>();

        for (DashboardMetricsService.DailySales ds : weeklyBreakdown) {
            labels.add(DAY_NAMES[ds.getDate().getDayOfWeek().getValue() - 1]);
            values.add(ds.getTotalSales());
        }

        Map<String, Object> dataset = new LinkedHashMap<>();
        dataset.put("label", "Ventas diarias");
        dataset.put("data", values);
        dataset.put("fill", false);
        dataset.put("borderColor", "rgb(75, 192, 192)");
        dataset.put("backgroundColor", "rgba(75, 192, 192, 0.2)");
        dataset.put("tension", 0.3);
        dataset.put("pointRadius", 4);
        dataset.put("pointBackgroundColor", "rgb(75, 192, 192)");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("labels", labels);
        data.put("datasets", Collections.singletonList(dataset));

        Map<String, Object> options = buildChartOptions(false, "Tendencia Semanal");

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("type", "line");
        config.put("data", data);
        config.put("options", options);

        weeklyChartConfig = toJson(config);
    }

    private Map<String, Object> buildChartOptions(boolean showLegend, String title) {
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

        Map<String, Object> titleObj = new LinkedHashMap<>();
        titleObj.put("display", false);
        titleObj.put("text", title);
        plugins.put("title", titleObj);
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

    @Nonnull
    public String startNewSale() {
        return "/secured/pages/Facturas/Facturas/factura.xhtml?faces-redirect=true";
    }

    private void updateLastTransactionDisplay() {
        if (lastTransaction != null) {
            try {
                String billNumber = lastTransaction.getEncabezado().getNumeroConsecutivo();
                BigDecimal total = lastTransaction.getResumen().getTotalComprobante();
                LocalDateTime time = lastTransaction.getEncabezado().getFechaEmision();
                String timeStr = time.format(DateTimeFormatter.ofPattern("HH:mm"));

                lastTransactionDisplay = String.format("Factura %s - %s colones a las %s",
                    billNumber, total, timeStr);
            } catch (RuntimeException e) {
                alertasService.registrarAlerta("Error", "Error formatting last transaction: " + e.toString(), null, 0, "DashboardController.updateLastTransactionDisplay()", null, e.toString());
                lastTransactionDisplay = "Error al cargar última transacción";
            }
        } else {
            lastTransactionDisplay = "No hay transacciones hoy";
        }
    }

    // ── Basic getters (original) ──

    @Nullable
    public String getCurrentDate() {
        if (!dataLoaded) {
            loadDashboardData();
        }
        return currentDate;
    }

    @Nullable
    public BigDecimal getTodaySales() {
        if (!dataLoaded) {
            loadDashboardData();
        }
        return todaySales;
    }

    public int getTransactionCount() {
        if (!dataLoaded) {
            loadDashboardData();
        }
        return transactionCount;
    }

    public int getItemsSold() {
        if (!dataLoaded) {
            loadDashboardData();
        }
        return itemsSold;
    }

    @Nullable
    public ComprobantesEmitidos getLastTransaction() {
        if (!dataLoaded) {
            loadDashboardData();
        }
        return lastTransaction;
    }

    @Nullable
    public String getLastTransactionDisplay() {
        if (!dataLoaded) {
            loadDashboardData();
        }
        return lastTransactionDisplay;
    }

    @Nullable
    public List<ComprobantesEmitidos> getRecentSales() {
        if (!dataLoaded) {
            loadDashboardData();
        }
        return recentSales;
    }

    // ── Metrics getters (lazy-loaded) ──

    @Nullable
    public BigDecimal getYesterdaySales() {
        ensureMetricsLoaded();
        return yesterdaySales;
    }

    @Nullable
    public BigDecimal getWeekSales() {
        ensureMetricsLoaded();
        return weekSales;
    }

    @Nullable
    public BigDecimal getMonthSales() {
        ensureMetricsLoaded();
        return monthSales;
    }

    @Nullable
    public BigDecimal getAvgTicketSize() {
        ensureMetricsLoaded();
        return avgTicketSize;
    }

    @Nullable
    public List<DashboardMetricsService.TopProduct> getTopProducts() {
        ensureMetricsLoaded();
        return topProducts;
    }

    @Nullable
    public List<DashboardMetricsService.HourlySales> getHourlyDistribution() {
        ensureMetricsLoaded();
        return hourlyDistribution;
    }

    @Nullable
    public List<DashboardMetricsService.DailySales> getWeeklyBreakdown() {
        ensureMetricsLoaded();
        return weeklyBreakdown;
    }

    @Nullable
    public DashboardMetricsService.DashboardKPI getKpi() {
        ensureMetricsLoaded();
        return kpi;
    }

    @Nullable
    public String getHourlyChartConfig() {
        ensureMetricsLoaded();
        return hourlyChartConfig;
    }

    @Nullable
    public String getWeeklyChartConfig() {
        ensureMetricsLoaded();
        return weeklyChartConfig;
    }

    // ── Formatted display helpers ──

    @Nullable
    public String getTodaySalesFormatted() {
        BigDecimal val = getTodaySales();
        return val != null ? formatColones(val) : "₡0";
    }

    @Nullable
    public String getYesterdaySalesFormatted() {
        BigDecimal val = getYesterdaySales();
        return val != null ? formatColones(val) : "₡0";
    }

    @Nullable
    public String getWeekSalesFormatted() {
        BigDecimal val = getWeekSales();
        return val != null ? formatColones(val) : "₡0";
    }

    @Nullable
    public String getMonthSalesFormatted() {
        BigDecimal val = getMonthSales();
        return val != null ? formatColones(val) : "₡0";
    }

    @Nullable
    public String getAvgTicketFormatted() {
        BigDecimal val = getAvgTicketSize();
        return val != null ? formatColones(val) : "₡0";
    }

    @Nullable
    public String getDailyGrowthDisplay() {
        DashboardMetricsService.DashboardKPI k = getKpi();
        if (k == null) return "0%";
        BigDecimal growth = k.getDailyGrowth();
        if (growth == null) return "0%";
        return (growth.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "") + growth.setScale(1).toPlainString() + "%";
    }

    @Nullable
    public String getDailyGrowthCssClass() {
        DashboardMetricsService.DashboardKPI k = getKpi();
        if (k == null) return "has-text-grey";
        BigDecimal growth = k.getDailyGrowth();
        if (growth == null) return "has-text-grey";
        return growth.compareTo(BigDecimal.ZERO) >= 0 ? "has-text-success" : "has-text-danger";
    }

    private String formatColones(BigDecimal value) {
        return "₡" + String.format("%,.0f", value);
    }

    private void ensureMetricsLoaded() {
        if (!metricsLoaded) {
            loadMetricsData();
        }
    }
}