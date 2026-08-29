package Services;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import Models.ComprobantesEmitidos;
import Models.Users;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.jboss.logging.Logger;

@Named
@ApplicationScoped
public class DashboardService extends GService<ComprobantesEmitidos> {
    
    private static final Logger LOG = Logger.getLogger(DashboardService.class);

    @Override
    @Nonnull
    protected Class<ComprobantesEmitidos> getEntityClass() {
        return ComprobantesEmitidos.class;
    }
    
    @PersistenceContext @Nonnull
    private EntityManager em;
    
    @Nonnull
    public BigDecimal getTodaySales(@Nonnull Users user) {
        LocalDate today = LocalDate.now();
        LocalDateTime startOfDay = today.atStartOfDay();
        LocalDateTime endOfDay = today.atTime(23, 59, 59);
        
        try {
            BigDecimal result = em.createQuery(
                "SELECT COALESCE(SUM(r.totalComprobante), 0) " +
                "FROM ComprobantesEmitidos f " +
                "JOIN f.resumen r " +
                "JOIN f.encabezado e " +
                "WHERE f.user = :user " +
                "AND f.status = true " +
                "AND e.fechaEmision BETWEEN :startOfDay AND :endOfDay",
                BigDecimal.class
            ).setParameter("user", user.getUsername())
             .setParameter("startOfDay", startOfDay)
             .setParameter("endOfDay", endOfDay)
             .getSingleResult();
            
            return result;
        } catch (jakarta.persistence.PersistenceException e) {
            LOG.warn("Error getting today sales: " + e.getMessage() + " | source=DashboardService.getTodaySales() | despues=" + e.getMessage());
            return BigDecimal.ZERO;
        }
    }
    
    public int getTransactionCount(@Nonnull Users user) {
        LocalDate today = LocalDate.now();
        LocalDateTime startOfDay = today.atStartOfDay();
        LocalDateTime endOfDay = today.atTime(23, 59, 59);
        
        try {
            Long result = em.createQuery(
                "SELECT COUNT(f) " +
                "FROM ComprobantesEmitidos f " +
                "JOIN f.encabezado e " +
                "WHERE f.user = :user " +
                "AND f.status = true " +
                "AND e.fechaEmision BETWEEN :startOfDay AND :endOfDay",
                Long.class
            ).setParameter("user", user.getUsername())
             .setParameter("startOfDay", startOfDay)
             .setParameter("endOfDay", endOfDay)
             .getSingleResult();
            
            return result.intValue();
        } catch (jakarta.persistence.PersistenceException e) {
            LOG.warn("Error getting transaction count: " + e.getMessage() + " | source=DashboardService.getTransactionCount() | despues=" + e.getMessage());
            return 0;
        }
    }
    
    public int getItemsSold(@Nonnull Users user) {
        LocalDate today = LocalDate.now();
        LocalDateTime startOfDay = today.atStartOfDay();
        LocalDateTime endOfDay = today.atTime(23, 59, 59);
        
        try {
            Number result = em.createQuery(
                "SELECT COALESCE(SUM(ld.cantidad), 0) " +
                "FROM ComprobantesEmitidos f " +
                "JOIN f.detalles d " +
                "JOIN d.lineasDetalle ld " +
                "JOIN f.encabezado e " +
                "WHERE f.user = :user " +
                "AND f.status = true " +
                "AND e.fechaEmision BETWEEN :startOfDay AND :endOfDay",
                Number.class
            ).setParameter("user", user.getUsername())
             .setParameter("startOfDay", startOfDay)
             .setParameter("endOfDay", endOfDay)
             .getSingleResult();
            
            return result == null ? 0 : result.intValue();
        } catch (jakarta.persistence.PersistenceException e) {
            LOG.warn("Error getting items sold: " + e.getMessage() + " | source=DashboardService.getItemsSold() | despues=" + e.getMessage());
            return 0;
        }
    }
    
    @Nullable
    public ComprobantesEmitidos getLastTransaction(@Nonnull Users user) {
        try {
            TypedQuery<ComprobantesEmitidos> query = em.createQuery(
                "SELECT f FROM ComprobantesEmitidos f " +
                "LEFT JOIN FETCH f.encabezado e " +
                "LEFT JOIN FETCH f.resumen r " +
                "LEFT JOIN FETCH f.detalles d " +
                "WHERE f.user = :user " +
                "AND f.status = true " +
                "ORDER BY e.fechaEmision DESC",
                ComprobantesEmitidos.class
            );
            query.setParameter("user", user.getUsername());
            query.setMaxResults(1);
            return query.getSingleResult();
        } catch (NoResultException e) {
            return null;
        } catch (jakarta.persistence.PersistenceException e) {
            LOG.warn("Error finding last transaction: " + e.getMessage() + " | source=DashboardService.getLastTransaction() | despues=" + e.getMessage());
            return null;
        }
    }
    
    @Nullable
    public List<ComprobantesEmitidos> getRecentSales(@Nonnull Users user, int limit) {
        try {
            TypedQuery<ComprobantesEmitidos> query = em.createQuery(
                "SELECT f FROM ComprobantesEmitidos f " +
                "LEFT JOIN FETCH f.encabezado e " +
                "LEFT JOIN FETCH f.resumen r " +
                "WHERE f.user = :user " +
                "AND f.status = true " +
                "ORDER BY e.fechaEmision DESC",
                ComprobantesEmitidos.class
            );
            query.setParameter("user", user.getUsername());
            query.setMaxResults(limit);
            return query.getResultList();
        } catch (jakarta.persistence.PersistenceException e) {
            LOG.warn("Error getting recent sales: " + e.getMessage() + " | source=DashboardService.getRecentSales() | despues=" + e.getMessage());
            return null;
        }
    }
}