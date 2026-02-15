package Services;

import Models.Articulos.Articulos;
import Models.Departamento;
import Models.Inventario;
import Models.ReorderSuggestion;
import Models.StockAlert;
import Models.Users;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for intelligent stock management and automated reordering
 * Calculates optimal stock levels based on sales velocity and creates alerts
 */
@Named
@ApplicationScoped
public class StockAlertService extends GService<StockAlert> {

    @Inject
    private EntityManager em;

    @Inject
    private InventarioService inventarioService;

    @Override
    protected Class<StockAlert> getEntityClass() {
        return StockAlert.class;
    }

    /**
     * Calculate optimal stock level based on sales velocity
     * Formula: Average Daily Sales × (Lead Time + Safety Stock Days)
     */
    public Integer calculateOptimalStock(Articulos articulo) {
        // Get last 30 days of inventory movements for this article
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, -30);
        Date startDate = cal.getTime();
        Date endDate = new Date();

        String jpql = "SELECT i FROM Inventario i WHERE i.articulo.codigo = :articuloId " +
                     "AND i.fechaMovimiento BETWEEN :startDate AND :endDate " +
                     "ORDER BY i.fechaMovimiento DESC";
        TypedQuery<Inventario> query = em.createQuery(jpql, Inventario.class)
                .setParameter("articuloId", articulo.getCodigo())
                .setParameter("startDate", startDate)
                .setParameter("endDate", endDate);

        List<Inventario> movements = query.getResultList();
        if (movements.isEmpty()) {
            return articulo.getDiasStockSeguridad() != null ? articulo.getDiasStockSeguridad() * 2 : 14; // Default 2 weeks safety
        }

        // Calculate sales velocity (items sold per day)
        int totalSold = movements.stream()
                .mapToInt(m -> {
                    if ("Venta".equals(m.getTipoMovimiento())) {
                        return -m.getCantidad().intValue(); // Negative for sales
                    }
                    return 0;
                })
                .sum();

        BigDecimal daysWithSales = BigDecimal.valueOf(movements.stream()
                .mapToInt(m -> "Venta".equals(m.getTipoMovimiento()) ? 1 : 0)
                .sum());

        BigDecimal dailySales = daysWithSales.compareTo(BigDecimal.ZERO) > 0 
                ? BigDecimal.valueOf(totalSold).divide(daysWithSales, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // Get safety stock days (default to 7 if not set)
        int safetyDays = articulo.getDiasStockSeguridad() != null ? articulo.getDiasStockSeguridad() : 7;

        // Calculate optimal stock: (daily sales × lead time) + safety stock
        // Assume 3 days lead time for most suppliers
        int leadTime = 3;
        BigDecimal optimalStock = dailySales.multiply(BigDecimal.valueOf(leadTime + safetyDays))
                .setScale(0, RoundingMode.HALF_UP);

        return optimalStock.intValue();
    }

    /**
     * Check and create stock alerts for articles below optimal levels
     */
    @Transactional
    public void checkAndCreateStockAlerts() {
        // Get all active articles
        String jpql = "SELECT a FROM Articulos a WHERE a.status = true ORDER BY a.codigo";
        TypedQuery<Articulos> query = em.createQuery(jpql, Articulos.class);
        List<Articulos> articulos = query.getResultList();

        for (Articulos articulo : articulos) {
            // Get current stock level
            Integer currentStock = getCurrentStock(articulo);
            
            if (currentStock == null || currentStock == 0) {
                continue; // Skip if no stock data
            }

            // Calculate optimal stock
            Integer optimalStock = calculateOptimalStock(articulo);
            
            // Check if stock is below optimal level
            if (currentStock < optimalStock && articulo.getEstadoAlertas()) {
                // Determine alert type
                String alertType;
                if (currentStock == 0) {
                    alertType = "out_of_stock";
                } else {
                    alertType = "low_stock";
                }

                // Create stock alert
                StockAlert alert = new StockAlert();
                alert.setArticulo(articulo);
                alert.setTipoAlerta(alertType);
                alert.setCantidadActual(currentStock);
                alert.setCantidadMinima(optimalStock);
                alert.setSugeridoReordenar(calculateReorderQuantity(articulo, currentStock, optimalStock));
                alert.setDepartamento(articulo.getDepartamento());
                alert.setNotas("Alerta generada automáticamente - Stock actual: " + currentStock + 
                              ", Stock óptimo: " + optimalStock);

                em.persist(alert);

                // Update article's optimal stock
                articulo.setStockOptimo(optimalStock);
                em.merge(articulo);

                // Create reorder suggestion
                createReorderSuggestion(articulo, currentStock, optimalStock);
            }
        }
    }

    /**
     * Get current stock level for an article
     */
    private Integer getCurrentStock(Articulos articulo) {
        try {
            String jpql = "SELECT SUM(i.cantidad) FROM Inventario i " +
                         "WHERE i.articulo.codigo = :articuloId AND i.status = true " +
                         "GROUP BY i.articulo.codigo";
            TypedQuery<Long> query = em.createQuery(jpql, Long.class)
                    .setParameter("articuloId", articulo.getCodigo());

            Long result = query.getSingleResult();
            return result != null ? result.intValue() : 0;
        } catch (NoResultException e) {
            return 0;
        }
    }

    /**
     * Calculate reorder quantity based on gap between current and optimal stock
     */
    private Integer calculateReorderQuantity(Articulos articulo, Integer currentStock, Integer optimalStock) {
        // Get monthly sales for this article
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.MONTH, -1);
        Date startDate = cal.getTime();
        Date endDate = new Date();

        String jpql = "SELECT i FROM Inventario i WHERE i.articulo.codigo = :articuloId " +
                     "AND i.fechaMovimiento BETWEEN :startDate AND :endDate " +
                     "AND i.tipoMovimiento = 'Venta'";
        TypedQuery<Inventario> query = em.createQuery(jpql, Inventario.class)
                .setParameter("articuloId", articulo.getCodigo())
                .setParameter("startDate", startDate)
                .setParameter("endDate", endDate);

        List<Inventario> sales = query.getResultList();
        int monthlySales = sales.stream()
                .mapToInt(s -> -s.getCantidad().intValue())
                .sum();

        // Calculate reorder quantity to reach optimal stock + 30 days buffer
        int stockNeeded = optimalStock - currentStock;
        int thirtyDayBuffer = monthlySales; // 30 days of sales
        
        return stockNeeded + thirtyDayBuffer;
    }

    /**
     * Create reorder suggestion for an article
     */
    @Transactional
    public void createReorderSuggestion(Articulos articulo, Integer currentStock, Integer optimalStock) {
        Integer reorderQuantity = calculateReorderQuantity(articulo, currentStock, optimalStock);
        
        if (reorderQuantity <= 0) {
            return; // No reordering needed
        }

        // Get monthly average sales
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.MONTH, -3);
        Date startDate = cal.getTime();
        Date endDate = new Date();

        String jpql = "SELECT i FROM Inventario i WHERE i.articulo.codigo = :articuloId " +
                     "AND i.fechaMovimiento BETWEEN :startDate AND :endDate " +
                     "AND i.tipoMovimiento = 'Venta'";
        TypedQuery<Inventario> query = em.createQuery(jpql, Inventario.class)
                .setParameter("articuloId", articulo.getCodigo())
                .setParameter("startDate", startDate)
                .setParameter("endDate", endDate);

        List<Inventario> sales = query.getResultList();
        BigDecimal monthlySales = BigDecimal.valueOf(sales.stream()
                .mapToInt(s -> -s.getCantidad().intValue())
                .sum())
                .divide(BigDecimal.valueOf(3), 2, RoundingMode.HALF_UP); // Average over 3 months

        // Calculate priority
        String priority;
        if (currentStock == 0) {
            priority = "urgent";
        } else if (currentStock < optimalStock * 0.3) {
            priority = "high";
        } else if (currentStock < optimalStock * 0.6) {
            priority = "medium";
        } else {
            priority = "low";
        }

        // Calculate estimated cost
        BigDecimal estimatedCost = reorderQuantity > 0 && articulo.getLastPrecio() != null
                ? articulo.getLastPrecio().getPrecioCostoSinIVA().multiply(BigDecimal.valueOf(reorderQuantity))
                : BigDecimal.ZERO;

        // Calculate days without stock
        Integer diasSinStock = currentStock == 0 ? 0 : null;
        if (monthlySales.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal dailySales = monthlySales.divide(BigDecimal.valueOf(30), 2, RoundingMode.HALF_UP);
            if (dailySales.compareTo(BigDecimal.ZERO) > 0) {
                diasSinStock = reorderQuantity / dailySales.intValue();
            }
        }

        // Create reorder suggestion
        ReorderSuggestion suggestion = new ReorderSuggestion();
        suggestion.setArticulo(articulo);
        suggestion.setDepartamento(articulo.getDepartamento());
        suggestion.setCantidadSugerida(reorderQuantity);
        suggestion.setCostoTotalEstimado(estimatedCost);
        suggestion.setPrioridad(priority);
        suggestion.setDiasSinStock(diasSinStock);
        suggestion.setPromedioVentasMensual(monthlySales);
        suggestion.setNotas("Sugerencia generada automáticamente basada en análisis de ventas históricas");

        em.persist(suggestion);
    }

    /**
     * Get all active stock alerts
     */
    public List<StockAlert> getActiveStockAlerts() {
        String jpql = "SELECT sa FROM StockAlert sa WHERE sa.estado = 'active' ORDER BY sa.fechaCreacion DESC";
        TypedQuery<StockAlert> query = em.createQuery(jpql, StockAlert.class);
        return query.getResultList();
    }

    /**
     * Get all reorder suggestions
     */
    public List<ReorderSuggestion> getAllReorderSuggestions() {
        String jpql = "SELECT rs FROM ReorderSuggestion rs ORDER BY rs.prioridad DESC, rs.fechaCreacion DESC";
        TypedQuery<ReorderSuggestion> query = em.createQuery(jpql, ReorderSuggestion.class);
        return query.getResultList();
    }

    /**
     * Get reorder suggestions by priority
     */
    public List<ReorderSuggestion> getReorderSuggestionsByPriority(String priority) {
        String jpql = "SELECT rs FROM ReorderSuggestion rs WHERE rs.prioridad = :priority ORDER BY rs.fechaCreacion DESC";
        TypedQuery<ReorderSuggestion> query = em.createQuery(jpql, ReorderSuggestion.class)
                .setParameter("priority", priority);
        return query.getResultList();
    }

    /**
     * Acknowledge a stock alert
     */
    @Transactional
    public void acknowledgeStockAlert(StockAlert alert, Users user, String notes) {
        alert.setEstado("acknowledged");
        alert.setFechaResolucion(new Date());
        alert.setUsuarioResolucion(user);
        alert.setNotas(notes);
        em.merge(alert);
    }

    /**
     * Resolve a stock alert
     */
    @Transactional
    public void resolveStockAlert(StockAlert alert, Users user, String notes) {
        alert.setEstado("resolved");
        alert.setFechaResolucion(new Date());
        alert.setUsuarioResolucion(user);
        alert.setNotas(notes);
        em.merge(alert);
    }

    /**
     * Get stock alerts by department
     */
    public List<StockAlert> getStockAlertsByDepartment(Departamento departamento) {
        String jpql = "SELECT sa FROM StockAlert sa WHERE sa.departamento = :departamento AND sa.estado = 'active' ORDER BY sa.fechaCreacion DESC";
        TypedQuery<StockAlert> query = em.createQuery(jpql, StockAlert.class)
                .setParameter("departamento", departamento);
        return query.getResultList();
    }

    /**
     * Get alert statistics
     */
    public Map<String, Integer> getAlertStatistics() {
        Map<String, Integer> stats = new HashMap<>();
        
        String jpql = "SELECT sa.tipoAlerta, COUNT(sa) FROM StockAlert sa " +
                     "WHERE sa.fechaCreacion >= :startDate GROUP BY sa.tipoAlerta";
        
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, -30);
        Date startDate = cal.getTime();
        
        TypedQuery<Object[]> query = em.createQuery(jpql, Object[].class)
                .setParameter("startDate", startDate);
        
        List<Object[]> results = query.getResultList();
        for (Object[] result : results) {
            String alertType = (String) result[0];
            Long count = (Long) result[1];
            stats.put(alertType, count.intValue());
        }
        
        return stats;
    }
}