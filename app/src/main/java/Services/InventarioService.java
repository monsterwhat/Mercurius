package Services;

import Models.Articulos.ArticuloStock; 
import Models.Inventario;
import Models.ReportesFamiliasYDepartamentos;
import Models.Usuarios;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import jakarta.persistence.NoResultException;
import jakarta.persistence.LockModeType;
import jakarta.persistence.OptimisticLockException;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List; 
import java.util.Map;
import org.jboss.logging.Logger;

@Named
@ApplicationScoped
public class InventarioService extends GService<Inventario> {
        
    private static final Logger LOG = Logger.getLogger(InventarioService.class);

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
            LOG.warn("Error creating entity: " + e.getMessage() + " | source=InventarioService.create() | despues=" + e.getMessage());
        }
    }
    
    @Transactional
    public void createWithStock(@Nonnull Inventario entity) {
        try {
            em.persist(entity);
            em.flush();
            updateStock(entity);
            em.flush();
        } catch (PersistenceException e) {
            LOG.warn("Error creating entity: " + e.getMessage() + " | source=InventarioService.createWithStock() | despues=" + e.getMessage());
        }
    }

    @Override
    @Transactional
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
            LOG.warn("Error deleting " + getEntityClass().getSimpleName() + " : " + e.getMessage() + " | source=InventarioService.delete() | despues=" + e.getMessage());
        }
    }

    @Override
    @Transactional
    public void update(@Nonnull Inventario entity) {
        try {
            em.merge(entity);
            em.flush();
            updateStock(entity);
            em.flush();
        } catch (PersistenceException e) {
            LOG.warn("Error updating entity: " + e.getMessage() + " | source=InventarioService.update() | despues=" + e.getMessage());
        }
    }
    
    @Transactional
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
            LOG.warn("Error marking as processed: " + e.getMessage() + " | source=InventarioService.markAsProcessed() | despues=" + e.getMessage());
        }
    }

    @Override
    public @Nullable List<Inventario> listAll() {
        try {
            TypedQuery<Inventario> query = em.createQuery("SELECT a FROM Inventario a", Inventario.class);
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.warn("Error listing all entities: " + e.getMessage() + " | source=InventarioService.listAll() | despues=" + e.getMessage());
            return null;
        }
    }
    
    public @Nullable List<Inventario> ListAllEnabled() {
        try {
            TypedQuery<Inventario> query = em.createQuery("SELECT a FROM Inventario a WHERE a.status = true", Inventario.class);
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.warn("Error listing all enabled entities: " + e.getMessage() + " | source=InventarioService.ListAllEnabled() | despues=" + e.getMessage());
            return null;
        }
    }

    @Transactional
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
            LOG.warn("Error soft deleting entity: " + e.getMessage() + " | source=InventarioService.softDelete() | despues=" + e.getMessage());
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
            LOG.warn("Error getting stock for barcode: " + barcode + " - " + e.getMessage() + " | source=InventarioService.getStock() | despues=" + e.getMessage());
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
            LOG.warn("Error calculating total stock for item by barcode: " + e.getMessage() + " | source=InventarioService.calculateTotalStockForItemByBarcode() | despues=" + e.getMessage());
            return 0.0;
        }
    }

    public List<Inventario> listAllSinProcesar() {
        try {
            TypedQuery<Inventario> query = em.createQuery("SELECT a FROM Inventario a WHERE a.status = true AND a.processed = false", Inventario.class);
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.warn("Error listing all entities: " + e.getMessage() + " | source=InventarioService.listAllSinProcesar() | despues=" + e.getMessage());
            return null;
        }
    }

    public List<Inventario> listAllActivosYProcesados() {
    try {
            TypedQuery<Inventario> query = em.createQuery("SELECT a FROM Inventario a WHERE a.status = true AND a.processed = true", Inventario.class);
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.warn("Error listing all entities: " + e.getMessage() + " | source=InventarioService.listAllActivosYProcesados() | despues=" + e.getMessage());
            return null;
        }    
    }

    public List<Inventario> listAllInactivos() {
    try {
            TypedQuery<Inventario> query = em.createQuery("SELECT a FROM Inventario a WHERE a.status = false", Inventario.class);
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.warn("Error listing all entities: " + e.getMessage() + " | source=InventarioService.listAllInactivos() | despues=" + e.getMessage());
            return null;
        }     
    }

    // ── Páginas de la tabla ──────────────────────────────────────────────
    // La página /app/inventarios solo pinta 20 filas por pestaña, pero pedía
    // las listas completas y las recortaba en memoria. Con 673.326
    // movimientos eso hidrataba dos veces el catálogo entero (con el
    // artículo EAGER) para descartar 673.306 filas. Estas consultas dejan
    // que PostgreSQL haga el LIMIT/OFFSET.

    /** Página de movimientos activos, más recientes primero. */
    public @Nullable List<Inventario> listEnabledPage(int first, int max) {
        return page(
                "SELECT a FROM Inventario a WHERE a.status = true ORDER BY a.codigo DESC",
                first, max, "listEnabledPage");
    }

    /** Página de movimientos inactivos, más recientes primero. */
    public @Nullable List<Inventario> listInactivosPage(int first, int max) {
        return page(
                "SELECT a FROM Inventario a WHERE a.status = false ORDER BY a.codigo DESC",
                first, max, "listInactivosPage");
    }

    /** Página de movimientos activos sin procesar, más recientes primero. */
    public @Nullable List<Inventario> listSinProcesarPage(int first, int max) {
        return page(
                "SELECT a FROM Inventario a WHERE a.status = true AND a.processed = false "
                        + "ORDER BY a.codigo DESC",
                first, max, "listSinProcesarPage");
    }

    /** Página de movimientos activos ya procesados, más recientes primero. */
    public @Nullable List<Inventario> listActivosYProcesadosPage(int first, int max) {
        return page(
                "SELECT a FROM Inventario a WHERE a.status = true AND a.processed = true "
                        + "ORDER BY a.codigo DESC",
                first, max, "listActivosYProcesadosPage");
    }

    private @Nullable List<Inventario> page(@Nonnull String jpql, int first, int max,
                                            @Nonnull String origen) {
        try {
            return em.createQuery(jpql, Inventario.class)
                    .setFirstResult(Math.max(0, first))
                    .setMaxResults(Math.max(1, max))
                    .getResultList();
        } catch (PersistenceException e) {
            LOG.warn("Error listing page of inventario: " + e.getMessage()
                    + " | source=InventarioService." + origen + "()");
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
            LOG.warn("Error counting "+ getEntityClass().getSimpleName() +" : " + e.getMessage() + " | source=InventarioService.count() | despues=" + e.getMessage());
            return 0l;
        }
    }
    
    public Long countInactivos() {
        try {
            TypedQuery<Long> query = em.createQuery("SELECT COUNT(e) FROM Inventario e WHERE e.status = false", Long.class);
            return query.getSingleResult();
        } catch (PersistenceException e) {
            LOG.warn("Error counting "+ getEntityClass().getSimpleName() +" : " + e.getMessage() + " | source=InventarioService.count() | despues=" + e.getMessage());
            return 0l;
        }
    }
    
    public Long countPendientes() {
        try {
            TypedQuery<Long> query = em.createQuery("SELECT COUNT(e) FROM Inventario e WHERE e.status = true AND e.processed = false", Long.class);
            return query.getSingleResult();
        } catch (PersistenceException e) {
            LOG.warn("Error counting "+ getEntityClass().getSimpleName() +" : " + e.getMessage() + " | source=InventarioService.count() | despues=" + e.getMessage());
            return 0l;
        }
    }
    
    /**
     * Applies an inventory movement to the running stock of its article.
     *
     * <p><b>Atomic and serialized.</b> This is a read-modify-write on a shared
     * row, so it takes a {@code PESSIMISTIC_WRITE} lock and runs inside a
     * transaction — the same pattern {@code ConsecutivoEmitidoService} uses for
     * invoice numbering. Previously this method had no {@code @Transactional},
     * no lock and no version column, so two concurrent sales of the same article
     * both read the same {@code stock}, both added their quantity, and the
     * second write silently discarded the first.</p>
     *
     * <p><b>Failures are no longer swallowed.</b> The old
     * {@code catch (PersistenceException) { LOG.warn(...); }} returned
     * {@code void}, so a rejected write was indistinguishable from a successful
     * one and the sale completed with stock never applied. The exception now
     * propagates and the surrounding transaction rolls back.</p>
     *
     * <p><b>First sale of a brand-new barcode.</b> Two transactions can both
     * find no row and both try to insert. {@code ArticuloStock.codigoBarra}
     * is now {@code unique}, so the database — not application timing — picks
     * the winner and the loser fails loudly. That loser is expected to retry
     * the sale; the previous behaviour let both inserts succeed and split the
     * article's stock across two rows permanently.</p>
     *
     * <p><b>Optimista + pesimista.</b> La fila sigue tomándose con
     * {@code PESSIMISTIC_WRITE} y {@code ArticuloStock.version} ({@code @Version})
     * detecta la confirmación tardía: el perdedor recibe
     * {@code OptimisticLockException} sin envolver (el mapper responde 409)
     * para que la venta recargue y reintente.</p>
     */
    @Transactional
    public void updateStock(Inventario entity) {
        String codigoBarra = entity.getArticulo() != null ? entity.getArticulo().getCodigoBarra() : null;
        if (codigoBarra == null || codigoBarra.isBlank()) {
            throw new IllegalArgumentException(
                    "No se puede actualizar el stock: el movimiento no tiene articulo con codigo de barra.");
        }
        BigDecimal cantidad = entity.getCantidad() != null ? entity.getCantidad() : BigDecimal.ZERO;

        try {
            TypedQuery<ArticuloStock> query = em.createQuery(
                    "SELECT a FROM ArticuloStock a WHERE a.codigoBarra = :barcode",
                    ArticuloStock.class);
            query.setParameter("barcode", codigoBarra);
            query.setLockMode(LockModeType.PESSIMISTIC_WRITE);
            query.setMaxResults(1);

            ArticuloStock stock;
            try {
                stock = query.getSingleResult();
                stock.setStock(stock.getStock() == null
                        ? cantidad
                        : stock.getStock().add(cantidad));
            } catch (NoResultException e) {
                stock = new ArticuloStock();
                stock.setCodigoBarra(codigoBarra);
                stock.setStock(cantidad);
            }
            em.merge(stock);
            em.flush();
        } catch (OptimisticLockException e) {
            LOG.warn("Conflicto de versión en stock " + codigoBarra
                    + " | source=InventarioService.updateStock()");
            throw e;
        } catch (PersistenceException e) {
            LOG.warn("Error updating stock: " + e.getMessage()
                    + " | source=InventarioService.updateStock()"
                    + " | despues=" + e.getMessage());
            throw new RuntimeException(
                    "No se pudo actualizar el stock del articulo " + codigoBarra + ": " + e.getMessage(), e);
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
    
    /**
     * Ventas agrupadas por familia o departamento, valuadas al PRECIO VIGENTE
     * en la fecha de cada movimiento.
     *
     * <p>Antes se tomaba el ultimo precio del articulo: con mas de una decada
     * de historico eso valoraba las ventas de 2013 al precio de 2026 y falseaba
     * la distribucion. Ahora se usa la serie historica de precios
     * ({@code articuloprecio}) tomando el precio vigente en la fecha del
     * movimiento.</p>
     *
     * @param grupo "familia" o "departamento"
     */
    private @Nonnull List<Object[]> ventasPorGrupoValoradas(@Nonnull String grupo,
                                                            Date startDate, Date endDate) {
        boolean esFamilia = "familia".equals(grupo);
        String tabla = esFamilia ? "familia" : "departamento";
        String columna = esFamilia ? "familia_id" : "departamento_id";
        String sql = "SELECT g.nombre, SUM(p.precio_final * i.cantidad) "
                + "FROM inventario i "
                + "JOIN articulos a ON a.codigo = i.articulo_codigo "
                + "JOIN " + tabla + " g ON g.id = a." + columna + " "
                + "JOIN (SELECT DISTINCT ON (ap.articulo_id) ap.articulo_id, ap.precio_final "
                + "        FROM articuloprecio ap "
                + "       ORDER BY ap.articulo_id, ap.fechacompra DESC NULLS LAST, ap.id DESC) p "
                + "  ON p.articulo_id = a.codigo "
                + "WHERE i.tipomovimiento = 'Venta' "
                + "AND i.fechamovimiento BETWEEN :startDate AND :endDate "
                + "GROUP BY g.nombre";
        Query query = em.createNativeQuery(sql);
        query.setParameter("startDate", startDate);
        query.setParameter("endDate", endDate);
        @SuppressWarnings("unchecked")
        List<Object[]> results = query.getResultList();
        return results;
    }

    /** Convierte los totales por grupo en el modelo del reporte (montes y %). */
    private @Nonnull List<ReportesFamiliasYDepartamentos> reportePorGrupo(@Nonnull String grupo,
                                                                        Date startDate, Date endDate) {
        List<Object[]> results = ventasPorGrupoValoradas(grupo, startDate, endDate);

        // Calculate the grand total
        BigDecimal grandTotal = BigDecimal.ZERO;
        for (Object[] result : results) {
            BigDecimal totalSales = (BigDecimal) result[1];
            // Handle null by initializing to BigDecimal.ZERO if necessary
            totalSales = (totalSales != null) ? totalSales.multiply(BigDecimal.valueOf(-1)) : BigDecimal.ZERO;
            grandTotal = grandTotal.add(totalSales);
        }

        // Create a list to store ReportesFamiliasYDepartamentos
        List<ReportesFamiliasYDepartamentos> reporte = new ArrayList<>();
        for (Object[] result : results) {
            String nombre = (String) result[0];
            BigDecimal totalSales = (BigDecimal) result[1];
            // Handle null by initializing to BigDecimal.ZERO if necessary
            totalSales = (totalSales != null) ? totalSales.multiply(BigDecimal.valueOf(-1)) : BigDecimal.ZERO;
            BigDecimal percentage = (grandTotal.compareTo(BigDecimal.ZERO) == 0)
                ? BigDecimal.ZERO
                : totalSales.divide(grandTotal, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));

            reporte.add(new ReportesFamiliasYDepartamentos(nombre, totalSales, percentage));
        }

        return reporte;
    }

    public List<ReportesFamiliasYDepartamentos> getTotalSalesByDepartamento(Date startDate, Date endDate) {
        try {
            return reportePorGrupo("departamento", startDate, endDate);
        } catch (PersistenceException e) {
            LOG.warn("Error getting total sales by departamento: " + e.getMessage()
                    + " | source=InventarioService.getTotalSalesByDepartamento() | despues=" + e.getMessage());
            return new ArrayList<>();
        }
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
            LOG.warn("Error getting last purchase date: " + e.getMessage() + " | source=InventarioService.getLastPurchaseDateByDepartamento() | despues=" + e.getMessage());
            return null;
        }
    }

    public List<Object[]> getSalesDetailsByDepartamento(Date startDate, Date endDate, Integer departamentoId) {
        try {
            String sql = "SELECT a.nombre, SUM(i.cantidad), SUM(p.precio_final * i.cantidad) "
                    + "FROM inventario i "
                    + "JOIN articulos a ON a.codigo = i.articulo_codigo "
                    + "JOIN departamento d ON d.id = a.departamento_id "
                    + "JOIN LATERAL (SELECT ap.precio_final FROM articuloprecio ap "
                    + "              WHERE ap.articulo_id = a.codigo AND ap.fechacompra <= i.fechamovimiento "
                    + "              ORDER BY ap.fechacompra DESC LIMIT 1) p ON true "
                    + "WHERE i.tipomovimiento = 'Venta' "
                    + "AND d.id = :departamentoId "
                    + "AND i.fechamovimiento BETWEEN :startDate AND :endDate "
                    + "GROUP BY a.nombre "
                    + "ORDER BY SUM(p.precio_final * i.cantidad) DESC";
            Query query = em.createNativeQuery(sql);
            query.setParameter("startDate", startDate);
            query.setParameter("endDate", endDate);
            query.setParameter("departamentoId", departamentoId);
            @SuppressWarnings("unchecked")
            List<Object[]> results = query.getResultList();
            return results;
        } catch (PersistenceException e) {
            LOG.warn("Error getting sales details: " + e.getMessage() + " | source=InventarioService.getSalesDetailsByDepartamento() | despues=" + e.getMessage());
            return null;
        }
    }

    public List<ReportesFamiliasYDepartamentos> getTotalSalesByFamilia(Date startDate, Date endDate) {
        try {
            return reportePorGrupo("familia", startDate, endDate);
        } catch (PersistenceException e) {
            LOG.warn("Error getting total sales by familia: " + e.getMessage()
                    + " | source=InventarioService.getTotalSalesByFamilia() | despues=" + e.getMessage());
            return new ArrayList<>();
        }
    }

    public List<ArticuloStock> getAllStock() {
        try {
            TypedQuery<ArticuloStock> query = em.createQuery("SELECT a FROM ArticuloStock a", ArticuloStock.class);
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.warn("Error getting all stock: " + e.getMessage() + " | source=InventarioService.getAllStock() | despues=" + e.getMessage());
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

    // ── Resumen de Inventario (app) ───────────────────────────────────────
    // /app/reportes/inventario/resumen materializaba los 252k articulos
    // habilitados (con sus precios EAGER), formateaba 252k filas, las ordenaba
    // en memoria y recortaba 15. Estas consultas dejan que PostgreSQL cuente,
    // filtre, ordene y recorte; solo se hidrata la pagina pedida.

    /** Escape character for the LIKE patterns built by {@link #likePattern}. */
    private static final char LIKE_ESCAPE = '!';

    /**
     * Stock of the joined article. A missing {@code articulostock} row means
     * zero, exactly like the previous in-memory map default.
     */
    private static final String RESUMEN_STOCK = "COALESCE(s.stock, 0)";

    /**
     * FROM shared by the resumen COUNT/page/chart queries. The barcode join is
     * a plain equality: {@code NULL = anything} is UNKNOWN, so an article with
     * a null barcode never matches (LEFT JOIN) and keeps the stock-0 default.
     * All other joins are to-one, so no row multiplication.
     */
    private static final String RESUMEN_FROM =
            " FROM articulos a"
            + " LEFT JOIN articulostock s ON s.codigo_barra = a.codigobarra"
            + " LEFT JOIN familia f ON f.id = a.familia_id"
            + " LEFT JOIN departamento d ON d.id = a.departamento_id";

    /**
     * KPI counters for the resumen: enabled articles, those at zero stock and
     * those with negative stock.
     *
     * <p>One COUNT pass instead of iterating the whole catalogue: the page used
     * {@code countActivos()} for "todos" and counted cero/negativos while
     * hydrating 252k entities.</p>
     *
     * @return {@code [todos, cero, negativos]}; all zeros when the query fails
     *         (the page keeps rendering, as it did when the list came back
     *         empty)
     */
    public @Nonnull long[] countResumenInventarioKpis() {
        String sql = "SELECT COUNT(*)"
                + ", COUNT(*) FILTER (WHERE " + RESUMEN_STOCK + " = 0)"
                + ", COUNT(*) FILTER (WHERE " + RESUMEN_STOCK + " < 0)"
                + RESUMEN_FROM
                + " WHERE a.status = true";
        try {
            Object[] fila = (Object[]) em.createNativeQuery(sql).getSingleResult();
            return new long[]{numero(fila[0]), numero(fila[1]), numero(fila[2])};
        } catch (PersistenceException e) {
            LOG.warn("Error contando KPIs del resumen de inventario: " + e.getMessage()
                    + " | source=InventarioService.countResumenInventarioKpis()"
                    + " | despues=" + e.getMessage());
            return new long[]{0L, 0L, 0L};
        }
    }

    /**
     * COUNT companion of {@link #pageResumenInventario}: identical FROM/WHERE,
     * so the pager total and the returned page can never disagree.
     *
     * @param vista         {@code cero} / {@code negativos} / anything else
     *                      (treated as {@code todos})
     * @param familiaId     family filter; null or &le;0 means "all"
     * @param departamentoId department filter; null or &le;0 means "all"
     * @param q             free-text filter; null/blank means "no filter"
     * @return filtered row count, 0 when the query fails
     */
    public long countResumenInventario(@Nullable String vista, @Nullable Integer familiaId,
                                       @Nullable Integer departamentoId, @Nullable String q) {
        Map<String, Object> params = new LinkedHashMap<>();
        String sql = "SELECT COUNT(*)" + RESUMEN_FROM
                + resumenWhere(vista, familiaId, departamentoId, q, params);
        try {
            return numero(consulta(sql, params).getSingleResult());
        } catch (PersistenceException e) {
            LOG.warn("Error contando el resumen de inventario: " + e.getMessage()
                    + " | source=InventarioService.countResumenInventario()"
                    + " | vista=" + vista + " | familia=" + familiaId
                    + " | departamento=" + departamentoId + " | q=" + q
                    + " | despues=" + e.getMessage());
            return 0L;
        }
    }

    /**
     * One page of the resumen table, filtered and sorted in SQL.
     *
     * <p>Only the page rows are read. The last price is deliberately NOT part
     * of this query (see {@link #ultimosPreciosPorArticulo(List)}): a request
     * that is not sorted by price never touches the price history. When
     * {@code sort=precio} the latest price is joined once per article because
     * the ORDER BY needs it — the same aggregate
     * {@code ArticulosService.pageByTab} uses, instead of a correlated
     * subquery per row.</p>
     *
     * @return page rows of {@code [codigo, nombre, codigoBarra, familia,
     *         departamento, stock, stockOptimo]} in display order
     */
    @SuppressWarnings("unchecked")
    public @Nonnull List<Object[]> pageResumenInventario(
            @Nullable String vista, @Nullable Integer familiaId,
            @Nullable Integer departamentoId, @Nullable String q,
            @Nullable String sort, @Nullable String dir, int offset, int size) {
        Map<String, Object> params = new LinkedHashMap<>();
        boolean porPrecio = "precio".equals(trimKey(sort));
        String sql = "SELECT a.codigo, a.nombre, a.codigobarra, f.nombre, d.nombre,"
                + " " + RESUMEN_STOCK + ", a.stockoptimo"
                + RESUMEN_FROM
                + (porPrecio ? precioJoin() : "")
                + resumenWhere(vista, familiaId, departamentoId, q, params)
                + resumenOrderBy(sort, dir);
        try {
            Query query = consulta(sql, params);
            query.setFirstResult(Math.max(offset, 0));
            query.setMaxResults(Math.max(size, 1));
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.warn("Error paginando el resumen de inventario: " + e.getMessage()
                    + " | source=InventarioService.pageResumenInventario()"
                    + " | vista=" + vista + " | familia=" + familiaId
                    + " | departamento=" + departamentoId + " | q=" + q
                    + " | sort=" + sort + " | dir=" + dir
                    + " | offset=" + offset + " | size=" + size
                    + " | despues=" + e.getMessage());
            return List.of();
        }
    }

    /**
     * Last price of each requested article (latest row = highest id), fetched
     * in ONE query for the visible page only.
     *
     * <p>{@code Articulos.precios} is EAGER, so the previous path loaded the
     * price history of all 252k articles just to render 15 rows. This is a
     * projection: no {@code Articulos}/{@code ArticuloPrecio} entity is
     * hydrated, so no further EAGER cascade can fire.</p>
     *
     * @param codigos article codes of the current page; empty means "no query"
     * @return code → {@code precio_final} (the value itself may be null: the
     *         article has a price row without a final price)
     */
    public @Nonnull Map<Long, BigDecimal> ultimosPreciosPorArticulo(@Nonnull List<Long> codigos) {
        if (codigos.isEmpty()) {
            return Map.of();
        }
        String jpql = "SELECT p.articulo.codigo, p.precioFinal"
                + " FROM ArticuloPrecio p"
                + " WHERE p.articulo.codigo IN :codigos"
                + " ORDER BY p.articulo.codigo ASC, p.id DESC";
        try {
            List<Object[]> filas = em.createQuery(jpql, Object[].class)
                    .setParameter("codigos", codigos)
                    .getResultList();
            Map<Long, BigDecimal> precios = new HashMap<>(filas.size());
            for (Object[] fila : filas) {
                // ORDER BY id DESC: the first row per article is its last
                // price. containsKey (not putIfAbsent) keeps a null final
                // price from being replaced by an older row.
                long codigo = numero(fila[0]);
                if (!precios.containsKey(codigo)) {
                    precios.put(codigo, (BigDecimal) fila[1]);
                }
            }
            return precios;
        } catch (PersistenceException e) {
            LOG.warn("Error leyendo los ultimos precios del resumen de inventario: " + e.getMessage()
                    + " | source=InventarioService.ultimosPreciosPorArticulo()"
                    + " | articulos=" + codigos.size()
                    + " | despues=" + e.getMessage());
            return Map.of();
        }
    }

    /**
     * Stock total by family for the current resumen filters, grouped in SQL.
     * The page used to accumulate this while iterating every article.
     *
     * <p>Label parity with the old loop: a null/blank family name renders as
     * {@code "Sin familia"}; any other name is kept verbatim (no trimming), so
     * families that only differ by surrounding spaces stay distinct, as they
     * did in the old in-memory map.</p>
     *
     * @return rows of {@code [etiqueta, total]}, ordered by label
     */
    @SuppressWarnings("unchecked")
    public @Nonnull List<Object[]> stockResumenPorFamilia(
            @Nullable String vista, @Nullable Integer familiaId,
            @Nullable Integer departamentoId, @Nullable String q) {
        Map<String, Object> params = new LinkedHashMap<>();
        String sql = "SELECT CASE WHEN f.nombre IS NULL OR BTRIM(f.nombre) = ''"
                + " THEN 'Sin familia' ELSE f.nombre END AS etiqueta"
                + ", COALESCE(SUM(" + RESUMEN_STOCK + "), 0) AS total"
                + RESUMEN_FROM
                + resumenWhere(vista, familiaId, departamentoId, q, params)
                + " GROUP BY 1 ORDER BY 1";
        try {
            return consulta(sql, params).getResultList();
        } catch (PersistenceException e) {
            LOG.warn("Error agrupando stock por familia del resumen de inventario: " + e.getMessage()
                    + " | source=InventarioService.stockResumenPorFamilia()"
                    + " | vista=" + vista + " | familia=" + familiaId
                    + " | departamento=" + departamentoId + " | q=" + q
                    + " | despues=" + e.getMessage());
            return List.of();
        }
    }

    /**
     * Shared WHERE for the resumen COUNT/page/chart. Mirrors the filters the
     * resource previously applied in memory:
     *
     * <ul>
     *   <li>{@code a.status = true} — the old {@code ListAllEnabled()}.</li>
     *   <li>{@code vista}: {@code cero} → stock = 0, {@code negativos} →
     *       stock &lt; 0; anything else (including null/unknown) is "todos".</li>
     *   <li>{@code familiaId}/{@code departamentoId}: exact id, ignored when
     *       null or &le;0 (the old helper did the same).</li>
     *   <li>{@code q}: case-insensitive {@code contains} over exactly the five
     *       fields of {@code ResumenResource.pasaTexto} — codigo as text,
     *       nombre, codigoBarra, familia.nombre and departamento.nombre. The
     *       term is passed as an escaped LIKE pattern, never concatenated.</li>
     * </ul>
     */
    private static @Nonnull String resumenWhere(
            @Nullable String vista, @Nullable Integer familiaId,
            @Nullable Integer departamentoId, @Nullable String q,
            @Nonnull Map<String, Object> params) {
        List<String> predicates = new ArrayList<>();
        predicates.add("a.status = true");
        String vistaKey = trimKey(vista);
        if ("cero".equals(vistaKey)) {
            predicates.add(RESUMEN_STOCK + " = 0");
        } else if ("negativos".equals(vistaKey)) {
            predicates.add(RESUMEN_STOCK + " < 0");
        }
        if (familiaId != null && familiaId > 0) {
            predicates.add("a.familia_id = :familiaId");
            params.put("familiaId", familiaId);
        }
        if (departamentoId != null && departamentoId > 0) {
            predicates.add("a.departamento_id = :departamentoId");
            params.put("departamentoId", departamentoId);
        }
        String term = trimKey(q);
        if (term != null) {
            String like = "LIKE LOWER(:q) ESCAPE '" + LIKE_ESCAPE + "'";
            predicates.add("(LOWER(CAST(a.codigo AS text)) " + like
                    + " OR LOWER(a.nombre) " + like
                    + " OR LOWER(a.codigobarra) " + like
                    + " OR LOWER(f.nombre) " + like
                    + " OR LOWER(d.nombre) " + like + ")");
            params.put("q", likePattern(term));
        }
        return " WHERE " + String.join(" AND ", predicates);
    }

    /**
     * Whitelisted ORDER BY for {@link #pageResumenInventario}. Only the
     * data-table column keys are interpolated; the request value never reaches
     * the SQL. Text keys sort on {@code LOWER(...)} to keep the old
     * case-insensitive comparator and familia/departamento sort on their
     * displayed value ({@code "-"} when null). Every key ends in
     * {@code a.codigo ASC} as a deterministic tiebreak, so a page can never
     * shuffle between requests. PostgreSQL's default NULL placement (ASC last
     * / DESC first) matches the previous nulls behaviour.
     */
    private static @Nonnull String resumenOrderBy(@Nullable String sort, @Nullable String dir) {
        String key = trimKey(sort);
        String direction = "desc".equalsIgnoreCase(dir) ? " DESC" : " ASC";
        if (key == null) {
            return " ORDER BY a.codigo ASC";
        }
        String expression = switch (key) {
            case "codigo" -> "a.codigo";
            case "nombre" -> "LOWER(a.nombre)";
            case "codigoBarra" -> "LOWER(a.codigobarra)";
            case "familia" -> "COALESCE(LOWER(NULLIF(f.nombre, '')), '-')";
            case "departamento" -> "COALESCE(LOWER(NULLIF(d.nombre, '')), '-')";
            case "stockActual" -> RESUMEN_STOCK;
            case "stockOptimo" -> "a.stockoptimo";
            case "precio" -> "lp.precio_final";
            default -> null;
        };
        if (expression == null) {
            return " ORDER BY a.codigo ASC";
        }
        return "codigo".equals(key)
                ? " ORDER BY " + expression + direction
                : " ORDER BY " + expression + direction + ", a.codigo ASC";
    }

    /**
     * Latest price row per article (highest id), the same element
     * {@code Articulos.getLastPrecio()} exposes. Aggregated once for the whole
     * price sort instead of a correlated subquery evaluated per article.
     */
    private static @Nonnull String precioJoin() {
        return " LEFT JOIN (SELECT ap.articulo_id AS art, MAX(ap.id) AS maxid"
                + " FROM articuloprecio ap GROUP BY ap.articulo_id) ult ON ult.art = a.codigo"
                + " LEFT JOIN articuloprecio lp ON lp.id = ult.maxid";
    }

    /** Native query with the collected named parameters bound. */
    private @Nonnull Query consulta(@Nonnull String sql, @Nonnull Map<String, Object> params) {
        Query query = em.createNativeQuery(sql);
        for (Map.Entry<String, Object> param : params.entrySet()) {
            query.setParameter(param.getKey(), param.getValue());
        }
        return query;
    }

    /** Long value of a COUNT/SUM cell; 0 for null/unknown types. */
    private static long numero(@Nullable Object valor) {
        return valor instanceof Number number ? number.longValue() : 0L;
    }

    @Nullable
    private static String trimKey(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Builds the {@code %term%} LIKE pattern, escaping {@code !}, {@code %}
     * and {@code _} so a user typing them searches literally (the old filter
     * used {@code String.contains}, not a pattern).
     */
    private static @Nonnull String likePattern(@Nonnull String term) {
        StringBuilder pattern = new StringBuilder(term.length() + 2);
        pattern.append('%');
        for (int i = 0; i < term.length(); i++) {
            char ch = term.charAt(i);
            if (ch == LIKE_ESCAPE || ch == '%' || ch == '_') {
                pattern.append(LIKE_ESCAPE);
            }
            pattern.append(ch);
        }
        pattern.append('%');
        return pattern.toString();
    }

}
