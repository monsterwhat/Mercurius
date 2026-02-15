package Services;

import Models.ComprobantesV44.ComprobantesEmitidos;
import Models.ComprobantesV44.Encabezado.Encabezado;
import Models.ComprobantesV44.Resumen.ResumenFactura;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
@Named("salesTrendService")
public class SalesTrendService {

    @Inject
    EntityManager entityManager;

    @Transactional
    public List<TimeSeriesData> getDailySalesTimeSeries(Date startDate, Date endDate) {
        LocalDateTime start = toLocalDateTime(startDate);
        LocalDateTime end = toLocalDateTime(endDate);

        List<Object[]> results = entityManager.createQuery(
            "SELECT FUNCTION('DATE', e.fechaEmision), SUM(r.totalComprobante), COUNT(f), SUM(r.totalGravado), SUM(r.totalExento) " +
            "FROM ComprobantesEmitidos f " +
            "JOIN f.encabezado e " +
            "JOIN f.resumen r " +
            "WHERE f.status = true " +
            "AND e.fechaEmision BETWEEN :start AND :end " +
            "GROUP BY FUNCTION('DATE', e.fechaEmision) " +
            "ORDER BY FUNCTION('DATE', e.fechaEmision)",
            Object[].class
        )
        .setParameter("start", start)
        .setParameter("end", end)
        .getResultList();

        return results.stream()
            .map(row -> new TimeSeriesData(
                (LocalDate) row[0],
                (BigDecimal) row[1],
                ((Number) row[2]).intValue(),
                (BigDecimal) row[3],
                (BigDecimal) row[4]
            ))
            .collect(Collectors.toList());
    }

    @Transactional
    public List<TimeSeriesData> getWeeklySalesTimeSeries(Date startDate, Date endDate) {
        LocalDateTime start = toLocalDateTime(startDate);
        LocalDateTime end = toLocalDateTime(endDate);

        List<Object[]> results = entityManager.createQuery(
            "SELECT FUNCTION('YEAR', e.fechaEmision), FUNCTION('WEEK', e.fechaEmision), " +
            "SUM(r.totalComprobante), COUNT(f), SUM(r.totalGravado), SUM(r.totalExento) " +
            "FROM ComprobantesEmitidos f " +
            "JOIN f.encabezado e " +
            "JOIN f.resumen r " +
            "WHERE f.status = true " +
            "AND e.fechaEmision BETWEEN :start AND :end " +
            "GROUP BY FUNCTION('YEAR', e.fechaEmision), FUNCTION('WEEK', e.fechaEmision) " +
            "ORDER BY FUNCTION('YEAR', e.fechaEmision), FUNCTION('WEEK', e.fechaEmision)",
            Object[].class
        )
        .setParameter("start", start)
        .setParameter("end", end)
        .getResultList();

        return results.stream()
            .map(row -> new TimeSeriesData(
                (Integer) row[0], (Integer) row[1],
                (BigDecimal) row[2],
                ((Number) row[3]).intValue(),
                (BigDecimal) row[4],
                (BigDecimal) row[5]
            ))
            .collect(Collectors.toList());
    }

    @Transactional
    public List<TimeSeriesData> getMonthlySalesTimeSeries(Date startDate, Date endDate) {
        LocalDateTime start = toLocalDateTime(startDate);
        LocalDateTime end = toLocalDateTime(endDate);

        List<Object[]> results = entityManager.createQuery(
            "SELECT FUNCTION('YEAR', e.fechaEmision), FUNCTION('MONTH', e.fechaEmision), " +
            "SUM(r.totalComprobante), COUNT(f), SUM(r.totalGravado), SUM(r.totalExento) " +
            "FROM ComprobantesEmitidos f " +
            "JOIN f.encabezado e " +
            "JOIN f.resumen r " +
            "WHERE f.status = true " +
            "AND e.fechaEmision BETWEEN :start AND :end " +
            "GROUP BY FUNCTION('YEAR', e.fechaEmision), FUNCTION('MONTH', e.fechaEmision) " +
            "ORDER BY FUNCTION('YEAR', e.fechaEmision), FUNCTION('MONTH', e.fechaEmision)",
            Object[].class
        )
        .setParameter("start", start)
        .setParameter("end", end)
        .getResultList();

        return results.stream()
            .map(row -> new TimeSeriesData(
                (Integer) row[0], (Integer) row[1],
                (BigDecimal) row[2],
                ((Number) row[3]).intValue(),
                (BigDecimal) row[4],
                (BigDecimal) row[5]
            ))
            .collect(Collectors.toList());
    }

    @Transactional
    public SeasonalPattern getSeasonalPattern(int year) {
        LocalDateTime start = LocalDate.of(year, 1, 1).atStartOfDay();
        LocalDateTime end = LocalDate.of(year, 12, 31).atTime(23, 59, 59);

        List<Object[]> results = entityManager.createQuery(
            "SELECT FUNCTION('MONTH', e.fechaEmision), SUM(r.totalComprobante), COUNT(f) " +
            "FROM ComprobantesEmitidos f " +
            "JOIN f.encabezado e " +
            "JOIN f.resumen r " +
            "WHERE f.status = true " +
            "AND e.fechaEmision BETWEEN :start AND :end " +
            "GROUP BY FUNCTION('MONTH', e.fechaEmision) " +
            "ORDER BY FUNCTION('MONTH', e.fechaEmision)",
            Object[].class
        )
        .setParameter("start", start)
        .setParameter("end", end)
        .getResultList();

        Map<Integer, BigDecimal> monthlyRevenue = new LinkedHashMap<>();
        Map<Integer, Integer> monthlyTransactions = new LinkedHashMap<>();
        
        for (int i = 1; i <= 12; i++) {
            monthlyRevenue.put(i, BigDecimal.ZERO);
            monthlyTransactions.put(i, 0);
        }

        for (Object[] row : results) {
            Integer month = (Integer) row[0];
            monthlyRevenue.put(month, (BigDecimal) row[1]);
            monthlyTransactions.put(month, ((Number) row[2]).intValue());
        }

        BigDecimal[] yearlyTotals = {BigDecimal.ZERO};
        monthlyRevenue.values().forEach(v -> yearlyTotals[0] = yearlyTotals[0].add(v));

        Map<Integer, BigDecimal> seasonalIndex = new LinkedHashMap<>();
        BigDecimal averageMonthly = yearlyTotals[0].divide(BigDecimal.valueOf(12), 6, RoundingMode.HALF_UP);
        
        if (averageMonthly.compareTo(BigDecimal.ZERO) > 0) {
            monthlyRevenue.forEach((month, revenue) -> {
                BigDecimal index = revenue.divide(averageMonthly, 4, RoundingMode.HALF_UP);
                seasonalIndex.put(month, index);
            });
        } else {
            for (int i = 1; i <= 12; i++) {
                seasonalIndex.put(i, BigDecimal.ONE);
            }
        }

        return new SeasonalPattern(year, monthlyRevenue, monthlyTransactions, seasonalIndex);
    }

    @Transactional
    public YearOverYearComparison getYearOverYearComparison(int currentYear, int yearsBack) {
        List<YearData> yearlyData = new ArrayList<>();
        
        for (int i = 0; i <= yearsBack; i++) {
            int year = currentYear - i;
            LocalDateTime start = LocalDate.of(year, 1, 1).atStartOfDay();
            LocalDateTime end = LocalDate.of(year, 12, 31).atTime(23, 59, 59);

            Object[] result = entityManager.createQuery(
                "SELECT SUM(r.totalComprobante), COUNT(f), SUM(r.totalGravado), SUM(r.totalExento) " +
                "FROM ComprobantesEmitidos f " +
                "JOIN f.encabezado e " +
                "JOIN f.resumen r " +
                "WHERE f.status = true " +
                "AND e.fechaEmision BETWEEN :start AND :end",
                Object[].class
            )
            .setParameter("start", start)
            .setParameter("end", end)
            .getSingleResult();

            BigDecimal totalRevenue = result != null && result[0] != null ? (BigDecimal) result[0] : BigDecimal.ZERO;
            Integer transactions = result != null && result[1] != null ? ((Number) result[1]).intValue() : 0;
            BigDecimal avgTicket = transactions > 0 ? totalRevenue.divide(BigDecimal.valueOf(transactions), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;

            yearlyData.add(new YearData(year, totalRevenue, transactions, avgTicket));
        }

        Collections.reverse(yearlyData);

        List<YearOverYearGrowth> growthRates = new ArrayList<>();
        for (int i = 1; i < yearlyData.size(); i++) {
            YearData current = yearlyData.get(i);
            YearData previous = yearlyData.get(i - 1);
            
            BigDecimal revenueGrowth = calculateGrowthPercentage(previous.getTotalRevenue(), current.getTotalRevenue());
            BigDecimal transactionGrowth = calculateGrowthPercentage(
                BigDecimal.valueOf(previous.getTransactions()), 
                BigDecimal.valueOf(current.getTransactions())
            );
            
            growthRates.add(new YearOverYearGrowth(previous.getYear(), current.getYear(), revenueGrowth, transactionGrowth));
        }

        return new YearOverYearComparison(yearlyData, growthRates);
    }

    @Transactional
    public TrendIndicators getTrendIndicators(Date startDate, Date endDate) {
        LocalDateTime start = toLocalDateTime(startDate);
        LocalDateTime end = toLocalDateTime(endDate);

        long days = Duration.between(start, end).toDays();
        long halfDays = days / 2;
        
        LocalDateTime midPoint = start.plusDays(halfDays);

        BigDecimal firstHalfRevenue = entityManager.createQuery(
            "SELECT COALESCE(SUM(r.totalComprobante), 0) " +
            "FROM ComprobantesEmitidos f " +
            "JOIN f.encabezado e " +
            "JOIN f.resumen r " +
            "WHERE f.status = true " +
            "AND e.fechaEmision BETWEEN :start AND :midPoint",
            BigDecimal.class
        )
        .setParameter("start", start)
        .setParameter("midPoint", midPoint)
        .getSingleResult();

        BigDecimal secondHalfRevenue = entityManager.createQuery(
            "SELECT COALESCE(SUM(r.totalComprobante), 0) " +
            "FROM ComprobantesEmitidos f " +
            "JOIN f.encabezado e " +
            "JOIN f.resumen r " +
            "WHERE f.status = true " +
            "AND e.fechaEmision BETWEEN :midPoint AND :end",
            BigDecimal.class
        )
        .setParameter("midPoint", midPoint)
        .setParameter("end", end)
        .getSingleResult();

        int firstHalfTransactions = entityManager.createQuery(
            "SELECT COUNT(f) " +
            "FROM ComprobantesEmitidos f " +
            "JOIN f.encabezado e " +
            "WHERE f.status = true " +
            "AND e.fechaEmision BETWEEN :start AND :midPoint",
            Integer.class
        )
        .setParameter("start", start)
        .setParameter("midPoint", midPoint)
        .getSingleResult();

        int secondHalfTransactions = entityManager.createQuery(
            "SELECT COUNT(f) " +
            "FROM ComprobantesEmitidos f " +
            "JOIN f.encabezado e " +
            "WHERE f.status = true " +
            "AND e.fechaEmision BETWEEN :midPoint AND :end",
            Integer.class
        )
        .setParameter("midPoint", midPoint)
        .setParameter("end", end)
        .getSingleResult();

        BigDecimal revenueGrowth = calculateGrowthPercentage(firstHalfRevenue, secondHalfRevenue);
        BigDecimal transactionGrowth = calculateGrowthPercentage(
            BigDecimal.valueOf(firstHalfTransactions), 
            BigDecimal.valueOf(secondHalfTransactions)
        );

        String trendDirection;
        if (revenueGrowth.compareTo(BigDecimal.valueOf(5)) > 0) {
            trendDirection = "UP";
        } else if (revenueGrowth.compareTo(BigDecimal.valueOf(-5)) < 0) {
            trendDirection = "DOWN";
        } else {
            trendDirection = "STABLE";
        }

        return new TrendIndicators(trendDirection, revenueGrowth, transactionGrowth, 
            firstHalfRevenue, secondHalfRevenue);
    }

    @Transactional
    public List<HourlyHeatmap> getHourlyHeatmap(Date startDate, Date endDate) {
        LocalDateTime start = toLocalDateTime(startDate);
        LocalDateTime end = toLocalDateTime(endDate);

        List<Object[]> results = entityManager.createQuery(
            "SELECT FUNCTION('HOUR', e.fechaEmision), FUNCTION('DAYOFWEEK', e.fechaEmision), " +
            "SUM(r.totalComprobante), COUNT(f) " +
            "FROM ComprobantesEmitidos f " +
            "JOIN f.encabezado e " +
            "JOIN f.resumen r " +
            "WHERE f.status = true " +
            "AND e.fechaEmision BETWEEN :start AND :end " +
            "GROUP BY FUNCTION('HOUR', e.fechaEmision), FUNCTION('DAYOFWEEK', e.fechaEmision) " +
            "ORDER BY FUNCTION('HOUR', e.fechaEmision), FUNCTION('DAYOFWEEK', e.fechaEmision)",
            Object[].class
        )
        .setParameter("start", start)
        .setParameter("end", end)
        .getResultList();

        return results.stream()
            .map(row -> new HourlyHeatmap(
                ((Number) row[0]).intValue(),
                ((Number) row[1]).intValue(),
                (BigDecimal) row[2],
                ((Number) row[3]).intValue()
            ))
            .collect(Collectors.toList());
    }

    @Transactional
    public GrowthMetrics getGrowthMetrics(Date startDate, Date endDate) {
        LocalDateTime start = toLocalDateTime(startDate);
        LocalDateTime end = toLocalDateTime(endDate);

        BigDecimal totalRevenue = entityManager.createQuery(
            "SELECT COALESCE(SUM(r.totalComprobante), 0) " +
            "FROM ComprobantesEmitidos f " +
            "JOIN f.encabezado e " +
            "JOIN f.resumen r " +
            "WHERE f.status = true " +
            "AND e.fechaEmision BETWEEN :start AND :end",
            BigDecimal.class
        )
        .setParameter("start", start)
        .setParameter("end", end)
        .getSingleResult();

        Long totalTransactions = entityManager.createQuery(
            "SELECT COUNT(f) " +
            "FROM ComprobantesEmitidos f " +
            "JOIN f.encabezado e " +
            "WHERE f.status = true " +
            "AND e.fechaEmision BETWEEN :start AND :end",
            Long.class
        )
        .setParameter("start", start)
        .setParameter("end", end)
        .getSingleResult();

        BigDecimal averageTicket = totalTransactions != null && totalTransactions > 0
            ? totalRevenue.divide(BigDecimal.valueOf(totalTransactions), 2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;

        long days = Duration.between(start, end).toDays();
        BigDecimal dailyAverage = days > 0 
            ? totalRevenue.divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;

        BigDecimal weeklyAverage = dailyAverage.multiply(BigDecimal.valueOf(7));
        BigDecimal monthlyAverage = dailyAverage.multiply(BigDecimal.valueOf(30));

        return new GrowthMetrics(
            totalRevenue != null ? totalRevenue : BigDecimal.ZERO,
            totalTransactions != null ? totalTransactions.intValue() : 0,
            averageTicket,
            dailyAverage,
            weeklyAverage,
            monthlyAverage
        );
    }

    private LocalDateTime toLocalDateTime(Date date) {
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
    }

    private BigDecimal calculateGrowthPercentage(BigDecimal oldValue, BigDecimal newValue) {
        if (oldValue == null || newValue == null) return BigDecimal.ZERO;
        if (oldValue.compareTo(BigDecimal.ZERO) == 0) {
            return newValue.compareTo(BigDecimal.ZERO) > 0 ? BigDecimal.valueOf(100) : BigDecimal.ZERO;
        }
        return newValue.subtract(oldValue)
            .multiply(BigDecimal.valueOf(100))
            .divide(oldValue, 2, RoundingMode.HALF_UP);
    }

    public static class TimeSeriesData {
        private LocalDate date;
        private Integer year;
        private Integer weekOrMonth;
        private BigDecimal totalSales;
        private int transactionCount;
        private BigDecimal taxableAmount;
        private BigDecimal exemptAmount;

        public TimeSeriesData(LocalDate date, BigDecimal totalSales, int transactionCount, 
                           BigDecimal taxableAmount, BigDecimal exemptAmount) {
            this.date = date;
            this.totalSales = totalSales;
            this.transactionCount = transactionCount;
            this.taxableAmount = taxableAmount;
            this.exemptAmount = exemptAmount;
        }

        public TimeSeriesData(Integer year, Integer weekOrMonth, BigDecimal totalSales, 
                           int transactionCount, BigDecimal taxableAmount, BigDecimal exemptAmount) {
            this.year = year;
            this.weekOrMonth = weekOrMonth;
            this.totalSales = totalSales;
            this.transactionCount = transactionCount;
            this.taxableAmount = taxableAmount;
            this.exemptAmount = exemptAmount;
        }

        public LocalDate getDate() { return date; }
        public Integer getYear() { return year; }
        public Integer getWeekOrMonth() { return weekOrMonth; }
        public BigDecimal getTotalSales() { return totalSales; }
        public int getTransactionCount() { return transactionCount; }
        public BigDecimal getTaxableAmount() { return taxableAmount; }
        public BigDecimal getExemptAmount() { return exemptAmount; }
    }

    public static class SeasonalPattern {
        private int year;
        private Map<Integer, BigDecimal> monthlyRevenue;
        private Map<Integer, Integer> monthlyTransactions;
        private Map<Integer, BigDecimal> seasonalIndex;

        public SeasonalPattern(int year, Map<Integer, BigDecimal> monthlyRevenue,
                             Map<Integer, Integer> monthlyTransactions,
                             Map<Integer, BigDecimal> seasonalIndex) {
            this.year = year;
            this.monthlyRevenue = monthlyRevenue;
            this.monthlyTransactions = monthlyTransactions;
            this.seasonalIndex = seasonalIndex;
        }

        public int getYear() { return year; }
        public Map<Integer, BigDecimal> getMonthlyRevenue() { return monthlyRevenue; }
        public Map<Integer, Integer> getMonthlyTransactions() { return monthlyTransactions; }
        public Map<Integer, BigDecimal> getSeasonalIndex() { return seasonalIndex; }
    }

    public static class YearData {
        private int year;
        private BigDecimal totalRevenue;
        private int transactions;
        private BigDecimal averageTicket;

        public YearData(int year, BigDecimal totalRevenue, int transactions, BigDecimal averageTicket) {
            this.year = year;
            this.totalRevenue = totalRevenue;
            this.transactions = transactions;
            this.averageTicket = averageTicket;
        }

        public int getYear() { return year; }
        public BigDecimal getTotalRevenue() { return totalRevenue; }
        public int getTransactions() { return transactions; }
        public BigDecimal getAverageTicket() { return averageTicket; }
    }

    public static class YearOverYearGrowth {
        private int fromYear;
        private int toYear;
        private BigDecimal revenueGrowth;
        private BigDecimal transactionGrowth;

        public YearOverYearGrowth(int fromYear, int toYear, BigDecimal revenueGrowth, BigDecimal transactionGrowth) {
            this.fromYear = fromYear;
            this.toYear = toYear;
            this.revenueGrowth = revenueGrowth;
            this.transactionGrowth = transactionGrowth;
        }

        public int getFromYear() { return fromYear; }
        public int getToYear() { return toYear; }
        public BigDecimal getRevenueGrowth() { return revenueGrowth; }
        public BigDecimal getTransactionGrowth() { return transactionGrowth; }
    }

    public static class YearOverYearComparison {
        private List<YearData> yearlyData;
        private List<YearOverYearGrowth> growthRates;

        public YearOverYearComparison(List<YearData> yearlyData, List<YearOverYearGrowth> growthRates) {
            this.yearlyData = yearlyData;
            this.growthRates = growthRates;
        }

        public List<YearData> getYearlyData() { return yearlyData; }
        public List<YearOverYearGrowth> getGrowthRates() { return growthRates; }
    }

    public static class TrendIndicators {
        private String trendDirection;
        private BigDecimal revenueGrowth;
        private BigDecimal transactionGrowth;
        private BigDecimal firstPeriodRevenue;
        private BigDecimal secondPeriodRevenue;

        public TrendIndicators(String trendDirection, BigDecimal revenueGrowth, BigDecimal transactionGrowth,
                             BigDecimal firstPeriodRevenue, BigDecimal secondPeriodRevenue) {
            this.trendDirection = trendDirection;
            this.revenueGrowth = revenueGrowth;
            this.transactionGrowth = transactionGrowth;
            this.firstPeriodRevenue = firstPeriodRevenue;
            this.secondPeriodRevenue = secondPeriodRevenue;
        }

        public String getTrendDirection() { return trendDirection; }
        public BigDecimal getRevenueGrowth() { return revenueGrowth; }
        public BigDecimal getTransactionGrowth() { return transactionGrowth; }
        public BigDecimal getFirstPeriodRevenue() { return firstPeriodRevenue; }
        public BigDecimal getSecondPeriodRevenue() { return secondPeriodRevenue; }
    }

    public static class HourlyHeatmap {
        private int hour;
        private int dayOfWeek;
        private BigDecimal totalSales;
        private int transactionCount;

        public HourlyHeatmap(int hour, int dayOfWeek, BigDecimal totalSales, int transactionCount) {
            this.hour = hour;
            this.dayOfWeek = dayOfWeek;
            this.totalSales = totalSales;
            this.transactionCount = transactionCount;
        }

        public int getHour() { return hour; }
        public int getDayOfWeek() { return dayOfWeek; }
        public BigDecimal getTotalSales() { return totalSales; }
        public int getTransactionCount() { return transactionCount; }
    }

    public static class GrowthMetrics {
        private BigDecimal totalRevenue;
        private int totalTransactions;
        private BigDecimal averageTicket;
        private BigDecimal dailyAverage;
        private BigDecimal weeklyAverage;
        private BigDecimal monthlyAverage;

        public GrowthMetrics(BigDecimal totalRevenue, int totalTransactions, BigDecimal averageTicket,
                          BigDecimal dailyAverage, BigDecimal weeklyAverage, BigDecimal monthlyAverage) {
            this.totalRevenue = totalRevenue;
            this.totalTransactions = totalTransactions;
            this.averageTicket = averageTicket;
            this.dailyAverage = dailyAverage;
            this.weeklyAverage = weeklyAverage;
            this.monthlyAverage = monthlyAverage;
        }

        public BigDecimal getTotalRevenue() { return totalRevenue; }
        public int getTotalTransactions() { return totalTransactions; }
        public BigDecimal getAverageTicket() { return averageTicket; }
        public BigDecimal getDailyAverage() { return dailyAverage; }
        public BigDecimal getWeeklyAverage() { return weeklyAverage; }
        public BigDecimal getMonthlyAverage() { return monthlyAverage; }
    }
}
