package Services;

import Models.ComprobantesEmitidos;
import Models.Users;
import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import io.quarkus.cache.CacheResult;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
@Named("dashboardMetricsService")
public class DashboardMetricsService {

    @Inject @Nonnull
    EntityManager entityManager;

    @CacheResult(cacheName = "analytics-dashboard")
    @Transactional(Transactional.TxType.SUPPORTS)
    @Nonnull
    public BigDecimal getTodaySales(@Nonnull Users user) {
        LocalDate today = LocalDate.now();
        LocalDateTime startOfDay = today.atStartOfDay();
        LocalDateTime endOfDay = today.atTime(23, 59, 59);
        
        try {
            BigDecimal result = entityManager.createQuery(
                "SELECT COALESCE(SUM(r.totalComprobante), 0) " +
                "FROM ComprobantesEmitidos f " +
                "JOIN f.resumen r " +
                "JOIN f.encabezado e " +
                "WHERE f.user = :user " +
                "AND f.status = true " +
                "AND e.fechaEmision BETWEEN :startOfDay AND :endOfDay",
                BigDecimal.class
            ).setParameter("user", user)
             .setParameter("startOfDay", startOfDay)
             .setParameter("endOfDay", endOfDay)
             .getSingleResult();
            
            return result != null ? result : BigDecimal.ZERO;
        } catch (PersistenceException e) {
            return BigDecimal.ZERO;
        }
    }

    @CacheResult(cacheName = "analytics-dashboard")
    @Transactional(Transactional.TxType.SUPPORTS)
    @Nonnull
    public BigDecimal getYesterdaySales(@Nonnull Users user) {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        LocalDateTime startOfDay = yesterday.atStartOfDay();
        LocalDateTime endOfDay = yesterday.atTime(23, 59, 59);
        
        try {
            BigDecimal result = entityManager.createQuery(
                "SELECT COALESCE(SUM(r.totalComprobante), 0) " +
                "FROM ComprobantesEmitidos f " +
                "JOIN f.resumen r " +
                "JOIN f.encabezado e " +
                "WHERE f.user = :user " +
                "AND f.status = true " +
                "AND e.fechaEmision BETWEEN :startOfDay AND :endOfDay",
                BigDecimal.class
            ).setParameter("user", user)
             .setParameter("startOfDay", startOfDay)
             .setParameter("endOfDay", endOfDay)
             .getSingleResult();
            
            return result != null ? result : BigDecimal.ZERO;
        } catch (PersistenceException e) {
            return BigDecimal.ZERO;
        }
    }

    @CacheResult(cacheName = "analytics-dashboard")
    @Transactional(Transactional.TxType.SUPPORTS)
    @Nonnull
    public BigDecimal getWeekSales(@Nonnull Users user) {
        LocalDate today = LocalDate.now();
        LocalDate weekAgo = today.minusDays(7);
        LocalDateTime startOfPeriod = weekAgo.atStartOfDay();
        LocalDateTime endOfPeriod = today.atTime(23, 59, 59);
        
        try {
            BigDecimal result = entityManager.createQuery(
                "SELECT COALESCE(SUM(r.totalComprobante), 0) " +
                "FROM ComprobantesEmitidos f " +
                "JOIN f.resumen r " +
                "JOIN f.encabezado e " +
                "WHERE f.user = :user " +
                "AND f.status = true " +
                "AND e.fechaEmision BETWEEN :startOfPeriod AND :endOfPeriod",
                BigDecimal.class
            ).setParameter("user", user)
             .setParameter("startOfPeriod", startOfPeriod)
             .setParameter("endOfPeriod", endOfPeriod)
             .getSingleResult();
            
            return result != null ? result : BigDecimal.ZERO;
        } catch (PersistenceException e) {
            return BigDecimal.ZERO;
        }
    }

    @CacheResult(cacheName = "analytics-dashboard")
    @Transactional(Transactional.TxType.SUPPORTS)
    @Nonnull
    public BigDecimal getMonthSales(@Nonnull Users user) {
        LocalDate today = LocalDate.now();
        LocalDate monthAgo = today.minusDays(30);
        LocalDateTime startOfPeriod = monthAgo.atStartOfDay();
        LocalDateTime endOfPeriod = today.atTime(23, 59, 59);
        
        try {
            BigDecimal result = entityManager.createQuery(
                "SELECT COALESCE(SUM(r.totalComprobante), 0) " +
                "FROM ComprobantesEmitidos f " +
                "JOIN f.resumen r " +
                "JOIN f.encabezado e " +
                "WHERE f.user = :user " +
                "AND f.status = true " +
                "AND e.fechaEmision BETWEEN :startOfPeriod AND :endOfPeriod",
                BigDecimal.class
            ).setParameter("user", user)
             .setParameter("startOfPeriod", startOfPeriod)
             .setParameter("endOfPeriod", endOfPeriod)
             .getSingleResult();
            
            return result != null ? result : BigDecimal.ZERO;
        } catch (PersistenceException e) {
            return BigDecimal.ZERO;
        }
    }

    @CacheResult(cacheName = "analytics-dashboard")
    @Transactional(Transactional.TxType.SUPPORTS)
    public int getTodayTransactions(@Nonnull Users user) {
        LocalDate today = LocalDate.now();
        LocalDateTime startOfDay = today.atStartOfDay();
        LocalDateTime endOfDay = today.atTime(23, 59, 59);
        
        try {
            Long result = entityManager.createQuery(
                "SELECT COUNT(f) " +
                "FROM ComprobantesEmitidos f " +
                "JOIN f.encabezado e " +
                "WHERE f.user = :user " +
                "AND f.status = true " +
                "AND e.fechaEmision BETWEEN :startOfDay AND :endOfDay",
                Long.class
            ).setParameter("user", user)
              .setParameter("startOfDay", startOfDay)
              .setParameter("endOfDay", endOfDay)
              .getSingleResult();
            
            return result != null ? result.intValue() : 0;
        } catch (PersistenceException e) {
            return 0;
        }
    }

    @CacheResult(cacheName = "analytics-dashboard")
    @Transactional(Transactional.TxType.SUPPORTS)
    @Nonnull
    public BigDecimal getAverageTicket(@Nonnull Users user, int days) {
        LocalDate today = LocalDate.now();
        LocalDate periodAgo = today.minusDays(days);
        LocalDateTime startOfPeriod = periodAgo.atStartOfDay();
        LocalDateTime endOfPeriod = today.atTime(23, 59, 59);
        
        try {
            BigDecimal total = entityManager.createQuery(
                "SELECT COALESCE(SUM(r.totalComprobante), 0) " +
                "FROM ComprobantesEmitidos f " +
                "JOIN f.resumen r " +
                "JOIN f.encabezado e " +
                "WHERE f.user = :user " +
                "AND f.status = true " +
                "AND e.fechaEmision BETWEEN :startOfPeriod AND :endOfPeriod",
                BigDecimal.class
            ).setParameter("user", user)
             .setParameter("startOfPeriod", startOfPeriod)
             .setParameter("endOfPeriod", endOfPeriod)
             .getSingleResult();

            Long count = entityManager.createQuery(
                "SELECT COUNT(f) " +
                "FROM ComprobantesEmitidos f " +
                "JOIN f.encabezado e " +
                "WHERE f.user = :user " +
                "AND f.status = true " +
                "AND e.fechaEmision BETWEEN :startOfPeriod AND :endOfPeriod",
                Long.class
            ).setParameter("user", user)
             .setParameter("startOfPeriod", startOfPeriod)
             .setParameter("endOfPeriod", endOfPeriod)
             .getSingleResult();

            if (count != null && count > 0) {
                return total.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
            }
            return BigDecimal.ZERO;
        } catch (PersistenceException e) {
            return BigDecimal.ZERO;
        }
    }

    @CacheResult(cacheName = "analytics-dashboard")
    @Transactional(Transactional.TxType.SUPPORTS)
    @Nonnull
    public List<TopProduct> getTopSellingProducts(@Nonnull Users user, int limit) {
        LocalDate today = LocalDate.now();
        LocalDate weekAgo = today.minusDays(7);
        LocalDateTime startOfPeriod = weekAgo.atStartOfDay();
        LocalDateTime endOfPeriod = today.atTime(23, 59, 59);

        List<Object[]> results = entityManager.createQuery(
            "SELECT ld.detalle, SUM(ld.cantidad), SUM(ld.montoTotalLinea) " +
            "FROM ComprobantesEmitidos f " +
            "JOIN f.detalles d " +
            "JOIN d.lineasDetalle ld " +
            "JOIN f.encabezado e " +
            "WHERE f.user = :user " +
            "AND f.status = true " +
            "AND e.fechaEmision BETWEEN :startOfPeriod AND :endOfPeriod " +
            "GROUP BY ld.detalle " +
            "ORDER BY SUM(ld.cantidad) DESC"
        )
        .setParameter("user", user)
        .setParameter("startOfPeriod", startOfPeriod)
        .setParameter("endOfPeriod", endOfPeriod)
        .setMaxResults(limit)
        .getResultList();

        return results.stream()
            .map(row -> new TopProduct(
                (String) row[0],
                ((Number) row[1]).longValue(),
                (BigDecimal) row[2]
            ))
            .collect(Collectors.toList());
    }

    @CacheResult(cacheName = "analytics-dashboard")
    @Transactional(Transactional.TxType.SUPPORTS)
    @Nonnull
    public List<HourlySales> getHourlySalesDistribution(@Nonnull Users user, @Nonnull LocalDate date) {
        LocalDateTime startOfDay = date.atStartOfDay();
        LocalDateTime endOfDay = date.atTime(23, 59, 59);

        List<Object[]> results = entityManager.createQuery(
            "SELECT HOUR(e.fechaEmision), SUM(r.totalComprobante), COUNT(f) " +
            "FROM ComprobantesEmitidos f " +
            "JOIN f.encabezado e " +
            "JOIN f.resumen r " +
            "WHERE f.user = :user " +
            "AND f.status = true " +
            "AND e.fechaEmision BETWEEN :startOfDay AND :endOfDay " +
            "GROUP BY HOUR(e.fechaEmision) " +
            "ORDER BY HOUR(e.fechaEmision)"
        )
        .setParameter("user", user)
        .setParameter("startOfDay", startOfDay)
        .setParameter("endOfDay", endOfDay)
        .getResultList();

        Map<Integer, HourlySales> hourlyMap = new LinkedHashMap<>();
        for (int i = 0; i < 24; i++) {
            hourlyMap.put(i, new HourlySales(i, BigDecimal.ZERO, 0));
        }

        for (Object[] row : results) {
            int hour = ((Number) row[0]).intValue();
            hourlyMap.put(hour, new HourlySales(hour, (BigDecimal) row[1], ((Number) row[2]).intValue()));
        }

        return new ArrayList<>(hourlyMap.values());
    }

    @CacheResult(cacheName = "analytics-dashboard")
    @Transactional(Transactional.TxType.SUPPORTS)
    @Nonnull
    public List<DailySales> getWeeklySalesBreakdown(@Nonnull Users user) {
        LocalDate today = LocalDate.now();
        List<DailySales> dailySalesList = new ArrayList<>();

        for (int i = 6; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            LocalDateTime startOfDay = date.atStartOfDay();
            LocalDateTime endOfDay = date.atTime(23, 59, 59);

            BigDecimal total = entityManager.createQuery(
                "SELECT COALESCE(SUM(r.totalComprobante), 0) " +
                "FROM ComprobantesEmitidos f " +
                "JOIN f.resumen r " +
                "JOIN f.encabezado e " +
                "WHERE f.user = :user " +
                "AND f.status = true " +
                "AND e.fechaEmision BETWEEN :startOfDay AND :endOfDay",
                BigDecimal.class
            ).setParameter("user", user)
             .setParameter("startOfDay", startOfDay)
             .setParameter("endOfDay", endOfDay)
             .getSingleResult();

            Integer count = entityManager.createQuery(
                "SELECT COUNT(f) " +
                "FROM ComprobantesEmitidos f " +
                "JOIN f.encabezado e " +
                "WHERE f.user = :user " +
                "AND f.status = true " +
                "AND e.fechaEmision BETWEEN :startOfDay AND :endOfDay",
                Integer.class
            ).setParameter("user", user)
             .setParameter("startOfDay", startOfDay)
             .setParameter("endOfDay", endOfDay)
             .getSingleResult();

            dailySalesList.add(new DailySales(
                date,
                total != null ? total : BigDecimal.ZERO,
                count != null ? count : 0
            ));
        }

        return dailySalesList;
    }

    @CacheResult(cacheName = "analytics-dashboard")
    @Nonnull
    public DashboardKPI getKPIs(@Nonnull Users user) {
        BigDecimal todaySales = getTodaySales(user);
        BigDecimal yesterdaySales = getYesterdaySales(user);
        BigDecimal weekSales = getWeekSales(user);
        BigDecimal monthSales = getMonthSales(user);
        int todayTransactions = getTodayTransactions(user);
        BigDecimal avgTicket = getAverageTicket(user, 30);

        BigDecimal dailyGrowth = calculateGrowth(yesterdaySales, todaySales);

        return new DashboardKPI(
            todaySales,
            weekSales,
            monthSales,
            todayTransactions,
            avgTicket,
            dailyGrowth
        );
    }

    private BigDecimal calculateGrowth(BigDecimal previous, BigDecimal current) {
        if (previous == null || previous.compareTo(BigDecimal.ZERO) == 0) {
            return current.compareTo(BigDecimal.ZERO) > 0 ? BigDecimal.valueOf(100) : BigDecimal.ZERO;
        }
        return current.subtract(previous)
            .multiply(BigDecimal.valueOf(100))
            .divide(previous, 2, RoundingMode.HALF_UP);
    }

    public static class TopProduct {
        private String name;
        private long quantity;
        private BigDecimal revenue;

        public TopProduct(String name, long quantity, BigDecimal revenue) {
            this.name = name;
            this.quantity = quantity;
            this.revenue = revenue;
        }

        public String getName() { return name; }
        public long getQuantity() { return quantity; }
        public BigDecimal getRevenue() { return revenue; }
    }

    public static class HourlySales {
        private int hour;
        private BigDecimal totalSales;
        private int transactions;

        public HourlySales(int hour, BigDecimal totalSales, int transactions) {
            this.hour = hour;
            this.totalSales = totalSales;
            this.transactions = transactions;
        }

        public int getHour() { return hour; }
        public BigDecimal getTotalSales() { return totalSales; }
        public int getTransactions() { return transactions; }
    }

    public static class DailySales {
        private LocalDate date;
        private BigDecimal totalSales;
        private int transactions;

        public DailySales(LocalDate date, BigDecimal totalSales, int transactions) {
            this.date = date;
            this.totalSales = totalSales;
            this.transactions = transactions;
        }

        public LocalDate getDate() { return date; }
        public BigDecimal getTotalSales() { return totalSales; }
        public int getTransactions() { return transactions; }
    }

    public static class DashboardKPI {
        private BigDecimal todaySales;
        private BigDecimal weekSales;
        private BigDecimal monthSales;
        private int todayTransactions;
        private BigDecimal averageTicket;
        private BigDecimal dailyGrowth;

        public DashboardKPI(BigDecimal todaySales, BigDecimal weekSales, BigDecimal monthSales,
                          int todayTransactions, BigDecimal averageTicket, BigDecimal dailyGrowth) {
            this.todaySales = todaySales;
            this.weekSales = weekSales;
            this.monthSales = monthSales;
            this.todayTransactions = todayTransactions;
            this.averageTicket = averageTicket;
            this.dailyGrowth = dailyGrowth;
        }

        public BigDecimal getTodaySales() { return todaySales; }
        public BigDecimal getWeekSales() { return weekSales; }
        public BigDecimal getMonthSales() { return monthSales; }
        public int getTodayTransactions() { return todayTransactions; }
        public BigDecimal getAverageTicket() { return averageTicket; }
        public BigDecimal getDailyGrowth() { return dailyGrowth; }
    }
}
