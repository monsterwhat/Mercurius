package Services;

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

@Named
@ApplicationScoped
public class DashboardService extends GService<ComprobantesEmitidos> {
    
    @Override
    protected Class<ComprobantesEmitidos> getEntityClass() {
        return ComprobantesEmitidos.class;
    }
    
    @PersistenceContext
    private EntityManager em;
    
    public BigDecimal getTodaySales(Users user) {
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
            ).setParameter("user", user)
             .setParameter("startOfDay", startOfDay)
             .setParameter("endOfDay", endOfDay)
             .getSingleResult();
            
            return result;
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error getting today sales: " + e.getMessage(), null, 0, "DashboardService.getTodaySales()", null, e.getMessage());
            return BigDecimal.ZERO;
        }
    }
    
    public int getTransactionCount(Users user) {
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
            ).setParameter("user", user)
             .setParameter("startOfDay", startOfDay)
             .setParameter("endOfDay", endOfDay)
             .getSingleResult();
            
            return result.intValue();
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error getting transaction count: " + e.getMessage(), null, 0, "DashboardService.getTransactionCount()", null, e.getMessage());
            return 0;
        }
    }
    
    public int getItemsSold(Users user) {
        LocalDate today = LocalDate.now();
        LocalDateTime startOfDay = today.atStartOfDay();
        LocalDateTime endOfDay = today.atTime(23, 59, 59);
        
        try {
            Long result = em.createQuery(
                "SELECT COALESCE(SUM(d.cantidad), 0) " +
                "FROM ComprobantesEmitidos f " +
                "JOIN f.detalles d " +
                "JOIN f.encabezado e " +
                "WHERE f.user = :user " +
                "AND f.status = true " +
                "AND e.fechaEmision BETWEEN :startOfDay AND :endOfDay",
                Long.class
            ).setParameter("user", user)
             .setParameter("startOfDay", startOfDay)
             .setParameter("endOfDay", endOfDay)
             .getSingleResult();
            
            return result.intValue();
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error getting items sold: " + e.getMessage(), null, 0, "DashboardService.getItemsSold()", null, e.getMessage());
            return 0;
        }
    }
    
    public ComprobantesEmitidos getLastTransaction(Users user) {
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
            query.setParameter("user", user);
            query.setMaxResults(1);
            return query.getSingleResult();
        } catch (NoResultException e) {
            return null;
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error finding last transaction: " + e.getMessage(), null, 0, "DashboardService.getLastTransaction()", null, e.getMessage());
            return null;
        }
    }
    
    public List<ComprobantesEmitidos> getRecentSales(Users user, int limit) {
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
            query.setParameter("user", user);
            query.setMaxResults(limit);
            return query.getResultList();
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error getting recent sales: " + e.getMessage(), null, 0, "DashboardService.getRecentSales()", null, e.getMessage());
            return null;
        }
    }
}