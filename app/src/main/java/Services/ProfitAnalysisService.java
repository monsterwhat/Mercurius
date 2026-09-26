package Services;

import Models.Articulos.Articulos;
import Models.Departamento;
import Models.Familia;
import Models.HistorialMargen;
import Models.CorteMargen;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for analyzing profit margins with historical tracking
 */
@Named
@ApplicationScoped
public class ProfitAnalysisService extends GService<HistorialMargen> {

    @Inject @Nonnull
    private EntityManager em;

    @Override
    protected Class<HistorialMargen> getEntityClass() {
        return HistorialMargen.class;
    }

    /**
     * Calculate real-time profit margin for an article
     * Real margin = (selling_price - cost_price) / selling_price * 100
     */
    @Transactional(TxType.SUPPORTS)
    public @Nonnull BigDecimal calculateRealMargin(@Nullable Articulos articulo) {
        if (articulo == null || articulo.getLastPrecio() == null) {
            return BigDecimal.ZERO;
        }

        BigDecimal precioVenta = articulo.getLastPrecio().getPrecioFinal();
        BigDecimal precioCosto = articulo.getLastPrecio().getPrecioCostoSinIVA();

        if (precioVenta == null || precioCosto == null || precioVenta.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        return precioVenta.subtract(precioCosto)
                .divide(precioVenta, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Record daily profit margin history for an article
     */
    @Transactional
    public void recordDailyMarginHistory(@Nullable Articulos articulo, @Nullable Integer cantidadVendida) {
        if (articulo == null || articulo.getLastPrecio() == null || cantidadVendida == null || cantidadVendida <= 0) {
            return;
        }

        // Check if already recorded today
        Date today = new Date();
        Calendar cal = Calendar.getInstance();
        cal.setTime(today);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        Date startOfDay = cal.getTime();

        cal.add(Calendar.DAY_OF_MONTH, 1);
        Date endOfDay = cal.getTime();

        // Check if already exists for today
        String jpql = "SELECT pmh FROM HistorialMargen pmh WHERE pmh.articulo.codigo = :articuloId AND pmh.fecha >= :startOfDay AND pmh.fecha < :endOfDay";
        TypedQuery<HistorialMargen> query = em.createQuery(jpql, HistorialMargen.class)
                .setParameter("articuloId", articulo.getCodigo())
                .setParameter("startOfDay", startOfDay)
                .setParameter("endOfDay", endOfDay);

        try {
            HistorialMargen existing = query.getSingleResult();
            // Update existing record
            existing.setCantidadVendida(existing.getCantidadVendida() + cantidadVendida);
            existing.setTotalIngresos(existing.getTotalIngresos().add(
                    articulo.getLastPrecio().getPrecioFinal().multiply(BigDecimal.valueOf(cantidadVendida))));
            em.merge(existing);
        } catch (NoResultException e) {
            // Create new record
            HistorialMargen history = new HistorialMargen();
            history.setArticulo(articulo);
            history.setFecha(startOfDay);
            history.setPrecioCosto(articulo.getLastPrecio().getPrecioCostoSinIVA());
            history.setPrecioVenta(articulo.getLastPrecio().getPrecioFinal());
            history.setPorcentajeUtilidad(articulo.getLastPrecio().getPorcentajeUtilidad());
            history.setPrecioConUtilidad(articulo.getLastPrecio().getPrecioConUtilidad());
            
            BigDecimal margenReal = calculateRealMargin(articulo);
            history.setMargenReal(margenReal);
            
            history.setCantidadVendida(cantidadVendida);
            history.setTotalIngresos(articulo.getLastPrecio().getPrecioFinal().multiply(BigDecimal.valueOf(cantidadVendida)));
            
            em.persist(history);
        }
    }

    /**
     * Create daily snapshot of profit margins by department and family
     */
    @Transactional
    public void createDailySnapshot(@Nonnull Date snapshotDate) {
        // Get all departments with their profit margins
        String deptJpql = "SELECT a.departamento.nombre, AVG(pmh.margenReal), SUM(pmh.totalIngresos), SUM(pmh.totalIngresos * pmh.margenReal / 100), COUNT(pmh.id) " +
                        "FROM HistorialMargen pmh " +
                        "JOIN pmh.articulo a " +
                        "JOIN a.departamento d " +
                        "WHERE pmh.fecha = :snapshotDate " +
                        "GROUP BY a.departamento.nombre";
        
        Query deptQuery = em.createQuery(deptJpql)
                .setParameter("snapshotDate", snapshotDate);
        
        @SuppressWarnings("unchecked")
        List<Object[]> deptResults = deptQuery.getResultList();
        
        for (Object[] result : deptResults) {
            String departamento = (String) result[0];
            BigDecimal margenPromedio = (BigDecimal) result[1];
            BigDecimal totalVentas = (BigDecimal) result[2];
            BigDecimal totalUtilidad = (BigDecimal) result[3];
            Long cantidadArticulos = (Long) result[4];
            
            CorteMargen snapshot = new CorteMargen();
            snapshot.setFechaSnapshot(snapshotDate);
            snapshot.setDepartamento(departamento);
            snapshot.setMargenPromedio(margenPromedio);
            snapshot.setTotalVentas(totalVentas);
            snapshot.setTotalUtilidad(totalUtilidad);
            snapshot.setCantidadArticulos(cantidadArticulos.intValue());
            
            em.persist(snapshot);
        }
        
        // Get all families with their profit margins
        String familyJpql = "SELECT a.familia.nombre, AVG(pmh.margenReal), SUM(pmh.totalIngresos), SUM(pmh.totalIngresos * pmh.margenReal / 100), COUNT(pmh.id) " +
                           "FROM HistorialMargen pmh " +
                           "JOIN pmh.articulo a " +
                           "JOIN a.familia f " +
                           "WHERE pmh.fecha = :snapshotDate " +
                           "GROUP BY a.familia.nombre";
        
        Query familyQuery = em.createQuery(familyJpql)
                .setParameter("snapshotDate", snapshotDate);
        
        @SuppressWarnings("unchecked")
        List<Object[]> familyResults = familyQuery.getResultList();
        
        for (Object[] result : familyResults) {
            String familia = (String) result[0];
            BigDecimal margenPromedio = (BigDecimal) result[1];
            BigDecimal totalVentas = (BigDecimal) result[2];
            BigDecimal totalUtilidad = (BigDecimal) result[3];
            Long cantidadArticulos = (Long) result[4];
            
            CorteMargen snapshot = new CorteMargen();
            snapshot.setFechaSnapshot(snapshotDate);
            snapshot.setFamilia(familia);
            snapshot.setMargenPromedio(margenPromedio);
            snapshot.setTotalVentas(totalVentas);
            snapshot.setTotalUtilidad(totalUtilidad);
            snapshot.setCantidadArticulos(cantidadArticulos.intValue());
            
            em.persist(snapshot);
        }
    }

    /**
     * Get profit margin history for an article within date range
     */
    @Transactional(TxType.SUPPORTS)
    public @Nonnull List<HistorialMargen> getArticleMarginHistory(@Nonnull Articulos articulo, @Nonnull Date startDate, @Nonnull Date endDate) {
        String jpql = "SELECT pmh FROM HistorialMargen pmh WHERE pmh.articulo.codigo = :articuloId AND pmh.fecha BETWEEN :startDate AND :endDate ORDER BY pmh.fecha DESC";
        TypedQuery<HistorialMargen> query = em.createQuery(jpql, HistorialMargen.class)
                .setParameter("articuloId", articulo.getCodigo())
                .setParameter("startDate", startDate)
                .setParameter("endDate", endDate);
        return query.getResultList();
    }

    /**
     * Get profit margin trend for department or family
     */
    @Transactional(TxType.SUPPORTS)
    public @Nonnull List<CorteMargen> getMarginTrend(@Nonnull String name, @Nonnull String type, @Nonnull Date startDate, @Nonnull Date endDate) {
        String jpql;
        if ("department".equals(type)) {
            jpql = "SELECT pms FROM CorteMargen pms WHERE pms.departamento = :name AND pms.fechaSnapshot BETWEEN :startDate AND :endDate ORDER BY pms.fechaSnapshot DESC";
        } else {
            jpql = "SELECT pms FROM CorteMargen pms WHERE pms.familia = :name AND pms.fechaSnapshot BETWEEN :startDate AND :endDate ORDER BY pms.fechaSnapshot DESC";
        }
        
        TypedQuery<CorteMargen> query = em.createQuery(jpql, CorteMargen.class)
                .setParameter("name", name)
                .setParameter("startDate", startDate)
                .setParameter("endDate", endDate);
        return query.getResultList();
    }

    /**
     * Get top performing articles by profit margin
     */
    @Transactional(TxType.SUPPORTS)
    public @Nonnull List<Articulos> getTopProfitMarginArticles(int limit, @Nonnull Date startDate, @Nonnull Date endDate) {
        String jpql = "SELECT a FROM Articulos a WHERE a.codigo IN " +
                "(SELECT pmh.articulo.codigo FROM HistorialMargen pmh " +
                "WHERE pmh.fecha BETWEEN :startDate AND :endDate " +
                "ORDER BY pmh.margenReal DESC)";
        TypedQuery<Articulos> query = em.createQuery(jpql, Articulos.class)
                .setParameter("startDate", startDate)
                .setParameter("endDate", endDate)
                .setMaxResults(limit);
        return query.getResultList();
    }

    /**
     * Get worst performing articles by profit margin
     */
    @Transactional(TxType.SUPPORTS)
    public @Nonnull List<Articulos> getWorstProfitMarginArticles(int limit, @Nonnull Date startDate, @Nonnull Date endDate) {
        String jpql = "SELECT a FROM Articulos a WHERE a.codigo IN " +
                "(SELECT pmh.articulo.codigo FROM HistorialMargen pmh " +
                "WHERE pmh.fecha BETWEEN :startDate AND :endDate AND pmh.margenReal > 0 " +
                "ORDER BY pmh.margenReal ASC)";
        TypedQuery<Articulos> query = em.createQuery(jpql, Articulos.class)
                .setParameter("startDate", startDate)
                .setParameter("endDate", endDate)
                .setMaxResults(limit);
        return query.getResultList();
    }

    /**
     * Get average profit margin for all articles
     */
    @Transactional(TxType.SUPPORTS)
    public @Nonnull BigDecimal getAverageProfitMargin(@Nonnull Date startDate, @Nonnull Date endDate) {
        String jpql = "SELECT AVG(pmh.margenReal) FROM HistorialMargen pmh WHERE pmh.fecha BETWEEN :startDate AND :endDate";
        TypedQuery<Double> query = em.createQuery(jpql, Double.class)
                .setParameter("startDate", startDate)
                .setParameter("endDate", endDate);
        
        try {
            Double avg = query.getSingleResult();
            return avg != null ? BigDecimal.valueOf(avg).setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        } catch (NoResultException e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * Get profit margin comparison across departments
     */
    @Transactional(TxType.SUPPORTS)
    public @Nonnull Map<String, BigDecimal> getDepartmentMarginComparison(@Nonnull Date startDate, @Nonnull Date endDate) {
        String jpql = "SELECT pms.departamento, AVG(pms.margenPromedio) FROM CorteMargen pms WHERE pms.departamento IS NOT NULL AND pms.fechaSnapshot BETWEEN :startDate AND :endDate GROUP BY pms.departamento ORDER BY AVG(pms.margenPromedio) DESC";
        TypedQuery<Object[]> query = em.createQuery(jpql, Object[].class)
                .setParameter("startDate", startDate)
                .setParameter("endDate", endDate);
        
        List<Object[]> results = query.getResultList();
        return results.stream().collect(
            Collectors.toMap(
                result -> (String) result[0],
                result -> result[1] != null ? ((BigDecimal) result[1]).setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO
            )
        );
    }
}