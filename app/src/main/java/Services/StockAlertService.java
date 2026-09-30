package Services;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import Models.Articulos.Articulos;
import Models.Departamento;
import Models.Inventario;
import Models.SugerenciaReposicion;
import Models.AlertaStock;
import Models.Usuarios;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Service for intelligent stock management and automated reordering
 * Calculates optimal stock levels based on sales velocity and creates alerts
 */
@Named
@ApplicationScoped
public class StockAlertService extends GService<AlertaStock> {

    @Inject @Nonnull
    private EntityManager em;

    @Inject @Nonnull
    private InventarioService inventarioService;

    /**
     * Overstock threshold as a multiple of the computed optimal stock: an
     * article alerts when its current stock exceeds
     * {@code optimal * multiploSobrestock}.
     *
     * <p>Default 2. Below ~1.5 the alert fires on normal replenishment cycles
     * (a fresh purchase routinely lands above optimal); above ~3 it only
     * catches extreme dead stock. Tunable without redeploy via
     * {@code mercurius.stock.sobrestock.multiplo}; values below 1 are clamped
     * to 1 (at 1.0 anything above optimal would alert, which is the low-stock
     * check's mirror, not an overstock signal).</p>
     */
    @ConfigProperty(name = "mercurius.stock.sobrestock.multiplo", defaultValue = "2")
    int multiploSobrestock;

    /**
     * Effective overstock multiplier after clamping (see
     * {@link #evaluarSobrestock}: values below 1 behave as 1).
     */
    public int getMultiploSobrestock() {
        return Math.max(1, multiploSobrestock);
    }

    /**
     * Dias de historia minima antes de juzgar un articulo (ambas familias).
     *
     * <p>Un articulo recien creado no tiene velocidad de venta: sin este gate su
     * primera compra entra al barrido y se le calcula un optimo de respaldo contra
     * el que dispara de inmediato. Se resuelve con UNA consulta bulk
     * ({@code MIN(fechaMovimiento)} agrupado por articulo) resuelta a mapa en
     * memoria, no con una query por articulo. Sin movimientos no hay historia:
     * el articulo se salta (antes tampoco alertaba, porque stock 0 caia en el
     * {@code continue} de stock vacio). 0 desactiva el gate.</p>
     */
    @ConfigProperty(name = "mercurius.stock.antiguedad.minima-dias", defaultValue = "7")
    int antiguedadMinimaDias;

    /**
     * Tope de alertas NUEVAS por barrido, contando las dos familias (stock bajo y
     * sobrestock). &lt;=0 = ilimitado.
     *
     * <p>El fix de {@code getCurrentStock} desperto alertas dormidas y la primera
     * corrida post-deploy generaba todo el backlog de una. Con el tope el corte es
     * un {@code break} sobre el orden determinista por codigo: el dedup por tipo
     * garantiza que lo que no cabe hoy se crea en la corrida siguiente
     * (catch-up completo). Los writebacks de {@code stockOptimo} pueden esperar
     * a esa corrida, por eso no es {@code continue}.</p>
     */
    @ConfigProperty(name = "mercurius.stock.lote.maximo-nuevas", defaultValue = "50")
    int maximoNuevasPorBarrido;

    /** Antiguedad minima efectiva en dias (0 = gate desactivado). */
    public int getAntiguedadMinimaDias() {
        return Math.max(0, antiguedadMinimaDias);
    }

    /** Tope efectivo de alertas nuevas por barrido (&lt;=0 = ilimitado). */
    public int getMaximoNuevasPorBarrido() {
        return maximoNuevasPorBarrido;
    }

    @Override
    protected Class<AlertaStock> getEntityClass() {
        return AlertaStock.class;
    }

    /**
     * Calculate optimal stock level based on sales velocity
     * Formula: Average Daily Sales × (Lead Time + Safety Stock Days)
     */
    @Transactional(TxType.SUPPORTS)
    @Nonnull
    public Integer calculateOptimalStock(@Nonnull Articulos articulo) {
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
            return optimoDeRespaldo(articulo);
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
     * Static optimal estimate used when there is no sales velocity to derive
     * one from: twice the safety-stock days, or 14 days when that is null too.
     *
     * <p>Extracted unchanged from {@link #calculateOptimalStock}'s no-movement
     * branch so the overstock check can reuse it (see
     * {@link #evaluarSobrestock}): no behavior change to the low-stock path.</p>
     */
    private static int optimoDeRespaldo(@Nonnull Articulos articulo) {
        return articulo.getDiasStockSeguridad() != null ? articulo.getDiasStockSeguridad() * 2 : 14;
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

        // Una sola consulta para todo el catalogo (el gate de antiguedad no puede
        // costar una query por articulo: el barrido corre tras cada venta, en el
        // planificador y a mano).
        Map<Long, Date> primeraMovimiento = historiaPorArticulo();
        int tope = getMaximoNuevasPorBarrido();
        int nuevas = 0;

        for (Articulos articulo : articulos) {
            // Tope por barrido: se corta, no se sigue. El orden por codigo es
            // determinista y el dedup por tipo hace que lo que queda fuera se cree
            // en la proxima corrida.
            if (tope > 0 && nuevas >= tope) {
                break;
            }

            // Gate de antiguedad (ambas familias): sin historia no hay velocidad que
            // juzgar, y con historia reciente todavia tampoco.
            if (!historiaSuficiente(primeraMovimiento, articulo)) {
                continue;
            }

            // Get current stock level
            Integer currentStock = getCurrentStock(articulo);
            
            if (currentStock == null || currentStock == 0) {
                continue; // Skip if no stock data
            }

            // Calculate optimal stock
            Integer optimalStock = calculateOptimalStock(articulo);
            
            // Check if stock is below optimal level
            if (currentStock < optimalStock && articulo.getEstadoAlertas()) {
                // Deduplication: skip if an active alert already exists for this article
                String checkJpql = "SELECT COUNT(sa) FROM AlertaStock sa WHERE sa.articulo = :articulo AND sa.estado = 'active'";
                Long existingCount = em.createQuery(checkJpql, Long.class)
                        .setParameter("articulo", articulo)
                        .getSingleResult();
                if (existingCount != null && existingCount > 0) {
                    continue; // Active alert already exists — skip
                }

                // Determine alert type
                String alertType;
                if (currentStock == 0) {
                    alertType = "out_of_stock";
                } else {
                    alertType = "low_stock";
                }

                // Create stock alert
                AlertaStock alert = new AlertaStock();
                alert.setArticulo(articulo);
                alert.setTipoAlerta(alertType);
                alert.setCantidadActual(currentStock);
                alert.setCantidadMinima(optimalStock);
                alert.setSugeridoReordenar(calculateReorderQuantity(articulo, currentStock, optimalStock));
                alert.setDepartamento(articulo.getDepartamento());
                alert.setNotas("Alerta generada automáticamente - Stock actual: " + currentStock + 
                              ", Stock óptimo: " + optimalStock);

                em.persist(alert);
                nuevas++;

                // Update article's optimal stock
                articulo.setStockOptimo(optimalStock);
                em.merge(articulo);

                // Create reorder suggestion
                createReorderSuggestion(articulo, currentStock, optimalStock);
            }

            // Overstock: current stock far above optimal ties up capital and,
            // for refrigerated articles, spoilage risk. Same table and lifecycle
            // as the low-stock family (tipoAlerta 'overstock'), evaluated in the
            // same pass so all three triggers (post-sale, scheduler, manual)
            // cover it with no extra full-table scan.
            if (evaluarSobrestock(articulo, currentStock, optimalStock)) {
                nuevas++;
            }
        }
    }

    /**
     * Primera fecha de movimiento por articulo (articulo -&gt; MIN(fechaMovimiento))
     * en UNA sola consulta, para las dos familias de alertas.
     *
     * <p>Se filtran los mismos movimientos que suma {@link #getCurrentStock}
     * (archivados excluidos): la historia que sirve para juzgar tiene que ser la
     * misma que sostiene el stock que se compara. Los movimientos sin articulo se
     * dejan fuera porque no pueden asociarse a ninguno.</p>
     */
    @Nonnull
    private Map<Long, Date> historiaPorArticulo() {
        List<Object[]> filas = em.createQuery(
                        "SELECT i.articulo.codigo, MIN(i.fechaMovimiento) FROM Inventario i "
                                + "WHERE i.status = true AND i.articulo IS NOT NULL "
                                + "GROUP BY i.articulo.codigo",
                        Object[].class)
                .getResultList();
        Map<Long, Date> historia = new HashMap<>();
        for (Object[] fila : filas) {
            Date primera = aFecha(fila[1]);
            if (primera != null && fila[0] instanceof Long codigo) {
                historia.put(codigo, primera);
            }
        }
        return historia;
    }

    /**
     * Gate de antiguedad: el articulo ya tiene historia para ser judgedo.
     *
     * <p>Sin movimientos no hay historia (se salta; antes tampoco alertaba porque
     * el stock 0 caia en el {@code continue} de stock vacio). Con el gate en 0 la
     * comprobacion se desactiva y el filtro de stock vacio sigue siendo el unico
     * descarte.</p>
     */
    private boolean historiaSuficiente(@Nonnull Map<Long, Date> historia, @Nonnull Articulos articulo) {
        int minimoDias = getAntiguedadMinimaDias();
        if (minimoDias <= 0) {
            return true;
        }
        Date primera = historia.get(articulo.getCodigo());
        if (primera == null) {
            return false;
        }
        return System.currentTimeMillis() - primera.getTime() >= minimoDias * 86_400_000L;
    }

    /**
     * Convierte el valor temporal que devuelve {@code MIN(fechaMovimiento)} a
     * {@link Date} (mismo criterio que {@code SeleccionMetodoService.aFecha}:
     * el driver puede entregar {@code java.sql.Date/TimeStamp}, ambos {@link Date}).
     */
    @Nullable
    private static Date aFecha(@Nullable Object valor) {
        if (valor instanceof Date fecha) {
            return fecha;
        }
        if (valor instanceof LocalDateTime instante) {
            return Date.from(instante.atZone(ZoneId.systemDefault()).toInstant());
        }
        if (valor instanceof LocalDate dia) {
            return Date.from(dia.atStartOfDay(ZoneId.systemDefault()).toInstant());
        }
        return null;
    }

    /**
     * Raises an {@code overstock} alert when the current stock exceeds the
     * configured multiple of optimal.
     *
     * <p>Dedup is per tipoAlerta, deliberately narrower than the low-stock
     * check above (which suppresses on ANY active alert for the article): stock
     * cannot be both low and over, but a stale low-stock alert from before a
     * large purchase must not silence a fresh overstock, and vice versa.</p>
     *
     * <p>{@code cantidadMinima} carries the overstock threshold (the maximum
     * before alerting). The column name says "mínima" because the table was
     * born for low-stock; for overstock rows it is the trigger maximum, which
     * is why the UI header reads "Umbral" instead. {@code sugeridoReordenar}
     * stays null — there is nothing to reorder — and renders as "-".</p>
     *
     * <p>Devuelve {@code true} solo si creo una alerta nueva, que es lo que el
     * tope por barrido necesita contabilizar (ver
     * {@link #maximoNuevasPorBarrido}).</p>
     */
    private boolean evaluarSobrestock(@Nonnull Articulos articulo,
                                      @Nonnull Integer currentStock,
                                      @Nonnull Integer optimalStock) {
        if (articulo.getEstadoAlertas() == null || !articulo.getEstadoAlertas()) {
            return false;
        }
        // Without sales velocity the computed optimal is 0 (0 daily sales x
        // any horizon), which would make every unit "overstock" — including a
        // new article's first purchase. Fall back to the static estimate, the
        // same one calculateOptimalStock uses with no movements at all: dead
        // stock still alerts against it, but ordinary receipts do not.
        int optimo = optimalStock != null && optimalStock > 0
                ? optimalStock
                : optimoDeRespaldo(articulo);
        int multiplo = Math.max(1, multiploSobrestock);
        int umbral = optimo * multiplo;
        if (currentStock <= umbral) {
            return false;
        }

        Long existentes = em.createQuery(
                        "SELECT COUNT(sa) FROM AlertaStock sa WHERE sa.articulo = :articulo "
                                + "AND sa.tipoAlerta = 'overstock' AND sa.estado = 'active'",
                        Long.class)
                .setParameter("articulo", articulo)
                .getSingleResult();
        if (existentes != null && existentes > 0) {
            return false;
        }

        AlertaStock alerta = new AlertaStock();
        alerta.setArticulo(articulo);
        alerta.setTipoAlerta("overstock");
        alerta.setCantidadActual(currentStock);
        alerta.setCantidadMinima(umbral);
        alerta.setSugeridoReordenar(null);
        alerta.setDepartamento(articulo.getDepartamento());
        alerta.setNotas("Alerta generada automáticamente - Sobrestock: " + currentStock
                + " unidades frente a un óptimo de " + optimo
                + " (umbral x" + multiplo + " = " + umbral + ")");
        em.persist(alerta);
        return true;
    }

    /**
     * Get current stock level for an article
     */
    /**
     * Current stock level for an article: the sum of its active movements.
     *
     * <p>The SUM is typed {@code BigDecimal} because {@code Inventario.cantidad}
     * is numeric — typing it {@code Long} (as before) throws
     * {@code QueryTypeMismatch} for every article that has ANY movement, which
     * aborted the whole alert sweep past the first stocked article and meant
     * neither low-stock nor overstock alerts ever fired from real data. Only
     * articles with zero movements (SUM over nothing → null → 0) ever got
     * through, which is exactly backwards.</p>
     */
    private Integer getCurrentStock(Articulos articulo) {
        try {
            String jpql = "SELECT SUM(i.cantidad) FROM Inventario i " +
                    "WHERE i.articulo.codigo = :articuloId AND i.status = true " +
                    "GROUP BY i.articulo.codigo";
            TypedQuery<BigDecimal> query = em.createQuery(jpql, BigDecimal.class)
                    .setParameter("articuloId", articulo.getCodigo());

            BigDecimal result = query.getSingleResult();
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
                // Divide in BigDecimal, THEN truncate: dailySales.intValue() is 0
                // for anything under 1 unit/day (e.g. 0.03 for a unit a month),
                // which passed the guard above and divided by zero. Slow sellers
                // are exactly the articles that reach this branch.
                diasSinStock = BigDecimal.valueOf(reorderQuantity)
                        .divide(dailySales, 0, RoundingMode.HALF_UP)
                        .intValue();
            }
        }

        // Create reorder suggestion
        SugerenciaReposicion suggestion = new SugerenciaReposicion();
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
    @Transactional(TxType.SUPPORTS)
    @Nonnull
    public List<AlertaStock> getActiveStockAlerts() {
        String jpql = "SELECT sa FROM AlertaStock sa WHERE sa.estado = 'active' ORDER BY sa.fechaCreacion DESC";
        TypedQuery<AlertaStock> query = em.createQuery(jpql, AlertaStock.class);
        return query.getResultList();
    }

    /**
     * Get all reorder suggestions
     */
    @Transactional(TxType.SUPPORTS)
    @Nonnull
    public List<SugerenciaReposicion> getAllReorderSuggestions() {
        String jpql = "SELECT rs FROM SugerenciaReposicion rs ORDER BY rs.prioridad DESC, rs.fechaCreacion DESC";
        TypedQuery<SugerenciaReposicion> query = em.createQuery(jpql, SugerenciaReposicion.class);
        return query.getResultList();
    }

    /**
     * Get reorder suggestions by priority
     */
    @Transactional(TxType.SUPPORTS)
    @Nonnull
    public List<SugerenciaReposicion> getReorderSuggestionsByPriority(@Nonnull String priority) {
        String jpql = "SELECT rs FROM SugerenciaReposicion rs WHERE rs.prioridad = :priority ORDER BY rs.fechaCreacion DESC";
        TypedQuery<SugerenciaReposicion> query = em.createQuery(jpql, SugerenciaReposicion.class)
                .setParameter("priority", priority);
        return query.getResultList();
    }

    /**
     * Acknowledge a stock alert
     */
    @Transactional
    public void acknowledgeStockAlert(@Nonnull AlertaStock alert, @Nonnull Usuarios user, @Nonnull String notes) {
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
    public void resolveStockAlert(@Nonnull AlertaStock alert, @Nonnull Usuarios user, @Nonnull String notes) {
        alert.setEstado("resolved");
        alert.setFechaResolucion(new Date());
        alert.setUsuarioResolucion(user);
        alert.setNotas(notes);
        em.merge(alert);
    }

    /**
     * Get stock alerts by department
     */
    @Transactional(TxType.SUPPORTS)
    @Nonnull
    public List<AlertaStock> getStockAlertsByDepartment(@Nonnull Departamento departamento) {
        String jpql = "SELECT sa FROM AlertaStock sa WHERE sa.departamento = :departamento AND sa.estado = 'active' ORDER BY sa.fechaCreacion DESC";
        TypedQuery<AlertaStock> query = em.createQuery(jpql, AlertaStock.class)
                .setParameter("departamento", departamento);
        return query.getResultList();
    }

    /**
     * Get alert statistics
     */
    @Transactional(TxType.SUPPORTS)
    @Nonnull
    public Map<String, Integer> getAlertStatistics() {
        Map<String, Integer> stats = new HashMap<>();
        
        String jpql = "SELECT sa.tipoAlerta, COUNT(sa) FROM AlertaStock sa " +
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