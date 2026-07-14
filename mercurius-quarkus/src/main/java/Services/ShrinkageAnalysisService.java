package Services;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import Models.Inventario;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TypedQuery;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for analyzing inventory shrinkage (losses from theft, damage, spoilage, etc.)
 */
@Named
@ApplicationScoped
public class ShrinkageAnalysisService {

    private static final String[] SHRINKAGE_TYPES = {"Merma", "Perdida/Robo", "Vencimiento", "Daño"};

    @PersistenceContext
    @Nonnull
    private EntityManager em;

    /**
     * Get total shrinkage quantity for a date range.
     * Sum of cantidad from Inventario where tipoMovimiento is a shrinkage type.
     */
    @Nonnull
    public BigDecimal getTotalShrinkage(@Nonnull Date start, @Nonnull Date end) {
        try {
            String jpql = "SELECT SUM(i.cantidad) FROM Inventario i " +
                          "WHERE i.tipoMovimiento IN ('Merma', 'Perdida/Robo', 'Vencimiento', 'Daño') " +
                          "AND i.fechaMovimiento BETWEEN :start AND :end";
            TypedQuery<BigDecimal> query = em.createQuery(jpql, BigDecimal.class);
            query.setParameter("start", start);
            query.setParameter("end", end);
            BigDecimal result = query.getSingleResult();
            return result != null ? result : BigDecimal.ZERO;
        } catch (PersistenceException e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * Get shrinkage grouped by cause (tipoMovimiento).
     * Returns map of cause -> total quantity.
     */
    @Nonnull
    public Map<String, BigDecimal> getShrinkageByCause(@Nonnull Date start, @Nonnull Date end) {
        Map<String, BigDecimal> resultMap = new LinkedHashMap<>();
        try {
            String jpql = "SELECT i.tipoMovimiento, SUM(i.cantidad) FROM Inventario i " +
                          "WHERE i.tipoMovimiento IN ('Merma', 'Perdida/Robo', 'Vencimiento', 'Daño') " +
                          "AND i.fechaMovimiento BETWEEN :start AND :end " +
                          "GROUP BY i.tipoMovimiento " +
                          "ORDER BY i.tipoMovimiento";
            List<Object[]> results = em.createQuery(jpql, Object[].class)
                    .setParameter("start", start)
                    .setParameter("end", end)
                    .getResultList();

            for (Object[] row : results) {
                String tipo = (String) row[0];
                BigDecimal total = (BigDecimal) row[1];
                resultMap.put(tipo, total != null ? total : BigDecimal.ZERO);
            }

            // Ensure all shrinkage types appear even with zero totals
            for (String type : SHRINKAGE_TYPES) {
                resultMap.putIfAbsent(type, BigDecimal.ZERO);
            }
        } catch (PersistenceException e) {
            for (String type : SHRINKAGE_TYPES) {
                resultMap.put(type, BigDecimal.ZERO);
            }
        }
        return resultMap;
    }

    /**
     * Get shrinkage grouped by department.
     * Returns map of department name -> total quantity.
     */
    @Nonnull
    public Map<String, BigDecimal> getShrinkageByDepartment(@Nonnull Date start, @Nonnull Date end) {
        Map<String, BigDecimal> resultMap = new LinkedHashMap<>();
        try {
            String jpql = "SELECT d.nombre, SUM(i.cantidad) FROM Inventario i " +
                          "JOIN i.articulo a " +
                          "JOIN a.departamento d " +
                          "WHERE i.tipoMovimiento IN ('Merma', 'Perdida/Robo', 'Vencimiento', 'Daño') " +
                          "AND i.fechaMovimiento BETWEEN :start AND :end " +
                          "GROUP BY d.nombre " +
                          "ORDER BY SUM(i.cantidad) DESC";
            List<Object[]> results = em.createQuery(jpql, Object[].class)
                    .setParameter("start", start)
                    .setParameter("end", end)
                    .getResultList();

            for (Object[] row : results) {
                String dept = (String) row[0];
                BigDecimal total = (BigDecimal) row[1];
                resultMap.put(dept, total != null ? total.abs() : BigDecimal.ZERO);
            }
        } catch (PersistenceException e) {
            // Return empty map on error
        }
        return resultMap;
    }

    /**
     * Calculate shrinkage percentage relative to total inventory movement.
     * (total shrinkage / total inventory movement) * 100
     */
    @Nonnull
    public BigDecimal getShrinkagePercentage(@Nonnull Date start, @Nonnull Date end) {
        try {
            BigDecimal totalShrinkage = getTotalShrinkage(start, end);

            // Total inventory movement (absolute sum of all quantities)
            String jpql = "SELECT SUM(ABS(i.cantidad)) FROM Inventario i " +
                          "WHERE i.fechaMovimiento BETWEEN :start AND :end";
            TypedQuery<BigDecimal> query = em.createQuery(jpql, BigDecimal.class);
            query.setParameter("start", start);
            query.setParameter("end", end);
            BigDecimal totalMovement = query.getSingleResult();

            if (totalMovement == null || totalMovement.compareTo(BigDecimal.ZERO) == 0) {
                return BigDecimal.ZERO;
            }

            BigDecimal absShrinkage = totalShrinkage.abs();
            return absShrinkage.divide(totalMovement, 4, RoundingMode.HALF_UP)
                               .multiply(BigDecimal.valueOf(100))
                               .setScale(2, RoundingMode.HALF_UP);
        } catch (PersistenceException e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * Get detailed shrinkage movements for the date range.
     */
    @Nonnull
    public List<Inventario> getShrinkageMovements(@Nonnull Date start, @Nonnull Date end) {
        try {
            String jpql = "SELECT i FROM Inventario i " +
                          "WHERE i.tipoMovimiento IN ('Merma', 'Perdida/Robo', 'Vencimiento', 'Daño') " +
                          "AND i.fechaMovimiento BETWEEN :start AND :end " +
                          "ORDER BY i.fechaMovimiento DESC";
            TypedQuery<Inventario> query = em.createQuery(jpql, Inventario.class);
            query.setParameter("start", start);
            query.setParameter("end", end);
            return query.getResultList();
        } catch (PersistenceException e) {
            return List.of();
        }
    }

    /**
     * Get total inventory movement (all types) for the date range.
     */
    @Nonnull
    public BigDecimal getTotalInventoryMovement(@Nonnull Date start, @Nonnull Date end) {
        try {
            String jpql = "SELECT SUM(ABS(i.cantidad)) FROM Inventario i " +
                          "WHERE i.fechaMovimiento BETWEEN :start AND :end";
            TypedQuery<BigDecimal> query = em.createQuery(jpql, BigDecimal.class);
            query.setParameter("start", start);
            query.setParameter("end", end);
            BigDecimal result = query.getSingleResult();
            return result != null ? result : BigDecimal.ZERO;
        } catch (PersistenceException e) {
            return BigDecimal.ZERO;
        }
    }
}
