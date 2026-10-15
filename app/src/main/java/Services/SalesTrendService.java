package Services;

import Models.ComprobantesEmitidos;
import Models.Encabezado.Encabezado;
import Models.Resumen.ResumenFactura;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import io.quarkus.cache.CacheResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.temporal.IsoFields;
import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
@Named("salesTrendService")
public class SalesTrendService {

    @Inject @Nonnull
    EntityManager entityManager;

@CacheResult(cacheName = "analytics-getDailySalesTimeSeries")
    @Transactional(value = Transactional.TxType.SUPPORTS)
    public @Nonnull List<TimeSeriesData> getDailySalesTimeSeries(@Nonnull Date startDate, @Nonnull Date endDate) {
        LocalDateTime start = toLocalDateTime(startDate);
        LocalDateTime end = toLocalDateTime(endDate);

        List<Object[]> results = entityManager.createQuery(
            "SELECT CAST(e.fechaEmision AS date), SUM(r.totalComprobante), COUNT(f), SUM(r.totalGravado), SUM(r.totalExento) " +
            "FROM ComprobantesEmitidos f " +
            "JOIN f.encabezado e " +
            "JOIN f.resumen r " +
            "WHERE f.status = true " +
            "AND e.fechaEmision BETWEEN :start AND :end " +
            "GROUP BY CAST(e.fechaEmision AS date) " +
            "ORDER BY CAST(e.fechaEmision AS date)",
            Object[].class
        )
        .setParameter("start", start)
        .setParameter("end", end)
        .getResultList();

        return results.stream()
            .map(row -> new TimeSeriesData(
                asLocalDate(row[0]),
                (BigDecimal) row[1],
                ((Number) row[2]).intValue(),
                (BigDecimal) row[3],
                (BigDecimal) row[4]
            ))
            .collect(Collectors.toList());
    }

@CacheResult(cacheName = "analytics-getWeeklySalesTimeSeries")
    @Transactional(value = Transactional.TxType.SUPPORTS)
    public @Nonnull List<TimeSeriesData> getWeeklySalesTimeSeries(@Nonnull Date startDate, @Nonnull Date endDate) {
        LocalDateTime start = toLocalDateTime(startDate);
        LocalDateTime end = toLocalDateTime(endDate);

        List<Object[]> results = entityManager.createQuery(
            "SELECT CAST(e.fechaEmision AS date), SUM(r.totalComprobante), COUNT(f), " +
            "SUM(r.totalGravado), SUM(r.totalExento) " +
            "FROM ComprobantesEmitidos f " +
            "JOIN f.encabezado e " +
            "JOIN f.resumen r " +
            "WHERE f.status = true " +
            "AND e.fechaEmision BETWEEN :start AND :end " +
            "GROUP BY CAST(e.fechaEmision AS date) " +
            "ORDER BY CAST(e.fechaEmision AS date)",
            Object[].class
        )
        .setParameter("start", start)
        .setParameter("end", end)
        .getResultList();

        Map<String, TimeSeriesData> weeklyMap = new LinkedHashMap<>();

        for (Object[] row : results) {
            LocalDate date = asLocalDate(row[0]);
            BigDecimal totalSales = (BigDecimal) row[1];
            int transactionCount = ((Number) row[2]).intValue();
            BigDecimal taxableAmount = (BigDecimal) row[3];
            BigDecimal exemptAmount = (BigDecimal) row[4];

            int weekYear = date.get(IsoFields.WEEK_BASED_YEAR);
            int weekOfYear = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);

            String key = weekYear + "-" + weekOfYear;
            TimeSeriesData existing = weeklyMap.get(key);
            if (existing != null) {
                weeklyMap.put(key, new TimeSeriesData(
                    weekYear, weekOfYear,
                    existing.getTotalSales().add(totalSales),
                    existing.getTransactionCount() + transactionCount,
                    existing.getTaxableAmount().add(taxableAmount),
                    existing.getExemptAmount().add(exemptAmount)
                ));
            } else {
                weeklyMap.put(key, new TimeSeriesData(
                    weekYear, weekOfYear,
                    totalSales, transactionCount, taxableAmount, exemptAmount
                ));
            }
        }

        return new ArrayList<>(weeklyMap.values());
    }

@CacheResult(cacheName = "analytics-getMonthlySalesTimeSeries")
    @Transactional(value = Transactional.TxType.SUPPORTS)
    public List<TimeSeriesData> getMonthlySalesTimeSeries(Date startDate, Date endDate) {
        LocalDateTime start = toLocalDateTime(startDate);
        LocalDateTime end = toLocalDateTime(endDate);

        List<Object[]> results = entityManager.createQuery(
            "SELECT YEAR(e.fechaEmision), MONTH(e.fechaEmision), " +
            "SUM(r.totalComprobante), COUNT(f), SUM(r.totalGravado), SUM(r.totalExento) " +
            "FROM ComprobantesEmitidos f " +
            "JOIN f.encabezado e " +
            "JOIN f.resumen r " +
            "WHERE f.status = true " +
            "AND e.fechaEmision BETWEEN :start AND :end " +
            "GROUP BY YEAR(e.fechaEmision), MONTH(e.fechaEmision) " +
            "ORDER BY YEAR(e.fechaEmision), MONTH(e.fechaEmision)",
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

@CacheResult(cacheName = "analytics-getSeasonalPattern")
    @Transactional(value = Transactional.TxType.SUPPORTS)
    public SeasonalPattern getSeasonalPattern(int year) {
        LocalDateTime start = LocalDate.of(year, 1, 1).atStartOfDay();
        LocalDateTime end = LocalDate.of(year, 12, 31).atTime(23, 59, 59);

        List<Object[]> results = entityManager.createQuery(
            "SELECT MONTH(e.fechaEmision), SUM(r.totalComprobante), COUNT(f) " +
            "FROM ComprobantesEmitidos f " +
            "JOIN f.encabezado e " +
            "JOIN f.resumen r " +
            "WHERE f.status = true " +
            "AND e.fechaEmision BETWEEN :start AND :end " +
            "GROUP BY MONTH(e.fechaEmision) " +
            "ORDER BY MONTH(e.fechaEmision)",
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

@CacheResult(cacheName = "analytics-getYearOverYearComparison")
    @Transactional(value = Transactional.TxType.SUPPORTS)
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

@CacheResult(cacheName = "analytics-getTrendIndicators")
    @Transactional(value = Transactional.TxType.SUPPORTS)
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

        long firstHalfTransactions = entityManager.createQuery(
            "SELECT COUNT(f) " +
            "FROM ComprobantesEmitidos f " +
            "JOIN f.encabezado e " +
            "WHERE f.status = true " +
            "AND e.fechaEmision BETWEEN :start AND :midPoint",
            Long.class
        )
        .setParameter("start", start)
        .setParameter("midPoint", midPoint)
        .getSingleResult();

        long secondHalfTransactions = entityManager.createQuery(
            "SELECT COUNT(f) " +
            "FROM ComprobantesEmitidos f " +
            "JOIN f.encabezado e " +
            "WHERE f.status = true " +
            "AND e.fechaEmision BETWEEN :midPoint AND :end",
            Long.class
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

@CacheResult(cacheName = "analytics-getHourlyHeatmap")
    @Transactional(value = Transactional.TxType.SUPPORTS)
    public List<HourlyHeatmap> getHourlyHeatmap(Date startDate, Date endDate) {
        LocalDateTime start = toLocalDateTime(startDate);
        LocalDateTime end = toLocalDateTime(endDate);

        List<Object[]> results = entityManager.createQuery(
            "SELECT HOUR(e.fechaEmision), CAST(e.fechaEmision AS date), " +
            "SUM(r.totalComprobante), COUNT(f) " +
            "FROM ComprobantesEmitidos f " +
            "JOIN f.encabezado e " +
            "JOIN f.resumen r " +
            "WHERE f.status = true " +
            "AND e.fechaEmision BETWEEN :start AND :end " +
            "GROUP BY HOUR(e.fechaEmision), CAST(e.fechaEmision AS date) " +
            "ORDER BY HOUR(e.fechaEmision), CAST(e.fechaEmision AS date)",
            Object[].class
        )
        .setParameter("start", start)
        .setParameter("end", end)
        .getResultList();

        // Aggregate by (hour, dayOfWeek) since multiple dates may share the same hour + day-of-week
        Map<String, HourlyHeatmap> aggregated = new LinkedHashMap<>();

        for (Object[] row : results) {
            int hour = ((Number) row[0]).intValue();
            LocalDate date = asLocalDate(row[1]);
            BigDecimal totalSales = (BigDecimal) row[2];
            int transactionCount = ((Number) row[3]).intValue();

            // Java DayOfWeek: MONDAY=1 .. SUNDAY=7
            // MySQL DAYOFWEEK: 1=Sunday .. 7=Saturday
            int javaDayOfWeek = date.getDayOfWeek().getValue();
            int mysqlDayOfWeek = (javaDayOfWeek % 7) + 1;

            String key = hour + "-" + mysqlDayOfWeek;

            aggregated.merge(key, new HourlyHeatmap(hour, mysqlDayOfWeek, totalSales, transactionCount),
                (existing, incoming) -> new HourlyHeatmap(
                    existing.getHour(),
                    existing.getDayOfWeek(),
                    existing.getTotalSales().add(incoming.getTotalSales()),
                    existing.getTransactionCount() + incoming.getTransactionCount()
                ));
        }

        List<HourlyHeatmap> result = new ArrayList<>(aggregated.values());
        result.sort(Comparator.comparingInt(HourlyHeatmap::getHour)
            .thenComparingInt(HourlyHeatmap::getDayOfWeek));
        return result;
    }

@CacheResult(cacheName = "analytics-getGrowthMetrics")
    @Transactional(value = Transactional.TxType.SUPPORTS)
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

    @Transactional(value = Transactional.TxType.SUPPORTS)
    public @Nonnull SameDateHistory getSameDateSalesHistory(int month, int day, int yearsBack,
                                                          @Nullable String dimension,
                                                          @Nullable List<String> selectedItems) {
        String resolvedDimension = (dimension == null || dimension.isBlank()) ? "productos" : dimension.trim().toLowerCase(Locale.ROOT);
        if (month < 1 || month > 12 || day < 1 || day > 31) {
            throw new IllegalArgumentException("Mes o dia invalido");
        }
        if (yearsBack < 1 || yearsBack > 50) {
            throw new IllegalArgumentException("yearsBack debe estar entre 1 y 50");
        }
        // Validate that the month/day exists on at least a non-leap year key.
        LocalDate.of(2000, month, day);

        int currentYear = LocalDate.now().getYear();
        int startYear = currentYear - yearsBack + 1;
        int endYear = currentYear;

        List<Integer> years = new ArrayList<>();
        for (int year = startYear; year <= endYear; year++) {
            years.add(year);
        }

        Map<Integer, BigDecimal> totalsByYear = new LinkedHashMap<>();
        Map<Integer, Integer> transactionsByYear = new LinkedHashMap<>();
        for (int year : years) {
            totalsByYear.put(year, BigDecimal.ZERO);
            transactionsByYear.put(year, 0);
        }

        for (int year : years) {
            LocalDateTime start = LocalDate.of(year, month, day).atStartOfDay();
            LocalDateTime end = LocalDate.of(year, month, day).atTime(23, 59, 59);
            Object[] row = entityManager.createQuery(
                "SELECT COALESCE(SUM(r.totalComprobante), 0), COUNT(f) " +
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
            BigDecimal total = row != null && row[0] != null ? (BigDecimal) row[0] : BigDecimal.ZERO;
            int transactions = row != null && row[1] != null ? ((Number) row[1]).intValue() : 0;
            totalsByYear.put(year, total);
            transactionsByYear.put(year, transactions);
        }

        List<String> selectedKeys = new ArrayList<>();
        if (selectedItems != null) {
            for (String raw : selectedItems) {
                if (raw == null) continue;
                String name = raw.trim();
                if (!name.isEmpty() && !selectedKeys.contains(name)) selectedKeys.add(name);
            }
        }
        boolean hasSelection = !selectedKeys.isEmpty();
        Set<String> selectedKeySet = new LinkedHashSet<>();
        for (String key : selectedKeys) selectedKeySet.add(normalizeProductName(key));

        String joinClause = "";
        String groupExpression = "ld.detalle";
        if ("familias".equals(resolvedDimension)) {
            joinClause = "LEFT JOIN Articulos a ON a.nombre = ld.detalle";
            groupExpression = "COALESCE(a.familia.nombre, 'Sin familia')";
        } else if ("departamentos".equals(resolvedDimension)) {
            joinClause = "LEFT JOIN Articulos a ON a.nombre = ld.detalle";
            groupExpression = "COALESCE(a.departamento.nombre, 'Sin departamento')";
        } else {
            resolvedDimension = "productos";
        }

        Map<String, Map<Integer, BigDecimal>> groupedByYear = new LinkedHashMap<>();
        Map<String, BigDecimal> groupTotals = new LinkedHashMap<>();
        if (hasSelection) {
            for (String key : selectedKeys) {
                Map<Integer, BigDecimal> zeros = new LinkedHashMap<>();
                for (int y : years) zeros.put(y, BigDecimal.ZERO);
                groupedByYear.put(key, zeros);
            }
        }

        for (int year : years) {
            LocalDateTime start = LocalDate.of(year, month, day).atStartOfDay();
            LocalDateTime end = LocalDate.of(year, month, day).atTime(23, 59, 59);
            @SuppressWarnings("unchecked")
            List<Object[]> rows = entityManager.createQuery(
                "SELECT " + groupExpression + ", COALESCE(SUM(ld.montoTotalLinea), 0) " +
                "FROM ComprobantesEmitidos f " +
                "JOIN f.detalles d " +
                "JOIN d.lineasDetalle ld " +
                "JOIN f.encabezado e " +
                joinClause + " " +
                "WHERE f.status = true " +
                "AND e.fechaEmision BETWEEN :start AND :end " +
                "GROUP BY " + groupExpression
            )
            .setParameter("start", start)
            .setParameter("end", end)
            .getResultList();

            for (Object[] row : rows) {
                String key = row[0] != null ? String.valueOf(row[0]) : "Sin detalle";
                BigDecimal amount = row[1] != null ? (BigDecimal) row[1] : BigDecimal.ZERO;
                if (hasSelection && !selectedKeySet.contains(normalizeProductName(key))) {
                    continue;
                }
                if (!hasSelection) {
                    groupedByYear.computeIfAbsent(key, k -> {
                        Map<Integer, BigDecimal> m = new LinkedHashMap<>();
                        for (int y : years) m.put(y, BigDecimal.ZERO);
                        return m;
                    });
                } else if (!groupedByYear.containsKey(key)) {
                    groupedByYear.computeIfAbsent(key, k -> {
                        Map<Integer, BigDecimal> m = new LinkedHashMap<>();
                        for (int y : years) m.put(y, BigDecimal.ZERO);
                        return m;
                    });
                }
                if (groupedByYear.containsKey(key)) {
                    groupedByYear.get(key).put(year, amount);
                }
                if (!hasSelection) {
                    groupTotals.merge(key, amount, BigDecimal::add);
                }
            }
        }

        List<String> groupsToChart = new ArrayList<>();
        if (hasSelection) {
            for (String key : selectedKeys) {
                String match = findMatchingProduct(groupedByYear.keySet(), key);
                if (match != null && !containsName(groupsToChart, match)) groupsToChart.add(match);
            }
            for (String key : groupedByYear.keySet()) {
                if (!containsName(groupsToChart, key) && selectedKeySet.contains(normalizeProductName(key))) {
                    groupsToChart.add(key);
                }
            }
            if (groupsToChart.isEmpty()) {
                groupsToChart.addAll(selectedKeys);
                for (String key : selectedKeys) {
                    groupedByYear.computeIfAbsent(key, k -> {
                        Map<Integer, BigDecimal> m = new LinkedHashMap<>();
                        for (int y : years) m.put(y, BigDecimal.ZERO);
                        return m;
                    });
                }
            }
        } else {
            groupsToChart = groupTotals.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .limit(8)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        }

        List<ProductYearSeries> productSeries = new ArrayList<>();
        for (String group : groupsToChart) {
            Map<Integer, BigDecimal> yearly = groupedByYear.computeIfAbsent(group, k -> {
                Map<Integer, BigDecimal> m = new LinkedHashMap<>();
                for (int y : years) m.put(y, BigDecimal.ZERO);
                return m;
            });
            List<BigDecimal> values = new ArrayList<>();
            for (int year : years) {
                values.add(yearly.getOrDefault(year, BigDecimal.ZERO));
            }
            productSeries.add(new ProductYearSeries(group, values));
        }
        List<BigDecimal> totalsList = new ArrayList<>();
        List<Integer> transactionsList = new ArrayList<>();
        for (int year : years) {
            totalsList.add(totalsByYear.getOrDefault(year, BigDecimal.ZERO));
            transactionsList.add(transactionsByYear.getOrDefault(year, 0));
        }

        return new SameDateHistory(month, day, years, totalsList, transactionsList, productSeries);
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

    public static class SameDateHistory {
        private final int month;
        private final int day;
        private final List<Integer> years;
        private final List<BigDecimal> totalSales;
        private final List<Integer> transactionCounts;
        private final List<ProductYearSeries> products;

        public SameDateHistory(int month, int day, List<Integer> years, List<BigDecimal> totalSales,
                               List<Integer> transactionCounts, List<ProductYearSeries> products) {
            this.month = month;
            this.day = day;
            this.years = years;
            this.totalSales = totalSales;
            this.transactionCounts = transactionCounts;
            this.products = products;
        }

        public int getMonth() { return month; }
        public int getDay() { return day; }
        public List<Integer> getYears() { return years; }
        public List<BigDecimal> getTotalSales() { return totalSales; }
        public List<Integer> getTransactionCounts() { return transactionCounts; }
        public List<ProductYearSeries> getProducts() { return products; }
    }

    public static class ProductYearSeries {
        private final String productName;
        private final List<BigDecimal> yearlySales;

        public ProductYearSeries(String productName, List<BigDecimal> yearlySales) {
            this.productName = productName;
            this.yearlySales = yearlySales;
        }

        public String getProductName() { return productName; }
        public List<BigDecimal> getYearlySales() { return yearlySales; }
    }

    private static String normalizeProductName(String value) {
        if (value == null) return "";
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean containsName(List<String> names, String candidate) {
        for (String name : names) {
            if (name != null && name.equals(candidate)) return true;
        }
        return false;
    }

    private static String findMatchingProduct(Set<String> available, String requested) {
        if (available == null || requested == null) return null;
        for (String name : available) {
            if (normalizeProductName(name).equals(normalizeProductName(requested))) return name;
        }
        return null;
    }

    /**
     * PostgreSQL CAST(AS date) yields java.sql.Date; H2/other drivers may hand
     * back LocalDate directly. Normalize both to LocalDate.
     */
    private static LocalDate asLocalDate(Object value) {
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        return (LocalDate) value;
    }}
