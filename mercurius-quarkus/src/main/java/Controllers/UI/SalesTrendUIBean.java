package Controllers.UI;

import jakarta.faces.view.ViewScoped;
import jakarta.inject.Named;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter @Setter @ToString @EqualsAndHashCode
@Named(value = "salesTrendUIBean")
@ViewScoped
public class SalesTrendUIBean implements Serializable {

    @Nonnull
    private Date startDate;
    @Nonnull
    private Date endDate;
    @Nonnull
    private List<TimeSeriesData> dailySales;
    @Nonnull
    private List<TimeSeriesData> weeklySales;
    @Nonnull
    private List<TimeSeriesData> monthlySales;
    @Nonnull
    private GrowthMetrics metrics;

    public SalesTrendUIBean() {
        endDate = new Date();
        long ninetyDays = 90L * 24 * 60 * 60 * 1000;
        startDate = new Date(System.currentTimeMillis() - ninetyDays);
        dailySales = new ArrayList<>();
        weeklySales = new ArrayList<>();
        monthlySales = new ArrayList<>();
        metrics = new GrowthMetrics();
    }

    public void loadData() {
        // Placeholder - actual data would come from REST API calls
        // This creates sample data for the UI to display
        if (dailySales.isEmpty()) {
            dailySales.add(new TimeSeriesData("01/01/2026", new BigDecimal("150000"), 25));
            dailySales.add(new TimeSeriesData("02/01/2026", new BigDecimal("180000"), 30));
            dailySales.add(new TimeSeriesData("03/01/2026", new BigDecimal("165000"), 28));
            dailySales.add(new TimeSeriesData("04/01/2026", new BigDecimal("200000"), 35));
            dailySales.add(new TimeSeriesData("05/01/2026", new BigDecimal("175000"), 32));
        }
        
        if (weeklySales.isEmpty()) {
            weeklySales.add(new TimeSeriesData("Semana 1", new BigDecimal("1050000"), 180));
            weeklySales.add(new TimeSeriesData("Semana 2", new BigDecimal("1200000"), 200));
            weeklySales.add(new TimeSeriesData("Semana 3", new BigDecimal("980000"), 165));
            weeklySales.add(new TimeSeriesData("Semana 4", new BigDecimal("1150000"), 190));
        }
        
        if (monthlySales.isEmpty()) {
            monthlySales.add(new TimeSeriesData("Enero 2026", new BigDecimal("4500000"), 735));
            monthlySales.add(new TimeSeriesData("Diciembre 2025", new BigDecimal("4200000"), 680));
            monthlySales.add(new TimeSeriesData("Noviembre 2025", new BigDecimal("3900000"), 620));
        }
        
        metrics = new GrowthMetrics();
        metrics.growthRate = "7.14%";
        metrics.avgDailySales = "₡175,000";
        metrics.maxDailySales = "₡200,000";
        metrics.minDailySales = "₡150,000";
    }

    @Getter @Setter @ToString @EqualsAndHashCode
    public static class TimeSeriesData {
        @Nonnull
        public String date;
        @Nonnull
        public BigDecimal value;
        public int count;

        public TimeSeriesData(@Nonnull String date, @Nonnull BigDecimal value, int count) {
            this.date = date;
            this.value = value;
            this.count = count;
        }
    }

    @Getter @Setter @ToString @EqualsAndHashCode
    public static class GrowthMetrics {
        @Nullable
        public String growthRate;
        @Nullable
        public String avgDailySales;
        @Nullable
        public String maxDailySales;
        @Nullable
        public String minDailySales;
    }
}
