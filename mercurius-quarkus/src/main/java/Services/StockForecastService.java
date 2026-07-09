package Services;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import Models.Articulos.Articulos;
import Models.Inventario;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import io.quarkus.cache.CacheResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
@Named("stockForecastService")
public class StockForecastService {

    @Inject @Nonnull
    EntityManager entityManager;

    @Transactional(TxType.SUPPORTS)
    @Nonnull
    public List<ProductForecast> generateForecast(@Nonnull Long articuloId, int forecastDays) {
        Articulos articulo = entityManager.find(Articulos.class, articuloId);
        if (articulo == null) return Collections.emptyList();

        List<SalesData> salesHistory = getSalesHistory(articuloId, 90);
        
        if (salesHistory.isEmpty()) {
            return Collections.emptyList();
        }

        BigDecimal averageDailySales = calculateAverageDailySales(salesHistory);
        BigDecimal salesVelocity = calculateSalesVelocity(salesHistory);
        BigDecimal trendFactor = calculateTrendFactor(salesHistory);

        int currentStock = getCurrentStock(articuloId);
        int reorderPoint = calculateReorderPoint(averageDailySales, 7);
        int optimalStock = calculateOptimalStock(averageDailySales, 30);
        int safetyStock = calculateSafetyStock(salesHistory);

        List<ProductForecast> forecasts = new ArrayList<>();
        BigDecimal runningStock = BigDecimal.valueOf(currentStock);
        BigDecimal predictedSales = averageDailySales;

        for (int day = 1; day <= forecastDays; day++) {
            predictedSales = predictedSales.multiply(trendFactor);
            
            runningStock = runningStock.subtract(predictedSales);
            
            boolean shouldReorder = runningStock.compareTo(BigDecimal.valueOf(reorderPoint)) <= 0;
            
            LocalDate forecastDate = LocalDate.now().plusDays(day);
            
            forecasts.add(new ProductForecast(
                articulo.getCodigo(),
                articulo.getNombre(),
                forecastDate,
                currentStock,
                predictedSales.setScale(0, RoundingMode.HALF_UP).intValue(),
                Math.max(0, runningStock.setScale(0, RoundingMode.HALF_UP).intValue()),
                shouldReorder,
                averageDailySales.setScale(2, RoundingMode.HALF_UP),
                salesVelocity.setScale(2, RoundingMode.HALF_UP)
            ));
        }

        return forecasts;
    }

    @Transactional(TxType.SUPPORTS)
    @Nonnull
    public List<ProductForecast> generateBulkForecast(int daysToForecast) {
        List<Articulos> articulos = entityManager.createQuery(
            "SELECT a FROM Articulos a WHERE a.status = true", Articulos.class
        ).getResultList();

        List<ProductForecast> allForecasts = new ArrayList<>();

        for (Articulos articulo : articulos) {
            List<SalesData> salesHistory = getSalesHistory(articulo.getCodigo(), 90);
            
            if (salesHistory.isEmpty()) continue;

            BigDecimal averageDailySales = calculateAverageDailySales(salesHistory);
            BigDecimal salesVelocity = calculateSalesVelocity(salesHistory);
            BigDecimal trendFactor = calculateTrendFactor(salesHistory);

            int currentStock = getCurrentStock(articulo.getCodigo());
            int reorderPoint = calculateReorderPoint(averageDailySales, 7);
            
            BigDecimal predictedSales = averageDailySales;
            BigDecimal runningStock = BigDecimal.valueOf(currentStock);

            for (int day = 1; day <= daysToForecast; day++) {
                predictedSales = predictedSales.multiply(trendFactor);
                runningStock = runningStock.subtract(predictedSales);

                if (runningStock.compareTo(BigDecimal.valueOf(reorderPoint)) <= 0) {
                    LocalDate forecastDate = LocalDate.now().plusDays(day);
                    allForecasts.add(new ProductForecast(
                        articulo.getCodigo(),
                        articulo.getNombre(),
                        forecastDate,
                        currentStock,
                        predictedSales.setScale(0, RoundingMode.HALF_UP).intValue(),
                        Math.max(0, runningStock.setScale(0, RoundingMode.HALF_UP).intValue()),
                        true,
                        averageDailySales.setScale(2, RoundingMode.HALF_UP),
                        salesVelocity.setScale(2, RoundingMode.HALF_UP)
                    ));
                    break;
                }
            }
        }

        return allForecasts;
    }

    @Transactional(TxType.SUPPORTS)
    @Nonnull
    public DemandPrediction predictDemand(@Nonnull Long articuloId, int daysToPredict) {
        List<SalesData> salesHistory = getSalesHistory(articuloId, 180);
        
        if (salesHistory.isEmpty()) {
            return new DemandPrediction(articuloId, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "INSUFFICIENT_DATA");
        }

        BigDecimal avgDaily = calculateAverageDailySales(salesHistory);
        BigDecimal velocity = calculateSalesVelocity(salesHistory);
        BigDecimal trend = calculateTrendFactor(salesHistory);
        
        BigDecimal predictedDemand = avgDaily.multiply(BigDecimal.valueOf(daysToPredict));
        
        BigDecimal seasonalFactor = calculateSeasonalFactor(salesHistory);
        
        predictedDemand = predictedDemand.multiply(seasonalFactor);

        String confidence;
        if (salesHistory.size() >= 90) {
            confidence = "HIGH";
        } else if (salesHistory.size() >= 30) {
            confidence = "MEDIUM";
        } else {
            confidence = "LOW";
        }

        return new DemandPrediction(
            articuloId,
            predictedDemand.setScale(2, RoundingMode.HALF_UP),
            avgDaily.setScale(2, RoundingMode.HALF_UP),
            trend.setScale(2, RoundingMode.HALF_UP),
            confidence
        );
    }

    @Transactional(TxType.SUPPORTS)
    @Nonnull
    public InventoryHealthReport getInventoryHealthReport() {
        List<Articulos> articulos = entityManager.createQuery(
            "SELECT a FROM Articulos a WHERE a.status = true", Articulos.class
        ).getResultList();

        int totalProducts = articulos.size();
        int lowStock = 0;
        int overStocked = 0;
        int optimal = 0;
        int outOfStock = 0;

        List<Articulos> criticalItems = new ArrayList<>();

        for (Articulos articulo : articulos) {
            List<SalesData> salesHistory = getSalesHistory(articulo.getCodigo(), 90);
            int currentStock = getCurrentStock(articulo.getCodigo());
            
            if (currentStock <= 0) {
                outOfStock++;
                criticalItems.add(articulo);
            } else if (salesHistory.isEmpty()) {
                optimal++;
            } else {
                BigDecimal avgDaily = calculateAverageDailySales(salesHistory);
                int reorderPoint = calculateReorderPoint(avgDaily, 7);
                int optimalStock = calculateOptimalStock(avgDaily, 30);

                if (currentStock < reorderPoint) {
                    lowStock++;
                    if (currentStock < reorderPoint / 2) {
                        criticalItems.add(articulo);
                    }
                } else if (currentStock > optimalStock * 2) {
                    overStocked++;
                } else {
                    optimal++;
                }
            }
        }

        return new InventoryHealthReport(
            totalProducts,
            optimal,
            lowStock,
            overStocked,
            outOfStock,
            criticalItems.stream().limit(10).map(a -> a.getNombre()).collect(Collectors.toList())
        );
    }

    @Transactional(TxType.SUPPORTS)
    @Nullable
    public ReorderRecommendation getReorderRecommendation(@Nonnull Long articuloId) {
        Articulos articulo = entityManager.find(Articulos.class, articuloId);
        if (articulo == null) return null;

        int currentStock = getCurrentStock(articuloId);
        List<SalesData> salesHistory = getSalesHistory(articuloId, 90);
        
        if (salesHistory.isEmpty()) {
            return new ReorderRecommendation(
                articuloId,
                articulo.getNombre(),
                currentStock,
                0,
                0,
                "NO_SALES_DATA",
                BigDecimal.ZERO
            );
        }

        BigDecimal avgDaily = calculateAverageDailySales(salesHistory);
        BigDecimal velocity = calculateSalesVelocity(salesHistory);
        
        int daysUntilStockout = avgDaily.compareTo(BigDecimal.ZERO) > 0 
            ? currentStock / avgDaily.setScale(0, RoundingMode.HALF_UP).intValue()
            : 999;

        int recommendedReorder = calculateOptimalStock(avgDaily, 30) - currentStock;
        
        String status;
        if (currentStock <= 0) {
            status = "OUT_OF_STOCK";
        } else if (daysUntilStockout <= 3) {
            status = "CRITICAL";
        } else if (daysUntilStockout <= 7) {
            status = "LOW";
        } else if (daysUntilStockout <= 14) {
            status = "MODERATE";
        } else {
            status = "OPTIMAL";
        }

        return new ReorderRecommendation(
            articuloId,
            articulo.getNombre(),
            currentStock,
            Math.max(0, recommendedReorder),
            daysUntilStockout,
            status,
            avgDaily.setScale(2, RoundingMode.HALF_UP)
        );
    }

    @CacheResult(cacheName = "analytics-forecast")
    List<SalesData> getSalesHistory(Long articuloId, int days) {
        LocalDateTime startDate = LocalDate.now().minusDays(days).atStartOfDay();
        LocalDateTime endDate = LocalDate.now().atTime(23, 59, 59);

        List<Object[]> results = entityManager.createQuery(
            "SELECT CAST(e.fechaEmision AS date), SUM(ld.cantidad) " +
            "FROM ComprobantesEmitidos f " +
            "JOIN f.detalles d " +
            "JOIN d.lineasDetalle ld " +
            "JOIN f.encabezado e " +
            "WHERE f.status = true " +
            "AND e.fechaEmision BETWEEN :start AND :end " +
            "AND ld.detalle = (SELECT a.nombre FROM Articulos a WHERE a.codigo = :articuloId) " +
            "GROUP BY CAST(e.fechaEmision AS date) " +
            "ORDER BY CAST(e.fechaEmision AS date)",
            Object[].class
        )
        .setParameter("start", startDate)
        .setParameter("end", endDate)
        .setParameter("articuloId", articuloId)
        .getResultList();

        return results.stream()
            .map(row -> new SalesData((LocalDate) row[0], ((Number) row[1]).longValue()))
            .collect(Collectors.toList());
    }

    private int getCurrentStock(Long articuloId) {
        try {
            BigDecimal stock = entityManager.createQuery(
                "SELECT COALESCE(SUM(i.cantidad), 0) FROM Inventario i WHERE i.articulo.codigo = :articuloId",
                BigDecimal.class
            ).setParameter("articuloId", articuloId)
             .getSingleResult();
            return stock != null ? stock.intValue() : 0;
        } catch (jakarta.persistence.PersistenceException e) {
            return 0;
        }
    }

    private BigDecimal calculateAverageDailySales(List<SalesData> salesHistory) {
        if (salesHistory.isEmpty()) return BigDecimal.ZERO;
        
        BigDecimal total = salesHistory.stream()
            .map(SalesData::quantity)
            .map(BigDecimal::valueOf)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        return total.divide(BigDecimal.valueOf(salesHistory.size()), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateSalesVelocity(List<SalesData> salesHistory) {
        if (salesHistory.size() < 2) return BigDecimal.ONE;
        
        double sum = 0;
        double sumSquared = 0;
        
        for (SalesData data : salesHistory) {
            sum += data.quantity();
            sumSquared += data.quantity() * data.quantity();
        }
        
        double n = salesHistory.size();
        double mean = sum / n;
        double variance = (sumSquared / n) - (mean * mean);
        double stdDev = Math.sqrt(variance);
        
        BigDecimal coefficientOfVariation = BigDecimal.valueOf(stdDev / mean);
        
        return BigDecimal.ONE.add(coefficientOfVariation.negate());
    }

    private BigDecimal calculateTrendFactor(List<SalesData> salesHistory) {
        if (salesHistory.size() < 7) return BigDecimal.ONE;
        
        int half = salesHistory.size() / 2;
        List<SalesData> firstHalf = salesHistory.subList(0, half);
        List<SalesData> secondHalf = salesHistory.subList(half, salesHistory.size());
        
        BigDecimal firstAvg = calculateAverageDailySales(firstHalf);
        BigDecimal secondAvg = calculateAverageDailySales(secondHalf);
        
        if (firstAvg.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ONE;
        
        return secondAvg.divide(firstAvg, 4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateSeasonalFactor(List<SalesData> salesHistory) {
        if (salesHistory.isEmpty()) return BigDecimal.ONE;
        
        Map<DayOfWeek, List<Long>> dayOfWeekSales = new HashMap<>();
        
        for (SalesData data : salesHistory) {
            DayOfWeek day = data.date().getDayOfWeek();
            dayOfWeekSales.computeIfAbsent(day, k -> new ArrayList<>()).add(data.quantity());
        }
        
        BigDecimal currentDayFactor = BigDecimal.ONE;
        DayOfWeek today = LocalDate.now().getDayOfWeek();
        
        if (dayOfWeekSales.containsKey(today)) {
            List<Long> todaySales = dayOfWeekSales.get(today);
            double avgToday = todaySales.stream().mapToLong(Long::longValue).average().orElse(0);
            double overallAvg = salesHistory.stream().mapToLong(SalesData::quantity).average().orElse(0);
            
            if (overallAvg > 0) {
                currentDayFactor = BigDecimal.valueOf(avgToday / overallAvg);
            }
        }
        
        return currentDayFactor;
    }

    private int calculateReorderPoint(BigDecimal avgDailySales, int leadTimeDays) {
        return avgDailySales.setScale(0, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(leadTimeDays))
            .intValue() + 1;
    }

    private int calculateOptimalStock(BigDecimal avgDailySales, int daysStock) {
        return avgDailySales.setScale(0, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(daysStock))
            .intValue();
    }

    private int calculateSafetyStock(List<SalesData> salesHistory) {
        if (salesHistory.size() < 7) return 7;
        
        double sum = salesHistory.stream().mapToLong(SalesData::quantity).sum();
        double mean = sum / salesHistory.size();
        
        double variance = salesHistory.stream()
            .mapToDouble(d -> Math.pow(d.quantity() - mean, 2))
            .sum() / salesHistory.size();
        
        double stdDev = Math.sqrt(variance);
        
        return Math.max(1, (int) Math.ceil(stdDev * 1.65));
    }

    public record SalesData(LocalDate date, long quantity) {}

    public static class ProductForecast {
        private Long articuloId;
        private String articuloNombre;
        private LocalDate forecastDate;
        private int currentStock;
        private int predictedSales;
        private int predictedStock;
        private boolean shouldReorder;
        private BigDecimal avgDailySales;
        private BigDecimal salesVelocity;

        public ProductForecast(Long articuloId, String articuloNombre, LocalDate forecastDate,
                            int currentStock, int predictedSales, int predictedStock,
                            boolean shouldReorder, BigDecimal avgDailySales, BigDecimal salesVelocity) {
            this.articuloId = articuloId;
            this.articuloNombre = articuloNombre;
            this.forecastDate = forecastDate;
            this.currentStock = currentStock;
            this.predictedSales = predictedSales;
            this.predictedStock = predictedStock;
            this.shouldReorder = shouldReorder;
            this.avgDailySales = avgDailySales;
            this.salesVelocity = salesVelocity;
        }

        public Long articuloId() { return articuloId; }
        public String articuloNombre() { return articuloNombre; }
        public LocalDate forecastDate() { return forecastDate; }
        public int currentStock() { return currentStock; }
        public int predictedSales() { return predictedSales; }
        public int predictedStock() { return predictedStock; }
        public boolean shouldReorder() { return shouldReorder; }
        public BigDecimal avgDailySales() { return avgDailySales; }
        public BigDecimal salesVelocity() { return salesVelocity; }
    }

    public static class DemandPrediction {
        private Long articuloId;
        private BigDecimal predictedDemand;
        private BigDecimal avgDailySales;
        private BigDecimal trendFactor;
        private String confidence;

        public DemandPrediction(Long articuloId, BigDecimal predictedDemand, BigDecimal avgDailySales,
                             BigDecimal trendFactor, String confidence) {
            this.articuloId = articuloId;
            this.predictedDemand = predictedDemand;
            this.avgDailySales = avgDailySales;
            this.trendFactor = trendFactor;
            this.confidence = confidence;
        }

        public Long articuloId() { return articuloId; }
        public BigDecimal predictedDemand() { return predictedDemand; }
        public BigDecimal avgDailySales() { return avgDailySales; }
        public BigDecimal trendFactor() { return trendFactor; }
        public String confidence() { return confidence; }
    }

    public static class InventoryHealthReport {
        private int totalProducts;
        private int optimal;
        private int lowStock;
        private int overStocked;
        private int outOfStock;
        private List<String> criticalItems;

        public InventoryHealthReport(int totalProducts, int optimal, int lowStock, int overStocked,
                                   int outOfStock, List<String> criticalItems) {
            this.totalProducts = totalProducts;
            this.optimal = optimal;
            this.lowStock = lowStock;
            this.overStocked = overStocked;
            this.outOfStock = outOfStock;
            this.criticalItems = criticalItems;
        }

        public int totalProducts() { return totalProducts; }
        public int optimal() { return optimal; }
        public int lowStock() { return lowStock; }
        public int overStocked() { return overStocked; }
        public int outOfStock() { return outOfStock; }
        public List<String> criticalItems() { return criticalItems; }
    }

    public static class ReorderRecommendation {
        private Long articuloId;
        private String articuloNombre;
        private int currentStock;
        private int recommendedReorder;
        private int daysUntilStockout;
        private String status;
        private BigDecimal avgDailySales;

        public ReorderRecommendation(Long articuloId, String articuloNombre, int currentStock,
                                   int recommendedReorder, int daysUntilStockout, String status,
                                   BigDecimal avgDailySales) {
            this.articuloId = articuloId;
            this.articuloNombre = articuloNombre;
            this.currentStock = currentStock;
            this.recommendedReorder = recommendedReorder;
            this.daysUntilStockout = daysUntilStockout;
            this.status = status;
            this.avgDailySales = avgDailySales;
        }

        public Long articuloId() { return articuloId; }
        public String articuloNombre() { return articuloNombre; }
        public int currentStock() { return currentStock; }
        public int recommendedReorder() { return recommendedReorder; }
        public int daysUntilStockout() { return daysUntilStockout; }
        public String status() { return status; }
        public BigDecimal avgDailySales() { return avgDailySales; }
    }
}
