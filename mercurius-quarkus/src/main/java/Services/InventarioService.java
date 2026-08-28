package Services;

import Models.Articulos.ArticuloStock; 
import Models.Inventario;
import Models.ReportesFamiliasYDepartamentos;
import Models.Users;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Calendar;
import jakarta.transaction.Transactional;
import java.util.Date; 
import java.util.List; 
import java.util.logging.Logger;

@Named
@ApplicationScoped
public class InventarioService extends GService<Inventario> {
        
    private static final Logger LOG = Logger.getLogger(InventarioService.class.getName());

    @Override
    protected @Nonnull Class<Inventario> getEntityClass() {
        return Inventario.class;
    }

    @PostConstruct
    public void init() {
    }

    @Override
    @Transactional
    public void create(@Nonnull Inventario entity) {
        try {
            em.persist(entity);
            em.flush();
        } catch (PersistenceException e) {
            LOG.log(java.util.logging.Level.WARNING, "Error creating entity: " + e.getMessage() + " | source=InventarioService.create() | despues=" + e.getMessage());
        }
    }
    
    public void createWithStock(@Nonnull Inventario entity) {
        try {
            em.persist(entity);
            em.flush();
            updateStock(entity);
            em.flush();
        } catch (PersistenceException e) {
            LOG.log(java.util.logging.Level.WARNING, "Error creating entity: " + e.getMessage() + " | source=InventarioService.createWithStock() | despues=" + e.getMessage());
        }
    }

    @Override
    public void delete(@Nonnull Inventario entity) {
        try {
            if (!em.contains(entity)) {
                entity = em.find(getEntityClass(), entity.getCodigo());
            }

            if (entity != null) {
                em.remove(entity);
                em.flush();
            } else {
                LOG.info("Entity not found for deletion | source=InventarioService.delete()");
            }
        } catch (PersistenceException e) {
            LOG.log(java.util.logging.Level.WARNING, "Error deleting " + getEntityClass().getSimpleName() + " : " + e.getMessage() + " | source=InventarioService.delete() | despues=" + e.getMessage());
        }
    }

    @Override
    public void update(@Nonnull Inventario entity) {
        try {
            em.merge(entity);
            em.flush();
            updateStock(entity);
            em.flush();
        } catch (PersistenceException e) {
            LOG.log(java.util.logging.Level.WARNING, "Error updating entity: " + e.getMessage() + " | source=InventarioService.update() | despues=" + e.getMessage());
        }
    }
    
    public void markAsProcessed(@Nonnull Inventario entity) {
        try {
            Inventario existingItem = em.find(getEntityClass(), entity.getCodigo());
            if (existingItem != null) {
                existingItem.setProcessed(true);
                existingItem.setStatus(true);
                existingItem.setUsuario(entity.getUsuario());
                existingItem.setCantidad(entity.getCantidad());
                existingItem.setUnidadesRecomendadasFactura(entity.getUnidadesRecomendadasFactura());
                existingItem.setTipoMovimiento(entity.getTipoMovimiento());
                existingItem.setFechaMovimiento(entity.getFechaMovimiento());
                existingItem.setNotas(entity.getNotas());
                em.merge(existingItem);
                em.flush();
                updateStock(existingItem);
                em.flush();
            } else {
                LOG.info("Entity not found for markAsProcessed | source=InventarioService.markAsProcessed()");
            }
        } catch (PersistenceException e) {
            LOG.log(java.util.logging.Level.WARNING, "Error marking as processed: " + e.getMessage() + " | source=InventarioService.markAsProcessed() | despues=" + e.getMessage());
        }
    }

    @Override
    public @Nullable List<Inventario> listAll() {
        try {
            TypedQuery<Inventario> query = em.createQuery("SELECT a FROM Inventario a", Inventario.class);
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.log(java.util.logging.Level.WARNING, "Error listing all entities: " + e.getMessage() + " | source=InventarioService.listAll() | despues=" + e.getMessage());
            return null;
        }
    }
    
    public @Nullable List<Inventario> ListAllEnabled() {
        try {
            TypedQuery<Inventario> query = em.createQuery("SELECT a FROM Inventario a WHERE a.status = true", Inventario.class);
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.log(java.util.logging.Level.WARNING, "Error listing all enabled entities: " + e.getMessage() + " | source=InventarioService.ListAllEnabled() | despues=" + e.getMessage());
            return null;
        }
    }

    public void softDelete(Inventario entity) {
        try {
            // Find the item by its ID
            Inventario existingItem = em.find(getEntityClass(), entity.getCodigo());

            if (existingItem != null) {
                // Soft delete the item by setting its status to false
                existingItem.setStatus(false);
                em.merge(existingItem);
            em.flush();
            } else {
                LOG.info("Entity not found for softDelete | source=InventarioService.softDelete()");
            }
        } catch (PersistenceException e) {
            LOG.log(java.util.logging.Level.WARNING, "Error soft deleting entity: " + e.getMessage() + " | source=InventarioService.softDelete() | despues=" + e.getMessage());
        }
    }
    
    public double getStock(String barcode) {
        try {
            // Query to find the ArticuloStock entity by barcode
            TypedQuery<BigDecimal> query = em.createQuery(
                "SELECT a.stock FROM ArticuloStock a WHERE a.codigoBarra = :barcode", 
                BigDecimal.class
            );
            query.setParameter("barcode", barcode);

            // Get the result list
            List<BigDecimal> results = query.getResultList();

            // Return the stock as double
            if (!results.isEmpty()) {
                return results.get(0).doubleValue();
            } else {
                return 0.0; // Return 0 if no stock found
            }
        } catch (PersistenceException e) {
            LOG.log(java.util.logging.Level.WARNING, "Error getting stock for barcode: " + barcode + " - " + e.getMessage() + " | source=InventarioService.getStock() | despues=" + e.getMessage());
            return 0.0;
        }
    }

    public double calculateTotalStockForItemByBarcode(String barcode) {
        try {
            // Query to sum up the quantities of inventory movements for items with the given barcode
            String queryString = "SELECT SUM(i.cantidad) FROM Inventario i WHERE i.articulo.codigoBarra = :barcode AND i.articulo.status = true AND i.articulo.processed = true";
            BigDecimal result = em.createQuery(queryString, BigDecimal.class)
                             .setParameter("barcode", barcode)
                             .getSingleResult();
            
            if(result != null){
                return result.doubleValue();
            }else{
                return 0.0;
            }
        } catch (PersistenceException e) {
            LOG.log(java.util.logging.Level.WARNING, "Error calculating total stock for item by barcode: " + e.getMessage() + " | source=InventarioService.calculateTotalStockForItemByBarcode() | despues=" + e.getMessage());
            return 0.0;
        }
    }

    public List<Inventario> listAllSinProcesar() {
        try {
            TypedQuery<Inventario> query = em.createQuery("SELECT a FROM Inventario a WHERE a.status = true AND a.processed = false", Inventario.class);
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.log(java.util.logging.Level.WARNING, "Error listing all entities: " + e.getMessage() + " | source=InventarioService.listAllSinProcesar() | despues=" + e.getMessage());
            return null;
        }
    }

    public List<Inventario> listAllActivosYProcesados() {
    try {
            TypedQuery<Inventario> query = em.createQuery("SELECT a FROM Inventario a WHERE a.status = true AND a.processed = true", Inventario.class);
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.log(java.util.logging.Level.WARNING, "Error listing all entities: " + e.getMessage() + " | source=InventarioService.listAllActivosYProcesados() | despues=" + e.getMessage());
            return null;
        }    
    }

    public List<Inventario> listAllInactivos() {
    try {
            TypedQuery<Inventario> query = em.createQuery("SELECT a FROM Inventario a WHERE a.status = false", Inventario.class);
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.log(java.util.logging.Level.WARNING, "Error listing all entities: " + e.getMessage() + " | source=InventarioService.listAllInactivos() | despues=" + e.getMessage());
            return null;
        }     
    }

    public List<Inventario> findByDateRangeAndUserId(Date startDate, Date endDate, Long userId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Inventario> cq = cb.createQuery(Inventario.class);
        Root<Inventario> inventario = cq.from(Inventario.class);

        Predicate datePredicate = cb.between(inventario.get("fechaMovimiento"), startDate, endDate);
        Predicate userPredicate = cb.equal(inventario.get("usuario").get("id"), userId);

        cq.where(cb.and(datePredicate, userPredicate));

        return em.createQuery(cq).getResultList();
    }
    
    public List<Inventario> findVentasByDateRangeAndUserId(Date startDate, Date endDate, Long userId) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Inventario> cq = cb.createQuery(Inventario.class);
        Root<Inventario> inventario = cq.from(Inventario.class);

        Predicate datePredicate = cb.between(inventario.get("fechaMovimiento"), startDate, endDate);
        Predicate userPredicate = cb.equal(inventario.get("usuario").get("id"), userId);

        Predicate tipoMovimientoPredicate = cb.equal(inventario.get("tipoMovimiento"), "Venta");        
        
        cq.where(cb.and(datePredicate, userPredicate, tipoMovimientoPredicate));
        
        return em.createQuery(cq).getResultList();
    }
    
    public Date getStartOfDay(Date date) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTime();
    }

    public Date getEndOfDay(Date date) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(date);
        calendar.set(Calendar.HOUR_OF_DAY, 23);
        calendar.set(Calendar.MINUTE, 59);
        calendar.set(Calendar.SECOND, 59);
        calendar.set(Calendar.MILLISECOND, 999);
        return calendar.getTime();
    }

    public List<Inventario> findByDateAndUserId(Date date, Long userId) {
        Date startOfDay = getStartOfDay(date);
        Date endOfDay = getEndOfDay(date);

        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Inventario> cq = cb.createQuery(Inventario.class);
        Root<Inventario> inventario = cq.from(Inventario.class);

        Predicate datePredicate = cb.between(inventario.get("fechaMovimiento"), startOfDay, endOfDay);
        Predicate userPredicate = cb.equal(inventario.get("usuario").get("id"), userId);

        cq.where(cb.and(datePredicate, userPredicate));

    return em.createQuery(cq).getResultList();
    }

    public List<Inventario> findInventariosAfterDate(Date fecha) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Inventario> cq = cb.createQuery(Inventario.class);
        Root<Inventario> inventario = cq.from(Inventario.class);

        Predicate datePredicate = cb.greaterThan(inventario.get("fechaMovimiento"), fecha);
        cq.where(datePredicate);

        TypedQuery<Inventario> query = em.createQuery(cq);
        return query.getResultList();
    }

    public Long countActivos() {
        try {
            TypedQuery<Long> query = em.createQuery("SELECT COUNT(e) FROM Inventario e WHERE e.status = true", Long.class);
            return query.getSingleResult();
        } catch (PersistenceException e) {
            LOG.log(java.util.logging.Level.WARNING, "Error counting "+ getEntityClass().getSimpleName() +" : " + e.getMessage() + " | source=InventarioService.count() | despues=" + e.getMessage());
            return 0l;
        }
    }
    
    public Long countInactivos() {
        try {
            TypedQuery<Long> query = em.createQuery("SELECT COUNT(e) FROM Inventario e WHERE e.status = false", Long.class);
            return query.getSingleResult();
        } catch (PersistenceException e) {
            LOG.log(java.util.logging.Level.WARNING, "Error counting "+ getEntityClass().getSimpleName() +" : " + e.getMessage() + " | source=InventarioService.count() | despues=" + e.getMessage());
            return 0l;
        }
    }
    
    public Long countPendientes() {
        try {
            TypedQuery<Long> query = em.createQuery("SELECT COUNT(e) FROM Inventario e WHERE e.status = true AND e.processed = false", Long.class);
            return query.getSingleResult();
        } catch (PersistenceException e) {
            LOG.log(java.util.logging.Level.WARNING, "Error counting "+ getEntityClass().getSimpleName() +" : " + e.getMessage() + " | source=InventarioService.count() | despues=" + e.getMessage());
            return 0l;
        }
    }
    
    public void updateStock(Inventario entity) {
        try {
            String codigoBarra = entity.getArticulo().getCodigoBarra();
            // Find the existing stock record by barcode
            ArticuloStock existingStock = em.createQuery(
                "SELECT a FROM ArticuloStock a WHERE a.codigoBarra = :barcode", 
                ArticuloStock.class
            ).setParameter("barcode", codigoBarra).getResultStream().findFirst().orElse(null);

            if (existingStock != null) {
                // Update the existing stock record
                existingStock.setStock(existingStock.getStock().add(entity.getCantidad()));
                em.merge(existingStock);
            em.flush();
            } else {
                // Create a new stock record
                ArticuloStock newStock = new ArticuloStock();
                newStock.setCodigoBarra(codigoBarra);
                newStock.setStock(entity.getCantidad()); // Set initial stock
                em.persist(newStock);
            em.flush();
            }
        } catch (PersistenceException e) {
            LOG.log(java.util.logging.Level.WARNING, "Error updating stock: " + e.getMessage() + " | source=InventarioService.updateStock() | despues=" + e.getMessage());
        }
    }
    
    public List<Inventario> getMovementsByUserAndTipo(String userId) {
        String jpql = "SELECT i FROM Inventario i WHERE i.tipoMovimiento = 'Venta' AND i.usuario.id = :userId";
        TypedQuery<Inventario> query = em.createQuery(jpql, Inventario.class);
        query.setParameter("userId", userId);

        return query.getResultList();
    }
    
    // Method for querying by date range
    public List<Inventario> getMovementsByUserAndDateRange(String userId, Date startDate, Date endDate) {
        String jpql = "SELECT i FROM Inventario i WHERE i.tipoMovimiento = 'Venta' " +
                      "AND i.usuario.id = :userId " +
                      "AND i.fechaMovimiento BETWEEN :startDate AND :endDate";
        TypedQuery<Inventario> query = em.createQuery(jpql, Inventario.class);
        query.setParameter("userId", userId);
        query.setParameter("startDate", startDate);
        query.setParameter("endDate", endDate);
        return query.getResultList();
    }
    
    public List<ReportesFamiliasYDepartamentos> getTotalSalesByDepartamento(Date startDate, Date endDate) {
        String jpql = "SELECT d.nombre, SUM(ap.precioFinal * i.cantidad) " +
                      "FROM Inventario i " +
                      "JOIN i.articulo a " +
                      "JOIN a.departamento d " +
                      "JOIN ArticuloPrecio ap ON ap.articulo.codigo = a.codigo " +
                      "WHERE i.tipoMovimiento = 'Venta' " +
                      "AND ap.fechaCompra = (SELECT MAX(ap2.fechaCompra) FROM ArticuloPrecio ap2 WHERE ap2.articulo.codigo = a.codigo) " +
                      "AND i.fechaMovimiento BETWEEN :startDate AND :endDate " +
                      "GROUP BY d.nombre";

        Query query = em.createQuery(jpql);
        query.setParameter("startDate", startDate);
        query.setParameter("endDate", endDate);
        List<Object[]> results = query.getResultList();

        // Calculate the grand total
        BigDecimal grandTotal = BigDecimal.ZERO;
        for (Object[] result : results) {
            BigDecimal totalSales = (BigDecimal) result[1]; 
            // Handle null by initializing to BigDecimal.ZERO if necessary
            totalSales = (totalSales != null) ? totalSales.multiply(BigDecimal.valueOf(-1)) : BigDecimal.ZERO;
            grandTotal = grandTotal.add(totalSales);
        }

        // Create a list to store ReportesFamiliasYDepartamentos
        List<ReportesFamiliasYDepartamentos> totalSalesByDepartamento = new ArrayList<>();
        for (Object[] result : results) {
            String departamentoName = (String) result[0];
            BigDecimal totalSales = (BigDecimal) result[1];
            // Handle null by initializing to BigDecimal.ZERO if necessary
            totalSales = (totalSales != null) ? totalSales.multiply(BigDecimal.valueOf(-1)) : BigDecimal.ZERO;
            BigDecimal percentage = (grandTotal.compareTo(BigDecimal.ZERO) == 0) 
                ? BigDecimal.ZERO 
                : totalSales.divide(grandTotal, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));

            // Create ReportesFamiliasYDepartamentos object and add to the list
            ReportesFamiliasYDepartamentos reporte = new ReportesFamiliasYDepartamentos(departamentoName, totalSales, percentage);
            totalSalesByDepartamento.add(reporte);
        }

        return totalSalesByDepartamento;
    }

    public Date getLastPurchaseDateByDepartamento(Integer departamentoId) {
        try {
            String jpql = "SELECT MAX(i.fechaMovimiento) FROM Inventario i " +
                          "JOIN i.articulo a " +
                          "WHERE a.departamento.id = :departamentoId " +
                          "AND (i.tipoMovimiento LIKE '%Entrada%' OR i.tipoMovimiento LIKE '%Ingreso%')";
            TypedQuery<Date> query = em.createQuery(jpql, Date.class);
            query.setParameter("departamentoId", departamentoId);
            return query.getSingleResult();
        } catch (NoResultException e) {
            return null;
        } catch (PersistenceException e) {
            LOG.log(java.util.logging.Level.WARNING, "Error getting last purchase date: " + e.getMessage() + " | source=InventarioService.getLastPurchaseDateByDepartamento() | despues=" + e.getMessage());
            return null;
        }
    }

    public List<Object[]> getSalesDetailsByDepartamento(Date startDate, Date endDate, Integer departamentoId) {
        try {
            String jpql = "SELECT a.nombre, SUM(i.cantidad), SUM(ap.precioFinal * i.cantidad) " +
                          "FROM Inventario i " +
                          "JOIN i.articulo a " +
                          "JOIN a.departamento d " +
                          "JOIN ArticuloPrecio ap ON ap.articulo.codigo = a.codigo " +
                          "WHERE i.tipoMovimiento = 'Venta' " +
                          "AND d.id = :departamentoId " +
                          "AND ap.fechaCompra = (SELECT MAX(ap2.fechaCompra) FROM ArticuloPrecio ap2 WHERE ap2.articulo.codigo = a.codigo) " +
                          "AND i.fechaMovimiento BETWEEN :startDate AND :endDate " +
                          "GROUP BY a.nombre " +
                          "ORDER BY SUM(ap.precioFinal * i.cantidad) DESC";
            Query query = em.createQuery(jpql);
            query.setParameter("startDate", startDate);
            query.setParameter("endDate", endDate);
            query.setParameter("departamentoId", departamentoId);
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.log(java.util.logging.Level.WARNING, "Error getting sales details: " + e.getMessage() + " | source=InventarioService.getSalesDetailsByDepartamento() | despues=" + e.getMessage());
            return null;
        }
    }

    public List<ReportesFamiliasYDepartamentos> getTotalSalesByFamilia(Date startDate, Date endDate) {
        String jpql = "SELECT f.nombre, SUM(ap.precioFinal * i.cantidad) " +
                      "FROM Inventario i " +
                      "JOIN i.articulo a " +
                      "JOIN a.familia f " +
                      "JOIN ArticuloPrecio ap ON ap.articulo.codigo = a.codigo " +
                      "WHERE i.tipoMovimiento = 'Venta' " +
                      "AND ap.fechaCompra = (SELECT MAX(ap2.fechaCompra) FROM ArticuloPrecio ap2 WHERE ap2.articulo.codigo = a.codigo) " +
                      "AND i.fechaMovimiento BETWEEN :startDate AND :endDate " +
                      "GROUP BY f.nombre";

        Query query = em.createQuery(jpql);
        query.setParameter("startDate", startDate);
        query.setParameter("endDate", endDate);
        List<Object[]> results = query.getResultList();

        // Calculate the grand total
        BigDecimal grandTotal = BigDecimal.ZERO;
        for (Object[] result : results) {
            BigDecimal totalSales = (BigDecimal) result[1]; 
            // Handle null by initializing to BigDecimal.ZERO if necessary
            totalSales = (totalSales != null) ? totalSales.multiply(BigDecimal.valueOf(-1)) : BigDecimal.ZERO;
            grandTotal = grandTotal.add(totalSales);
        }

        // Create a list to store ReportesFamiliasYDepartamentos
        List<ReportesFamiliasYDepartamentos> totalSalesByFamilia = new ArrayList<>();
        for (Object[] result : results) {
            String familiaName = (String) result[0];
            BigDecimal totalSales = (BigDecimal) result[1]; 
            // Handle null by initializing to BigDecimal.ZERO if necessary
            totalSales = (totalSales != null) ? totalSales.multiply(BigDecimal.valueOf(-1)) : BigDecimal.ZERO;

            BigDecimal percentage = (grandTotal.compareTo(BigDecimal.ZERO) == 0) 
                ? BigDecimal.ZERO 
                : totalSales.divide(grandTotal, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));

            // Create ReportesFamiliasYDepartamentos object and add to the list
            ReportesFamiliasYDepartamentos reporte = new ReportesFamiliasYDepartamentos(familiaName, totalSales, percentage);
            totalSalesByFamilia.add(reporte);
        }

        return totalSalesByFamilia;
    }

    public List<ArticuloStock> getAllStock() {
        try {
            TypedQuery<ArticuloStock> query = em.createQuery("SELECT a FROM ArticuloStock a", ArticuloStock.class);
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.log(java.util.logging.Level.WARNING, "Error getting all stock: " + e.getMessage() + " | source=InventarioService.getAllStock() | despues=" + e.getMessage());
            return new ArrayList<>();
        }
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public ArticuloStock findStockByArticleCode(int articleCode) {
        try {
            return em.createQuery(
                "SELECT a FROM ArticuloStock a WHERE a.codigoBarra = (SELECT ar.codigoBarra FROM Articulos ar WHERE ar.codigo = :id)",
                ArticuloStock.class)
                .setParameter("id", (long) articleCode)
                .getResultStream().findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

}
