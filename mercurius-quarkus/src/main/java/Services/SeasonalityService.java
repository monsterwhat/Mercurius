package Services;

import jakarta.annotation.Nonnull;
import Models.ReportesFamiliasYDepartamentos;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import io.quarkus.cache.CacheResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;

@Named
@ApplicationScoped
public class SeasonalityService {

    @Inject @Nonnull
    EntityManager em;

    @Inject @Nonnull
    InventarioService inventarioService;

    @Transactional(TxType.SUPPORTS)
    @CacheResult(cacheName = "analytics-seasonality")
    @Nonnull
    public Map<YearMonth, BigDecimal> getMonthlySales(@Nonnull Date start, @Nonnull Date end) {
        LocalDateTime startLdt = toLocalDateTime(start);
        LocalDateTime endLdt = toLocalDateTime(end);

        List<Object[]> results = em.createQuery(
            "SELECT YEAR(e.fechaEmision), MONTH(e.fechaEmision), " +
            "SUM(r.totalComprobante) " +
            "FROM ComprobantesEmitidos f " +
            "JOIN f.encabezado e " +
            "JOIN f.resumen r " +
            "WHERE f.status = true " +
            "AND e.fechaEmision BETWEEN :start AND :end " +
            "GROUP BY YEAR(e.fechaEmision), MONTH(e.fechaEmision) " +
            "ORDER BY YEAR(e.fechaEmision), MONTH(e.fechaEmision)",
            Object[].class
        )
        .setParameter("start", startLdt)
        .setParameter("end", endLdt)
        .getResultList();

        Map<YearMonth, BigDecimal> monthlySales = new LinkedHashMap<>();
        for (Object[] row : results) {
            int year = (Integer) row[0];
            int month = (Integer) row[1];
            BigDecimal total = (BigDecimal) row[2];
            monthlySales.put(YearMonth.of(year, month), total != null ? total : BigDecimal.ZERO);
        }
        return monthlySales;
    }

    @CacheResult(cacheName = "analytics-seasonality")
    @Transactional(TxType.SUPPORTS)
    @Nonnull
    public Map<Integer, BigDecimal> getSalesByDayOfWeek(@Nonnull Date start, @Nonnull Date end) {
        LocalDateTime startLdt = toLocalDateTime(start);
        LocalDateTime endLdt = toLocalDateTime(end);

        List<Object[]> results = em.createQuery(
            "SELECT CAST(e.fechaEmision AS date), SUM(r.totalComprobante) " +
            "FROM ComprobantesEmitidos f " +
            "JOIN f.encabezado e " +
            "JOIN f.resumen r " +
            "WHERE f.status = true " +
            "AND e.fechaEmision BETWEEN :start AND :end " +
            "GROUP BY CAST(e.fechaEmision AS date) " +
            "ORDER BY CAST(e.fechaEmision AS date)",
            Object[].class
        )
        .setParameter("start", startLdt)
        .setParameter("end", endLdt)
        .getResultList();

        // 1=Monday ... 7=Sunday, all initialized to zero
        Map<Integer, BigDecimal> dayOfWeekSales = new LinkedHashMap<>();
        for (int i = 1; i <= 7; i++) {
            dayOfWeekSales.put(i, BigDecimal.ZERO);
        }

        for (Object[] row : results) {
            java.sql.Date date = (java.sql.Date) row[0];
            BigDecimal total = (BigDecimal) row[1];
            int dayOfWeek = date.toLocalDate().getDayOfWeek().getValue();
            dayOfWeekSales.put(dayOfWeek, dayOfWeekSales.get(dayOfWeek).add(total != null ? total : BigDecimal.ZERO));
        }
        return dayOfWeekSales;
    }

    @Transactional(TxType.SUPPORTS)
    @Nonnull
    public Map<String, BigDecimal> getSalesByDepartment(@Nonnull Date start, @Nonnull Date end) {
        List<ReportesFamiliasYDepartamentos> reportes = inventarioService.getTotalSalesByDepartamento(start, end);
        Map<String, BigDecimal> deptSales = new LinkedHashMap<>();
        if (reportes != null) {
            for (ReportesFamiliasYDepartamentos r : reportes) {
                deptSales.put(r.getNombre(), r.getCantidad());
            }
        }
        return deptSales;
    }

    @Transactional(TxType.SUPPORTS)
    @Nonnull
    public Map<String, BigDecimal> getSalesByFamily(@Nonnull Date start, @Nonnull Date end) {
        List<ReportesFamiliasYDepartamentos> reportes = inventarioService.getTotalSalesByFamilia(start, end);
        Map<String, BigDecimal> familySales = new LinkedHashMap<>();
        if (reportes != null) {
            for (ReportesFamiliasYDepartamentos r : reportes) {
                familySales.put(r.getNombre(), r.getCantidad());
            }
        }
        return familySales;
    }

    @Transactional(TxType.SUPPORTS)
    @Nonnull
    public List<Object[]> getDailySales(@Nonnull Date start, @Nonnull Date end) {
        LocalDateTime startLdt = toLocalDateTime(start);
        LocalDateTime endLdt = toLocalDateTime(end);

        return em.createQuery(
            "SELECT CAST(e.fechaEmision AS date), COALESCE(SUM(r.totalComprobante), 0) " +
            "FROM ComprobantesEmitidos f " +
            "JOIN f.encabezado e " +
            "JOIN f.resumen r " +
            "WHERE f.status = true " +
            "AND e.fechaEmision BETWEEN :start AND :end " +
            "GROUP BY CAST(e.fechaEmision AS date) " +
            "ORDER BY CAST(e.fechaEmision AS date)",
            Object[].class
        )
        .setParameter("start", startLdt)
        .setParameter("end", endLdt)
        .getResultList();
    }

    @Nonnull
    private LocalDateTime toLocalDateTime(@Nonnull Date date) {
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
    }
}
