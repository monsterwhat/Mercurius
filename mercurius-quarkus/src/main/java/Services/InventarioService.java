package Services;

import Models.Articulos.ArticuloStock; 
import Models.Inventario;
import Models.ReportesFamiliasYDepartamentos;
import Models.Users;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
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
import java.util.Date; 
import java.util.List; 

@Named
@ApplicationScoped
public class InventarioService extends GService<Inventario> {
        
    @Inject AlertasService alertasService;

    @Override
    protected Class<Inventario> getEntityClass() {
        return Inventario.class;
    }

    @PostConstruct
    public void init() {
    }

    @Override
    public void create(Inventario entity) {
        try {
            em.persist(entity);
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error creating entity: " + e.getMessage(), entity.getUsuario(), 0, "InventarioService.create()", null, e.getMessage());
        }
    }
    
    public void createWithStock(Inventario entity) {
        try {
            em.persist(entity);
            updateStock(entity);
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error creating entity: " + e.getMessage(), entity.getUsuario(), 0, "InventarioService.createWithStock()", null, e.getMessage());
        }
    }

    @Override
    public void delete(Inventario entity) {
        try {
            if (!em.contains(entity)) {
                entity = em.find(getEntityClass(), entity.getCodigo());
            }

            if (entity != null) {
                em.remove(entity);
            } else {
                alertasService.registrarAlerta("Info", "Entity not found for deletion", null, 0, "InventarioService.delete()", null, null);
            }
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error deleting " + getEntityClass().getSimpleName() + " : " + e.getMessage(), null, 0, "InventarioService.delete()", null, e.getMessage());
        }
    }

    @Override
    public void update(Inventario entity) {
        try {
            em.merge(entity);
            updateStock(entity);
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error updating entity: " + e.getMessage(), entity.getUsuario(), 0, "InventarioService.update()", null, e.getMessage());
        }
    }
    
    public void markAsProcessed(Inventario entity) {
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
                updateStock(existingItem);
            } else {
                alertasService.registrarAlerta("Info", "Entity not found for markAsProcessed", null, 0, "InventarioService.markAsProcessed()", null, null);
            }
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error marking as processed: " + e.getMessage(), entity.getUsuario(), 0, "InventarioService.markAsProcessed()", null, e.getMessage());
        }
    }

    @Override
    public List<Inventario> listAll() {
        try {
            TypedQuery<Inventario> query = em.createQuery("SELECT a FROM Inventario a", Inventario.class);
            return query.getResultList();
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error listing all entities: " + e.getMessage(), null, 0, "InventarioService.listAll()", null, e.getMessage());
            return null;
        }
    }
    
    public List<Inventario> ListAllEnabled() {
        try {
            TypedQuery<Inventario> query = em.createQuery("SELECT a FROM Inventario a WHERE a.status = true", Inventario.class);
            return query.getResultList();
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error listing all enabled entities: " + e.getMessage(), null, 0, "InventarioService.ListAllEnabled()", null, e.getMessage());
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
            } else {
                alertasService.registrarAlerta("Info", "Entity not found for softDelete", null, 0, "InventarioService.softDelete()", null, null);
            }
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error soft deleting entity: " + e.getMessage(), null, 0, "InventarioService.softDelete()", null, e.getMessage());
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
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error getting stock for barcode: " + barcode + " - " + e.getMessage(), null, 0, "InventarioService.getStock()", null, e.getMessage());
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
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error calculating total stock for item by barcode: " + e.getMessage(), null, 0, "InventarioService.calculateTotalStockForItemByBarcode()", null, e.getMessage());
            return 0.0;
        }
    }

    public List<Inventario> listAllSinProcesar() {
        try {
            TypedQuery<Inventario> query = em.createQuery("SELECT a FROM Inventario a WHERE a.status = true AND a.processed = false", Inventario.class);
            return query.getResultList();
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error listing all entities: " + e.getMessage(), null, 0, "InventarioService.listAllSinProcesar()", null, e.getMessage());
            return null;
        }
    }

    public List<Inventario> listAllActivosYProcesados() {
    try {
            TypedQuery<Inventario> query = em.createQuery("SELECT a FROM Inventario a WHERE a.status = true AND a.processed = true", Inventario.class);
            return query.getResultList();
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error listing all entities: " + e.getMessage(), null, 0, "InventarioService.listAllActivosYProcesados()", null, e.getMessage());
            return null;
        }    
    }

    public List<Inventario> listAllInactivos() {
    try {
            TypedQuery<Inventario> query = em.createQuery("SELECT a FROM Inventario a WHERE a.status = false", Inventario.class);
            return query.getResultList();
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error listing all entities: " + e.getMessage(), null, 0, "InventarioService.listAllInactivos()", null, e.getMessage());
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
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error counting "+ getEntityClass().getSimpleName() +" : " + e.getMessage(), null, 0, "InventarioService.count()", null, e.getMessage());
            return 0l;
        }
    }
    
    public Long countInactivos() {
        try {
            TypedQuery<Long> query = em.createQuery("SELECT COUNT(e) FROM Inventario e WHERE e.status = false", Long.class);
            return query.getSingleResult();
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error counting "+ getEntityClass().getSimpleName() +" : " + e.getMessage(), null, 0, "InventarioService.count()", null, e.getMessage());
            return 0l;
        }
    }
    
    public Long countPendientes() {
        try {
            TypedQuery<Long> query = em.createQuery("SELECT COUNT(e) FROM Inventario e WHERE e.status = true AND e.processed = false", Long.class);
            return query.getSingleResult();
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error counting "+ getEntityClass().getSimpleName() +" : " + e.getMessage(), null, 0, "InventarioService.count()", null, e.getMessage());
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
            } else {
                // Create a new stock record
                ArticuloStock newStock = new ArticuloStock();
                newStock.setCodigoBarra(codigoBarra);
                newStock.setStock(entity.getCantidad()); // Set initial stock
                em.persist(newStock);
            }
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error updating stock: " + e.getMessage(), entity.getUsuario(), 0, "InventarioService.updateStock()", null, e.getMessage());
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
        } catch (Exception e) {
            alertasService.registrarAlerta("Error", "Error getting all stock: " + e.getMessage(), null, 0, "InventarioService.getAllStock()", null, e.getMessage());
            return new ArrayList<>();
        }
    }

}
