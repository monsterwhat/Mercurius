package Services;

import Models.ComprobantesRecibidos;
import Models.Departamento;
import Models.DepartamentoMetrico;
import Utils.DiffUtils;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.logging.Logger;

/**
 * Servicio para calcular y almacenar métricas de rendimiento de proveedores (Departamentos).
 *
 * @author Al
 */
@Named
@ApplicationScoped
public class DepartamentoMetricoService extends GService<DepartamentoMetrico> {

    private static final Logger LOG = Logger.getLogger(DepartamentoMetricoService.class.getName());

    /** Pesos para el cálculo del score */
    private static final double WEIGHT_ON_TIME_DELIVERY = 0.40;
    private static final double WEIGHT_PAYMENT_RELIABILITY = 0.30;
    private static final double WEIGHT_VOLUME = 0.20;
    private static final double WEIGHT_ARTICLE_DIVERSITY = 0.10;

    @Inject
    @Nonnull
    DepartamentoService departamentoService;

    @Inject
    @Nonnull
    ComprobantesRecibidosService comprobantesRecibidosService;

    @Override
    protected @Nonnull Class<DepartamentoMetrico> getEntityClass() {
        return DepartamentoMetrico.class;
    }

    @PostConstruct
    public void init() {
    }

    /**
     * Calcula las métricas para un departamento específico y las persiste.
     */
    @Transactional
    public void calcularMetricas(@Nonnull Departamento dept) {
        try {
            // 1. Find existing metric or create new
            DepartamentoMetrico metrico = findByDepartamento(dept);
            if (metrico == null) {
                metrico = new DepartamentoMetrico();
                metrico.setDepartamento(dept);
            } else {
                // Snapshot before alert
                String antes = DiffUtils.snapshotEntity(metrico);
                metrico.setFechaCalculo(new Date());
            }

            // 2. Find all ComprobantesRecibidos that match this department (by emisor nombre)
            List<ComprobantesRecibidos> facturas = findFacturasForDepartamento(dept);

            // 3. Calculate invoice metrics
            int totalFacturas = facturas.size();
            int pagadas = 0;
            BigDecimal montoTotal = BigDecimal.ZERO;

            for (ComprobantesRecibidos cr : facturas) {
                // Count paid
                if (Boolean.TRUE.equals(cr.getPaid())) {
                    pagadas++;
                }

                // Sum total amount
                if (cr.getResumen() != null && cr.getResumen().getTotalComprobante() != null) {
                    montoTotal = montoTotal.add(cr.getResumen().getTotalComprobante());
                }
            }

            BigDecimal montoPromedio = totalFacturas > 0
                    ? montoTotal.divide(BigDecimal.valueOf(totalFacturas), 5, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            // 4. Calculate delivery metrics from invoices
            double tiempoEntregaPromedio = calcularTiempoEntregaPromedio(facturas, dept);
            double tasaOnTime = calcularTasaOnTimeDelivery(facturas, dept);

            // 5. Count distinct articles purchased from this department
            int articulosComprados = countDistinctArticlesForDepartamento(dept);

            // 6. Calculate score
            double score = calcularScore(tasaOnTime, pagadas, totalFacturas, montoTotal, articulosComprados);

            // 7. Update metric entity
            metrico.setTotalFacturasRecibidas(totalFacturas);
            metrico.setFacturasPagadas(pagadas);
            metrico.setMontoTotalCompras(montoTotal);
            metrico.setMontoPromedioFactura(montoPromedio);
            metrico.setTiempoEntregaPromedio(tiempoEntregaPromedio);
            metrico.setTasaOnTimeDelivery(tasaOnTime);
            metrico.setArticulosComprados(articulosComprados);
            metrico.setScore(score);
            metrico.setFechaCalculo(new Date());

            // 8. Persist
            if (metrico.getId() == null) {
                create(metrico);
            } else {
                update(metrico);
            }

            alertasService.registrarAlerta("Info",
                    "Métricas calculadas para proveedor: " + dept.getNombre() + " - Score: " + String.format("%.1f", score),
                    null, 0, "DepartamentoMetricoService.calcularMetricas()",
                    null, DiffUtils.snapshotEntity(metrico));

        } catch (Exception e) {
            alertasService.registrarAlerta("Error",
                    "Error calculando métricas para departamento " + dept.getNombre() + ": " + e.getMessage(),
                    null, 0, "DepartamentoMetricoService.calcularMetricas()",
                    null, e.getMessage());
        }
    }

    /**
     * Recalcula métricas para todos los departamentos activos.
     */
    @Transactional
    public void calcularTodasLasMetricas() {
        try {
            List<Departamento> departamentos = departamentoService.listAllActive();
            if (departamentos != null) {
                for (Departamento dept : departamentos) {
                    calcularMetricas(dept);
                }
            }
            alertasService.registrarAlerta("Info",
                    "Métricas recalculadas para todos los departamentos activos",
                    null, 0, "DepartamentoMetricoService.calcularTodasLasMetricas()",
                    null, null);
        } catch (Exception e) {
            alertasService.registrarAlerta("Error",
                    "Error recalculando todas las métricas: " + e.getMessage(),
                    null, 0, "DepartamentoMetricoService.calcularTodasLasMetricas()",
                    null, e.getMessage());
        }
    }

    @Override
    @Nonnull
    public List<DepartamentoMetrico> listAll() {
        try {
            TypedQuery<DepartamentoMetrico> query = em.createQuery(
                    "SELECT m FROM DepartamentoMetrico m LEFT JOIN FETCH m.departamento d ORDER BY m.score DESC",
                    DepartamentoMetrico.class);
            return query.getResultList();
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error",
                    "Error listing DepartamentoMetrico: " + e.getMessage(),
                    null, 0, "DepartamentoMetricoService.listAll()", null, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Nullable
    public DepartamentoMetrico findByDepartamento(@Nonnull Departamento dept) {
        try {
            TypedQuery<DepartamentoMetrico> query = em.createQuery(
                    "SELECT m FROM DepartamentoMetrico m WHERE m.departamento = :dept",
                    DepartamentoMetrico.class);
            query.setParameter("dept", dept);
            List<DepartamentoMetrico> results = query.getResultList();
            return results.isEmpty() ? null : results.get(0);
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error",
                    "Error finding DepartamentoMetrico by Departamento: " + e.getMessage(),
                    null, 0, "DepartamentoMetricoService.findByDepartamento()",
                    null, e.getMessage());
            return null;
        }
    }

    /**
     * Returns the sum of montoTotalCompras across all stored metrics.
     */
    @Nonnull
    public BigDecimal sumMontoTotalCompras() {
        try {
            TypedQuery<BigDecimal> query = em.createQuery(
                    "SELECT COALESCE(SUM(m.montoTotalCompras), 0) FROM DepartamentoMetrico m",
                    BigDecimal.class);
            BigDecimal result = query.getSingleResult();
            return result != null ? result : BigDecimal.ZERO;
        } catch (PersistenceException e) {
            return BigDecimal.ZERO;
        }
    }

    /**
     * Returns the average score across all stored metrics.
     */
    public double avgScore() {
        try {
            TypedQuery<Double> query = em.createQuery(
                    "SELECT COALESCE(AVG(m.score), 0) FROM DepartamentoMetrico m",
                    Double.class);
            Double result = query.getSingleResult();
            return result != null ? result : 0.0;
        } catch (PersistenceException e) {
            return 0.0;
        }
    }

    // ─── Private helpers ────────────────────────────────────────────────

    @Nonnull
    private List<ComprobantesRecibidos> findFacturasForDepartamento(@Nonnull Departamento dept) {
        try {
            // Match ComprobantesRecibidos by Emisor nombre = Departamento nombre
            TypedQuery<ComprobantesRecibidos> query = em.createQuery(
                    "SELECT DISTINCT cr FROM ComprobantesRecibidos cr " +
                            "LEFT JOIN FETCH cr.resumen " +
                            "LEFT JOIN FETCH cr.encabezado enc " +
                            "LEFT JOIN FETCH enc.emisor em " +
                            "WHERE em.nombre = :nombre OR em.nombreComercial = :nombre",
                    ComprobantesRecibidos.class);
            query.setParameter("nombre", dept.getNombre());
            return query.getResultList();
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error",
                    "Error finding facturas for departamento: " + e.getMessage(),
                    null, 0, "DepartamentoMetricoService.findFacturasForDepartamento()",
                    null, e.getMessage());
            return Collections.emptyList();
        }
    }

    private double calcularTiempoEntregaPromedio(@Nonnull List<ComprobantesRecibidos> facturas, @Nonnull Departamento dept) {
        if (dept.getTiempoEntregaDias() == null || dept.getTiempoEntregaDias() <= 0) {
            // No expected lead time configured, return 0
            return 0.0;
        }

        // Use the department's configured expected delivery time as reference
        // Actual delivery time estimation: use the difference between processing dates
        // Since we don't have explicit delivery timestamps, estimate from payment timing
        int expectedDays = dept.getTiempoEntregaDias();
        double totalActual = 0;
        int count = 0;

        for (ComprobantesRecibidos cr : facturas) {
            if (cr.getEncabezado() != null && cr.getEncabezado().getFechaEmision() != null) {
                // Estimate actual delivery as 90% of expected (heuristic when no real delivery date)
                totalActual += expectedDays * 0.9;
                count++;
            }
        }

        return count > 0 ? totalActual / count : (double) expectedDays;
    }

    private double calcularTasaOnTimeDelivery(@Nonnull List<ComprobantesRecibidos> facturas, @Nonnull Departamento dept) {
        if (facturas.isEmpty()) {
            return 0.0;
        }

        int expectedDays = dept.getTiempoEntregaDias() != null ? dept.getTiempoEntregaDias() : 15;
        int onTime = 0;

        for (ComprobantesRecibidos cr : facturas) {
            if (cr.getEncabezado() != null && cr.getEncabezado().getFechaEmision() != null) {
                LocalDateTime fechaEmision = cr.getEncabezado().getFechaEmision();
                // Estimate: invoices processed within expected delivery window are "on time"
                // Use processed date if available, otherwise assume on time
                // Since we lack explicit delivery dates, assume all processed invoices were delivered on time
                // unless the department's expected lead time is very short
                if (expectedDays <= 0 || Boolean.TRUE.equals(cr.getProcessed()) || Boolean.TRUE.equals(cr.getPaid())) {
                    onTime++;
                }
            }
        }

        return facturas.isEmpty() ? 0.0 : ((double) onTime / facturas.size()) * 100.0;
    }

    private int countDistinctArticlesForDepartamento(@Nonnull Departamento dept) {
        try {
            TypedQuery<Long> query = em.createQuery(
                    "SELECT COUNT(DISTINCT a.codigo) FROM Articulos a WHERE a.departamento = :dept AND a.status = true",
                    Long.class);
            query.setParameter("dept", dept);
            Long count = query.getSingleResult();
            return count != null ? count.intValue() : 0;
        } catch (PersistenceException e) {
            return 0;
        }
    }

    /**
     * Calcula el score ponderado de rendimiento (0-100).
     *
     * - On-time delivery rate (40%)
     * - Payment reliability: % facturas pagadas (30%)
     * - Volume: monto total normalizado (20%)
     * - Article diversity: artículos comprados normalizado (10%)
     */
    private double calcularScore(double tasaOnTime, int pagadas, int totalFacturas,
                                 BigDecimal montoTotal, int articulosComprados) {
        double onTimeScore = Math.min(tasaOnTime, 100.0);

        double paymentScore = totalFacturas > 0
                ? ((double) pagadas / totalFacturas) * 100.0
                : 0.0;

        // Normalize volume: assume max 10,000,000 CRC is 100%
        double volumeScore = montoTotal.doubleValue() > 0
                ? Math.min((montoTotal.doubleValue() / 10_000_000.0) * 100.0, 100.0)
                : 0.0;

        // Normalize diversity: assume 50+ articles is 100%
        double diversityScore = Math.min(((double) articulosComprados / 50.0) * 100.0, 100.0);

        double score = (onTimeScore * WEIGHT_ON_TIME_DELIVERY)
                + (paymentScore * WEIGHT_PAYMENT_RELIABILITY)
                + (volumeScore * WEIGHT_VOLUME)
                + (diversityScore * WEIGHT_ARTICLE_DIVERSITY);

        return Math.round(score * 10.0) / 10.0; // Round to 1 decimal
    }
}
