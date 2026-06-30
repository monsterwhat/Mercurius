package Services;

import Models.ReportesFamiliasYDepartamentos;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;

@Named
@ApplicationScoped
public class SeasonalityService {

    @Inject
    EntityManager em;

    @Inject
    InventarioService inventarioService;

    @Transactional
    public Map<YearMonth, BigDecimal> getMonthlySales(Date start, Date end) {
        LocalDateTime startLdt = toLocalDateTime(start);
        LocalDateTime endLdt = toLocalDateTime(end);

        List<Object[]> results = em.createQuery(
            "SELECT FUNCTION('YEAR', e.fechaEmision), FUNCTION('MONTH', e.fechaEmision), " +
            "SUM(r.totalComprobante) " +
            "FROM ComprobantesEmitidos f " +
            "JOIN f.encabezado e " +
            "JOIN f.resumen r " +
            "WHERE f.status = true " +
            "AND e.fechaEmision BETWEEN :start AND :end " +
            "GROUP BY FUNCTION('YEAR', e.fechaEmision), FUNCTION('MONTH', e.fechaEmision) " +
            "ORDER BY FUNCTION('YEAR', e.fechaEmision), FUNCTION('MONTH', e.fechaEmision)",
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

    @Transactional
    public Map<Integer, BigDecimal> getSalesByDayOfWeek(Date start, Date end) {
        LocalDateTime startLdt = toLocalDateTime(start);
        LocalDateTime endLdt = toLocalDateTime(end);

        List<Object[]> results = em.createQuery(
            "SELECT FUNCTION('WEEKDAY', e.fechaEmision), SUM(r.totalComprobante) " +
            "FROM ComprobantesEmitidos f " +
            "JOIN f.encabezado e " +
            "JOIN f.resumen r " +
            "WHERE f.status = true " +
            "AND e.fechaEmision BETWEEN :start AND :end " +
            "GROUP BY FUNCTION('WEEKDAY', e.fechaEmision) " +
            "ORDER BY FUNCTION('WEEKDAY', e.fechaEmision)",
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

        // MySQL WEEKDAY returns 0=Monday, 6=Sunday; we want 1=Monday...7=Sunday
        for (Object[] row : results) {
            int weekday = (Integer) row[0];
            BigDecimal total = (BigDecimal) row[1];
            dayOfWeekSales.put(weekday + 1, total != null ? total : BigDecimal.ZERO);
        }
        return dayOfWeekSales;
    }

    @Transactional
    public Map<String, BigDecimal> getSalesByDepartment(Date start, Date end) {
        List<ReportesFamiliasYDepartamentos> reportes = inventarioService.getTotalSalesByDepartamento(start, end);
        Map<String, BigDecimal> deptSales = new LinkedHashMap<>();
        if (reportes != null) {
            for (ReportesFamiliasYDepartamentos r : reportes) {
                deptSales.put(r.getNombre(), r.getCantidad());
            }
        }
        return deptSales;
    }

    @Transactional
    public Map<String, BigDecimal> getSalesByFamily(Date start, Date end) {
        List<ReportesFamiliasYDepartamentos> reportes = inventarioService.getTotalSalesByFamilia(start, end);
        Map<String, BigDecimal> familySales = new LinkedHashMap<>();
        if (reportes != null) {
            for (ReportesFamiliasYDepartamentos r : reportes) {
                familySales.put(r.getNombre(), r.getCantidad());
            }
        }
        return familySales;
    }

    @Transactional
    public List<Object[]> getDailySales(Date start, Date end) {
        LocalDateTime startLdt = toLocalDateTime(start);
        LocalDateTime endLdt = toLocalDateTime(end);

        return em.createQuery(
            "SELECT FUNCTION('DATE', e.fechaEmision), COALESCE(SUM(r.totalComprobante), 0) " +
            "FROM ComprobantesEmitidos f " +
            "JOIN f.encabezado e " +
            "JOIN f.resumen r " +
            "WHERE f.status = true " +
            "AND e.fechaEmision BETWEEN :start AND :end " +
            "GROUP BY FUNCTION('DATE', e.fechaEmision) " +
            "ORDER BY FUNCTION('DATE', e.fechaEmision)",
            Object[].class
        )
        .setParameter("start", startLdt)
        .setParameter("end", endLdt)
        .getResultList();
    }

    private LocalDateTime toLocalDateTime(Date date) {
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
    }
}
