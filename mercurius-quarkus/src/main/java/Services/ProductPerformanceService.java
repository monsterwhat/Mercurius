package Services;

import Models.Articulos.Articulos;
import Models.ComprobantesEmitidos;
import Models.Detalles.LineaDetalle;
import Models.Departamento;
import Models.Familia;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
@Named("productPerformanceService")
public class ProductPerformanceService {

    @Inject
    EntityManager entityManager;

    @Transactional
    public List<ProductSalesSummary> getBestSellingProducts(Date startDate, Date endDate, int limit) {
        LocalDateTime start = toLocalDateTime(startDate);
        LocalDateTime end = toLocalDateTime(endDate);

        List<Object[]> results = entityManager.createQuery(
            "SELECT ld.detalle, SUM(ld.cantidad), SUM(ld.montoTotalLinea) " +
            "FROM ComprobantesEmitidos f " +
            "JOIN f.detalles d " +
            "JOIN d.lineasDetalle ld " +
            "JOIN f.encabezado e " +
            "WHERE f.status = true " +
            "AND e.fechaEmision BETWEEN :start AND :end " +
            "GROUP BY ld.detalle " +
            "ORDER BY SUM(ld.cantidad) DESC"
        )
        .setParameter("start", start)
        .setParameter("end", end)
        .setMaxResults(limit)
        .getResultList();

        return results.stream()
            .map(row -> new ProductSalesSummary(
                (String) row[0],
                ((Number) row[1]).longValue(),
                (BigDecimal) row[2]
            ))
            .collect(Collectors.toList());
    }

    @Transactional
    public List<ProductSalesSummary> getWorstSellingProducts(Date startDate, Date endDate, int limit) {
        LocalDateTime start = toLocalDateTime(startDate);
        LocalDateTime end = toLocalDateTime(endDate);

        List<Object[]> results = entityManager.createQuery(
            "SELECT ld.detalle, SUM(ld.cantidad), SUM(ld.montoTotalLinea) " +
            "FROM ComprobantesEmitidos f " +
            "JOIN f.detalles d " +
            "JOIN d.lineasDetalle ld " +
            "JOIN f.encabezado e " +
            "WHERE f.status = true " +
            "AND e.fechaEmision BETWEEN :start AND :end " +
            "GROUP BY ld.detalle " +
            "HAVING SUM(ld.cantidad) > 0 " +
            "ORDER BY SUM(ld.cantidad) ASC"
        )
        .setParameter("start", start)
        .setParameter("end", end)
        .setMaxResults(limit)
        .getResultList();

        return results.stream()
            .map(row -> new ProductSalesSummary(
                (String) row[0],
                ((Number) row[1]).longValue(),
                (BigDecimal) row[2]
            ))
            .collect(Collectors.toList());
    }

    @Transactional
    public List<ProductSalesSummary> getBestSellingProductsByRevenue(Date startDate, Date endDate, int limit) {
        LocalDateTime start = toLocalDateTime(startDate);
        LocalDateTime end = toLocalDateTime(endDate);

        List<Object[]> results = entityManager.createQuery(
            "SELECT ld.detalle, SUM(ld.cantidad), SUM(ld.montoTotalLinea) " +
            "FROM ComprobantesEmitidos f " +
            "JOIN f.detalles d " +
            "JOIN d.lineasDetalle ld " +
            "JOIN f.encabezado e " +
            "WHERE f.status = true " +
            "AND e.fechaEmision BETWEEN :start AND :end " +
            "GROUP BY ld.detalle " +
            "ORDER BY SUM(ld.montoTotalLinea) DESC"
        )
        .setParameter("start", start)
        .setParameter("end", end)
        .setMaxResults(limit)
        .getResultList();

        return results.stream()
            .map(row -> new ProductSalesSummary(
                (String) row[0],
                ((Number) row[1]).longValue(),
                (BigDecimal) row[2]
            ))
            .collect(Collectors.toList());
    }

    @Transactional
    public BigDecimal getProductVelocity(Long articuloId, int days) {
        Date endDate = new Date();
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, -days);
        Date startDate = cal.getTime();

        LocalDateTime start = toLocalDateTime(startDate);
        LocalDateTime end = toLocalDateTime(endDate);

        BigDecimal result = entityManager.createQuery(
            "SELECT COALESCE(SUM(ld.cantidad), 0) " +
            "FROM ComprobantesEmitidos f " +
            "JOIN f.detalles d " +
            "JOIN d.lineasDetalle ld " +
            "JOIN f.encabezado e " +
            "WHERE f.status = true " +
            "AND e.fechaEmision BETWEEN :start AND :end " +
            "AND ld.detalle IN (SELECT a.nombre FROM Articulos a WHERE a.codigo = :articuloId)",
            BigDecimal.class
        )
        .setParameter("start", start)
        .setParameter("end", end)
        .setParameter("articuloId", articuloId)
        .getSingleResult();

        return result != null ? result : BigDecimal.ZERO;
    }

    @Transactional
    public Map<String, BigDecimal> getCategoryPerformance(Date startDate, Date endDate) {
        LocalDateTime start = toLocalDateTime(startDate);
        LocalDateTime end = toLocalDateTime(endDate);

        List<Object[]> results = entityManager.createQuery(
            "SELECT a.familia.nombre, SUM(ld.montoTotalLinea) " +
            "FROM ComprobantesEmitidos f " +
            "JOIN f.detalles d " +
            "JOIN d.lineasDetalle ld " +
            "JOIN f.encabezado e " +
            "JOIN Articulos a ON a.nombre = ld.detalle " +
            "WHERE f.status = true " +
            "AND e.fechaEmision BETWEEN :start AND :end " +
            "GROUP BY a.familia.nombre " +
            "ORDER BY SUM(ld.montoTotalLinea) DESC",
            Object[].class
        )
        .setParameter("start", start)
        .setParameter("end", end)
        .getResultList();

        Map<String, BigDecimal> categoryPerformance = new LinkedHashMap<>();
        for (Object[] row : results) {
            String category = (String) row[0];
            BigDecimal revenue = (BigDecimal) row[1];
            categoryPerformance.put(category != null ? category : "Sin Categoría", revenue);
        }
        return categoryPerformance;
    }

    @Transactional
    public Map<String, BigDecimal> getDepartmentPerformance(Date startDate, Date endDate) {
        LocalDateTime start = toLocalDateTime(startDate);
        LocalDateTime end = toLocalDateTime(endDate);

        List<Object[]> results = entityManager.createQuery(
            "SELECT a.departamento.nombre, SUM(ld.montoTotalLinea) " +
            "FROM ComprobantesEmitidos f " +
            "JOIN f.detalles d " +
            "JOIN d.lineasDetalle ld " +
            "JOIN f.encabezado e " +
            "JOIN Articulos a ON a.nombre = ld.detalle " +
            "WHERE f.status = true " +
            "AND e.fechaEmision BETWEEN :start AND :end " +
            "GROUP BY a.departamento.nombre " +
            "ORDER BY SUM(ld.montoTotalLinea) DESC",
            Object[].class
        )
        .setParameter("start", start)
        .setParameter("end", end)
        .getResultList();

        Map<String, BigDecimal> departmentPerformance = new LinkedHashMap<>();
        for (Object[] row : results) {
            String department = (String) row[0];
            BigDecimal revenue = (BigDecimal) row[1];
            departmentPerformance.put(department != null ? department : "Sin Departamento", revenue);
        }
        return departmentPerformance;
    }

    @Transactional
    public List<DailySalesTrend> getSalesTrend(Date startDate, Date endDate) {
        LocalDateTime start = toLocalDateTime(startDate);
        LocalDateTime end = toLocalDateTime(endDate);

        List<Object[]> results = entityManager.createQuery(
            "SELECT FUNCTION('DATE', e.fechaEmision), SUM(r.totalComprobante), COUNT(f) " +
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
            .map(row -> new DailySalesTrend(
                (java.time.LocalDate) row[0],
                (BigDecimal) row[1],
                ((Number) row[2]).intValue()
            ))
            .collect(Collectors.toList());
    }

    @Transactional
    public List<ABCAnalysis> performABCAnalysis(Date startDate, Date endDate) {
        LocalDateTime start = toLocalDateTime(startDate);
        LocalDateTime end = toLocalDateTime(endDate);

        List<Object[]> results = entityManager.createQuery(
            "SELECT ld.detalle, SUM(ld.montoTotalLinea) " +
            "FROM ComprobantesEmitidos f " +
            "JOIN f.detalles d " +
            "JOIN d.lineasDetalle ld " +
            "JOIN f.encabezado e " +
            "WHERE f.status = true " +
            "AND e.fechaEmision BETWEEN :start AND :end " +
            "GROUP BY ld.detalle " +
            "ORDER BY SUM(ld.montoTotalLinea) DESC",
            Object[].class
        )
        .setParameter("start", start)
        .setParameter("end", end)
        .getResultList();

        BigDecimal totalRevenue = results.stream()
            .map(row -> (BigDecimal) row[1])
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<ABCAnalysis> abcList = new ArrayList<>();
        BigDecimal cumulativeRevenue = BigDecimal.ZERO;
        int rank = 1;

        for (Object[] row : results) {
            String productName = (String) row[0];
            BigDecimal revenue = (BigDecimal) row[1];
            cumulativeRevenue = cumulativeRevenue.add(revenue);

            BigDecimal cumulativePercentage = totalRevenue.compareTo(BigDecimal.ZERO) > 0
                ? cumulativeRevenue.multiply(BigDecimal.valueOf(100)).divide(totalRevenue, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

            String category;
            if (cumulativePercentage.compareTo(BigDecimal.valueOf(80)) <= 0) {
                category = "A";
            } else if (cumulativePercentage.compareTo(BigDecimal.valueOf(95)) <= 0) {
                category = "B";
            } else {
                category = "C";
            }

            abcList.add(new ABCAnalysis(
                rank++,
                productName,
                revenue,
                revenue.multiply(BigDecimal.valueOf(100)).divide(totalRevenue, 2, RoundingMode.HALF_UP),
                cumulativePercentage,
                category
            ));
        }

        return abcList;
    }

    @Transactional
    public ProductPerformanceSummary getPerformanceSummary(Date startDate, Date endDate) {
        LocalDateTime start = toLocalDateTime(startDate);
        LocalDateTime end = toLocalDateTime(endDate);

        BigDecimal totalRevenue = entityManager.createQuery(
            "SELECT COALESCE(SUM(r.totalComprobante), 0) " +
            "FROM ComprobantesEmitidos f " +
            "JOIN f.resumen r " +
            "JOIN f.encabezado e " +
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

        BigDecimal totalQuantity = entityManager.createQuery(
            "SELECT COALESCE(SUM(ld.cantidad), 0) " +
            "FROM ComprobantesEmitidos f " +
            "JOIN f.detalles d " +
            "JOIN d.lineasDetalle ld " +
            "JOIN f.encabezado e " +
            "WHERE f.status = true " +
            "AND e.fechaEmision BETWEEN :start AND :end",
            BigDecimal.class
        )
        .setParameter("start", start)
        .setParameter("end", end)
        .getSingleResult();

        Long uniqueProducts = entityManager.createQuery(
            "SELECT COUNT(DISTINCT ld.detalle) " +
            "FROM ComprobantesEmitidos f " +
            "JOIN f.detalles d " +
            "JOIN d.lineasDetalle ld " +
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

        return new ProductPerformanceSummary(
            totalRevenue != null ? totalRevenue : BigDecimal.ZERO,
            totalTransactions != null ? totalTransactions.intValue() : 0,
            totalQuantity != null ? totalQuantity.longValue() : 0L,
            uniqueProducts != null ? uniqueProducts.intValue() : 0,
            averageTicket
        );
    }

    private LocalDateTime toLocalDateTime(Date date) {
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
    }

    public static class ProductSalesSummary {
        private String productName;
        private Long quantitySold;
        private BigDecimal totalRevenue;

        public ProductSalesSummary(String productName, Long quantitySold, BigDecimal totalRevenue) {
            this.productName = productName;
            this.quantitySold = quantitySold;
            this.totalRevenue = totalRevenue;
        }

        public String getProductName() { return productName; }
        public Long getQuantitySold() { return quantitySold; }
        public BigDecimal getTotalRevenue() { return totalRevenue; }
    }

    public static class DailySalesTrend {
        private LocalDate date;
        private BigDecimal totalSales;
        private int transactionCount;

        public DailySalesTrend(LocalDate date, BigDecimal totalSales, int transactionCount) {
            this.date = date;
            this.totalSales = totalSales;
            this.transactionCount = transactionCount;
        }

        public LocalDate getDate() { return date; }
        public BigDecimal getTotalSales() { return totalSales; }
        public int getTransactionCount() { return transactionCount; }
    }

    public static class ABCAnalysis {
        private int rank;
        private String productName;
        private BigDecimal revenue;
        private BigDecimal revenuePercentage;
        private BigDecimal cumulativePercentage;
        private String category;

        public ABCAnalysis(int rank, String productName, BigDecimal revenue, 
                         BigDecimal revenuePercentage, BigDecimal cumulativePercentage, String category) {
            this.rank = rank;
            this.productName = productName;
            this.revenue = revenue;
            this.revenuePercentage = revenuePercentage;
            this.cumulativePercentage = cumulativePercentage;
            this.category = category;
        }

        public int getRank() { return rank; }
        public String getProductName() { return productName; }
        public BigDecimal getRevenue() { return revenue; }
        public BigDecimal getRevenuePercentage() { return revenuePercentage; }
        public BigDecimal getCumulativePercentage() { return cumulativePercentage; }
        public String getCategory() { return category; }
    }

    public static class ProductPerformanceSummary {
        private BigDecimal totalRevenue;
        private int totalTransactions;
        private long totalQuantitySold;
        private int uniqueProductsSold;
        private BigDecimal averageTicket;

        public ProductPerformanceSummary(BigDecimal totalRevenue, int totalTransactions, 
                                       long totalQuantitySold, int uniqueProductsSold, BigDecimal averageTicket) {
            this.totalRevenue = totalRevenue;
            this.totalTransactions = totalTransactions;
            this.totalQuantitySold = totalQuantitySold;
            this.uniqueProductsSold = uniqueProductsSold;
            this.averageTicket = averageTicket;
        }

        public BigDecimal getTotalRevenue() { return totalRevenue; }
        public int getTotalTransactions() { return totalTransactions; }
        public long getTotalQuantitySold() { return totalQuantitySold; }
        public int getUniqueProductsSold() { return uniqueProductsSold; }
        public BigDecimal getAverageTicket() { return averageTicket; }
    }
}
