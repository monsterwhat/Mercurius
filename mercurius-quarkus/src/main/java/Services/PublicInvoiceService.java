package Services;

import Models.CierreCaja;
import Models.Clients;
import Models.ComprobantesEmitidos;
import Models.ComprobantesRecibidos;
import Models.NotaCredito;
import Models.TipoCambio;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TypedQuery;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin @ApplicationScoped facade over existing services for public API consumption.
 * <p>
 * This service is a read-only pass-through layer intended for JAX-RS controllers.
 * It does NOT contain business logic — it delegates to existing @ApplicationScoped
 * services and shapes the results as Maps for the controller layer.
 * <p>
 * The controllers will add OpenAPI annotations and HTTP mapping (T25).
 *
 * @author Mercurius Team
 */
@Named
@ApplicationScoped
public class PublicInvoiceService {

    @PersistenceContext
    @Nonnull
    protected EntityManager em;

    @Inject @Nonnull
    private ComprobantesEmitidosService comprobantesEmitidosService;

    @Inject @Nonnull
    private ComprobantesRecibidosService comprobantesRecibidosService;

    @Inject @Nonnull
    private ClientService clientService;

    @Inject @Nonnull
    private TipoCambioService tipoCambioService;

    @Inject @Nonnull
    private ArticulosService articulosService;

    @Inject @Nonnull
    private NotaCreditoService notaCreditoService;

    @Inject @Nonnull
    private CierreCajaService cierreCajaService;

    @Inject @Nonnull
    private AlertasService alertasService;

    // ────────────────────────────────────────────────────────────────────
    // 1. getInvoicedInvoices — issued invoices list with pagination
    // ────────────────────────────────────────────────────────────────────

    /**
     * Returns issued (emitted) invoices with optional pagination.
     *
     * @param page page number (1-based), null for all
     * @param pageSize items per page, null for default
     * @return map with "data" (List of ComprobantesEmitidos) and "total" (Long count)
     */
    @Nonnull
    public Map<String, Object> getInvoicedInvoices(@Nullable Integer page, @Nullable Integer pageSize) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (page != null && pageSize != null) {
                int offset = (page - 1) * pageSize;
                List<ComprobantesEmitidos> data = comprobantesEmitidosService.listPage(offset, pageSize);
                Long total = comprobantesEmitidosService.count();
                result.put("data", data);
                result.put("total", total);
            } else {
                List<ComprobantesEmitidos> data = comprobantesEmitidosService.listAll();
                result.put("data", data);
                result.put("total", (long) data.size());
            }
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error getting invoiced invoices: " + e.getMessage(),
                null, 0, "PublicInvoiceService.getInvoicedInvoices()", null, e.getMessage());
            result.put("data", Collections.emptyList());
            result.put("total", 0L);
        }
        return result;
    }

    // ────────────────────────────────────────────────────────────────────
    // 2. getReceivedInvoices — received invoices list with pagination
    // ────────────────────────────────────────────────────────────────────

    /**
     * Returns received (recibidos) invoices with optional pagination.
     *
     * @param page page number (1-based), null for all
     * @param pageSize items per page, null for default
     * @return map with "data" (List of ComprobantesRecibidos) and "total" (Long count)
     */
    @Nonnull
    public Map<String, Object> getReceivedInvoices(@Nullable Integer page, @Nullable Integer pageSize) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (page != null && pageSize != null) {
                int offset = (page - 1) * pageSize;
                List<ComprobantesRecibidos> data = comprobantesRecibidosService.listPage(offset, pageSize);
                Long total = comprobantesRecibidosService.count();
                result.put("data", data);
                result.put("total", total);
            } else {
                List<ComprobantesRecibidos> data = comprobantesRecibidosService.listAll();
                result.put("data", data);
                result.put("total", (long) data.size());
            }
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error getting received invoices: " + e.getMessage(),
                null, 0, "PublicInvoiceService.getReceivedInvoices()", null, e.getMessage());
            result.put("data", Collections.emptyList());
            result.put("total", 0L);
        }
        return result;
    }

    // ────────────────────────────────────────────────────────────────────
    // 3. getInvoiceDetail — full invoice with lines, taxes, discounts
    // ────────────────────────────────────────────────────────────────────

    /**
     * Returns a full issued invoice by ID with all nested relationships
     * (encabezado, detalles, resumen, lineas, impuestos, descuentos).
     *
     * @param invoiceId the ComprobantesEmitidos.id
     * @return the full entity or null if not found
     */
    @Nullable
    public ComprobantesEmitidos getInvoiceDetail(@Nonnull Long invoiceId) {
        try {
            return comprobantesEmitidosService.find(invoiceId);
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error getting invoice detail: " + e.getMessage(),
                null, 0, "PublicInvoiceService.getInvoiceDetail()", null, e.getMessage());
            return null;
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 4. getInvoiceSummary — totals only
    // ────────────────────────────────────────────────────────────────────

    /**
     * Returns the ResumenFactura (totals summary) for an issued invoice.
     *
     * @param invoiceId the ComprobantesEmitidos.id
     * @return map with summary fields, or empty map if not found
     */
    @Nonnull
    public Map<String, Object> getInvoiceSummary(@Nonnull Long invoiceId) {
        Map<String, Object> summary = new HashMap<>();
        try {
            ComprobantesEmitidos invoice = comprobantesEmitidosService.find(invoiceId);
            if (invoice == null || invoice.getResumen() == null) {
                return summary;
            }
            var resumen = invoice.getResumen();
            summary.put("totalGravado", resumen.getTotalGravado());
            summary.put("totalExento", resumen.getTotalExento());
            summary.put("totalExonerado", resumen.getTotalExonerado());
            summary.put("totalVenta", resumen.getTotalVenta());
            summary.put("totalDescuentos", resumen.getTotalDescuentos());
            summary.put("totalVentaNeta", resumen.getTotalVentaNeta());
            summary.put("totalImpuesto", resumen.getTotalImpuesto());
            summary.put("totalIVADevuelto", resumen.getTotalIVADevuelto());
            summary.put("totalOtrosCargos", resumen.getTotalOtrosCargos());
            summary.put("totalComprobante", resumen.getTotalComprobante());
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error getting invoice summary: " + e.getMessage(),
                null, 0, "PublicInvoiceService.getInvoiceSummary()", null, e.getMessage());
        }
        return summary;
    }

    // ────────────────────────────────────────────────────────────────────
    // 5. getPayments — payment list for an invoice
    // ────────────────────────────────────────────────────────────────────

    /**
     * Returns the payment methods (MedioPagoR) from the ResumenFactura
     * for a given issued invoice.
     *
     * @param invoiceId the ComprobantesEmitidos.id
     * @return list of payment entries, or empty list if not found
     */
    @Nonnull
    public List<?> getPayments(@Nonnull Long invoiceId) {
        try {
            ComprobantesEmitidos invoice = comprobantesEmitidosService.find(invoiceId);
            if (invoice == null || invoice.getResumen() == null || invoice.getResumen().getMediosPago() == null) {
                return Collections.emptyList();
            }
            return invoice.getResumen().getMediosPago();
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error getting payments: " + e.getMessage(),
                null, 0, "PublicInvoiceService.getPayments()", null, e.getMessage());
            return Collections.emptyList();
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 6. getSuppliers — supplier list (clients acting as suppliers)
    // ────────────────────────────────────────────────────────────────────

    /**
     * Returns suppliers. In this system, suppliers are stored as Clients
     * (via ComprobantesRecibidos). Returns all clients; the controller
     * can filter by role or by having received invoices.
     *
     * @param page page number (1-based), null for all
     * @param pageSize items per page, null for default
     * @return map with "data" (List of Clients) and "total" (Long count)
     */
    @Nonnull
    public Map<String, Object> getSuppliers(@Nullable Integer page, @Nullable Integer pageSize) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (page != null && pageSize != null) {
                int offset = (page - 1) * pageSize;
                List<Clients> data = clientService.listPage(offset, pageSize);
                Long total = clientService.count();
                result.put("data", data);
                result.put("total", total);
            } else {
                List<Clients> data = clientService.listAll();
                result.put("data", data);
                result.put("total", (long) data.size());
            }
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error getting suppliers: " + e.getMessage(),
                null, 0, "PublicInvoiceService.getSuppliers()", null, e.getMessage());
            result.put("data", Collections.emptyList());
            result.put("total", 0L);
        }
        return result;
    }

    // ────────────────────────────────────────────────────────────────────
    // 7. getInventoryValuation — current stock values
    // ────────────────────────────────────────────────────────────────────

    /**
     * Returns current stock levels with valuation.
     * Joins ArticuloStock (codigoBarra) with Articulos (lastPrecio) to compute values.
     *
     * @return map with "items" (List of stock+valuation records) and "totalValue" (BigDecimal)
     */
    @Nonnull
    public Map<String, Object> getInventoryValuation() {
        Map<String, Object> result = new HashMap<>();
        try {
            // Join ArticuloStock with Articulos via codigoBarra to get pricing
            TypedQuery<Object[]> query = em.createQuery(
                "SELECT s.codigoBarra, s.stock, a.nombre, " +
                "a.lastPrecio.precioFinal, a.lastPrecio.precioCostoSinIVA " +
                "FROM ArticuloStock s " +
                "JOIN Articulos a ON a.codigoBarra = s.codigoBarra " +
                "WHERE s.stock > 0",
                Object[].class
            );
            List<Object[]> rows = query.getResultList();

            BigDecimal totalValue = BigDecimal.ZERO;
            List<Map<String, Object>> items = new ArrayList<>();
            for (Object[] row : rows) {
                String codigoBarra = (String) row[0];
                BigDecimal stock = (BigDecimal) row[1];
                String nombre = (String) row[2];
                BigDecimal precioFinal = (BigDecimal) row[3];

                Map<String, Object> item = new HashMap<>();
                item.put("codigoBarra", codigoBarra);
                item.put("stock", stock);
                item.put("nombre", nombre);
                item.put("precioUnitario", precioFinal);

                BigDecimal lineValue = BigDecimal.ZERO;
                if (precioFinal != null && stock != null) {
                    lineValue = precioFinal.multiply(stock);
                }
                item.put("valorTotal", lineValue);
                totalValue = totalValue.add(lineValue);
                items.add(item);
            }

            result.put("items", items);
            result.put("totalValue", totalValue);
            result.put("itemCount", items.size());
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error getting inventory valuation: " + e.getMessage(),
                null, 0, "PublicInvoiceService.getInventoryValuation()", null, e.getMessage());
            result.put("items", Collections.emptyList());
            result.put("totalValue", BigDecimal.ZERO);
            result.put("itemCount", 0);
        }
        return result;
    }

    // ────────────────────────────────────────────────────────────────────
    // 8. getExchangeRates — currency rates
    // ────────────────────────────────────────────────────────────────────

    /**
     * Returns the current exchange rate (TipoCambio) from Hacienda API / cache.
     *
     * @return the TipoCambio entity, or null if unavailable
     */
    @Nullable
    public TipoCambio getExchangeRates() {
        try {
            return tipoCambioService.getNewestTipoCambio();
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error getting exchange rates: " + e.getMessage(),
                null, 0, "PublicInvoiceService.getExchangeRates()", null, e.getMessage());
            return null;
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 9. getCashRegister — cash register entries
    // ────────────────────────────────────────────────────────────────────

    /**
     * Returns cash register (CierreCaja) entries with optional date filtering.
     *
     * @param page page number (1-based), null for all
     * @param pageSize items per page, null for default
     * @return map with "data" (List of CierreCaja) and "total" (Long count)
     */
    @Nonnull
    public Map<String, Object> getCashRegister(@Nullable Integer page, @Nullable Integer pageSize) {
        Map<String, Object> result = new HashMap<>();
        try {
            if (page != null && pageSize != null) {
                int offset = (page - 1) * pageSize;
                List<CierreCaja> data = cierreCajaService.listPage(offset, pageSize);
                Long total = cierreCajaService.count();
                result.put("data", data);
                result.put("total", total);
            } else {
                List<CierreCaja> data = cierreCajaService.listAll();
                result.put("data", data);
                result.put("total", (long) data.size());
            }
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error getting cash register: " + e.getMessage(),
                null, 0, "PublicInvoiceService.getCashRegister()", null, e.getMessage());
            result.put("data", Collections.emptyList());
            result.put("total", 0L);
        }
        return result;
    }

    // ────────────────────────────────────────────────────────────────────
    // 10. getCreditNotes — credit notes list
    // ────────────────────────────────────────────────────────────────────

    /**
     * Returns all credit notes (Notas de Crédito).
     *
     * @return list of NotaCredito entities
     */
    @Nonnull
    public List<NotaCredito> getCreditNotes() {
        try {
            List<NotaCredito> notes = notaCreditoService.listAll();
            return notes != null ? notes : Collections.emptyList();
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error getting credit notes: " + e.getMessage(),
                null, 0, "PublicInvoiceService.getCreditNotes()", null, e.getMessage());
            return Collections.emptyList();
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 11. getProfitMargins — profit margin reports
    // ────────────────────────────────────────────────────────────────────

    /**
     * Returns profit margin data per article.
     * Delegates to ProfitAnalysisService for real-time margin calculations.
     *
     * @param page page number (1-based), null for all
     * @param pageSize items per page, null for default
     * @return map with "data" (List of profit records) and "total" (Long count)
     */
    @Nonnull
    public Map<String, Object> getProfitMargins(@Nullable Integer page, @Nullable Integer pageSize) {
        Map<String, Object> result = new HashMap<>();
        try {
            List<Models.Articulos.Articulos> articles = articulosService.listAll();
            if (articles == null) articles = Collections.emptyList();

            List<Map<String, Object>> margins = new ArrayList<>();
            for (Models.Articulos.Articulos articulo : articles) {
                if (articulo.isStatus()
                    && articulo.getLastPrecio() != null
                    && articulo.getLastPrecio().getPrecioFinal() != null
                    && articulo.getLastPrecio().getPrecioCostoSinIVA() != null) {

                    BigDecimal precioVenta = articulo.getLastPrecio().getPrecioFinal();
                    BigDecimal precioCosto = articulo.getLastPrecio().getPrecioCostoSinIVA();

                    if (precioVenta.compareTo(BigDecimal.ZERO) > 0) {
                        BigDecimal margin = precioVenta.subtract(precioCosto)
                            .divide(precioVenta, 4, java.math.RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100))
                            .setScale(2, java.math.RoundingMode.HALF_UP);

                        Map<String, Object> entry = new HashMap<>();
                        entry.put("codigo", articulo.getCodigo());
                        entry.put("nombre", articulo.getNombre());
                        entry.put("precioVenta", precioVenta);
                        entry.put("precioCosto", precioCosto);
                        entry.put("margenPorcentaje", margin);
                        margins.add(entry);
                    }
                }
            }

            int total = margins.size();
            if (page != null && pageSize != null) {
                int from = (page - 1) * pageSize;
                int to = Math.min(from + pageSize, total);
                margins = (from < total) ? margins.subList(from, to) : Collections.emptyList();
            }

            result.put("data", margins);
            result.put("total", (long) total);
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error getting profit margins: " + e.getMessage(),
                null, 0, "PublicInvoiceService.getProfitMargins()", null, e.getMessage());
            result.put("data", Collections.emptyList());
            result.put("total", 0L);
        }
        return result;
    }

    // ────────────────────────────────────────────────────────────────────
    // 12. getSalesByCategory — sales by category
    // ────────────────────────────────────────────────────────────────────

    /**
     * Returns sales aggregated by category (Departamento).
     * LineaDetalle has no direct Articulos reference, so builds a CAByS→Departamento
     * lookup map from Articulos, then aggregates invoice line totals by department.
     *
     * @return list of maps, each with "departamento", "totalVentas", "cantidadArticulos"
     */
    @Nonnull
    public List<Map<String, Object>> getSalesByCategory() {
        try {
            // Build CAByS code → department name lookup from Articulos
            List<Models.Articulos.Articulos> allArticles = articulosService.listAll();
            if (allArticles == null) allArticles = Collections.emptyList();
            Map<String, String> cabysToDepartamento = new HashMap<>();
            for (Models.Articulos.Articulos art : allArticles) {
                if (art.getCodigoCabys() != null && art.getCodigoCabys().getCodigo() != null
                    && art.getDepartamento() != null && art.getDepartamento().getNombre() != null) {
                    cabysToDepartamento.put(art.getCodigoCabys().getCodigo(), art.getDepartamento().getNombre());
                }
            }

            // Fetch all line details from emitted invoices
            TypedQuery<Object[]> query = em.createQuery(
                "SELECT l.codigoCabys, l.montoTotalLinea, l.cantidad " +
                "FROM ComprobantesEmitidos ce " +
                "JOIN ce.detalles d " +
                "JOIN d.lineasDetalle l " +
                "WHERE ce.status = true",
                Object[].class
            );
            List<Object[]> rows = query.getResultList();

            // Aggregate by department
            Map<String, BigDecimal> ventasByDepto = new HashMap<>();
            Map<String, Long> countByDepto = new HashMap<>();
            for (Object[] row : rows) {
                String cabys = (String) row[0];
                BigDecimal monto = (BigDecimal) row[1];
                BigDecimal cantidad = (BigDecimal) row[2];
                String depto = cabysToDepartamento.getOrDefault(cabys, "Sin categoría");

                ventasByDepto.merge(depto, monto != null ? monto : BigDecimal.ZERO, BigDecimal::add);
                countByDepto.merge(depto, 1L, Long::sum);
            }

            // Sort by total sales descending
            List<Map<String, Object>> categories = new ArrayList<>();
            ventasByDepto.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .forEach(entry -> {
                    Map<String, Object> record = new HashMap<>();
                    record.put("departamento", entry.getKey());
                    record.put("totalVentas", entry.getValue());
                    record.put("cantidadArticulos", countByDepto.getOrDefault(entry.getKey(), 0L));
                    categories.add(record);
                });
            return categories;
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error getting sales by category: " + e.getMessage(),
                null, 0, "PublicInvoiceService.getSalesByCategory()", null, e.getMessage());
            return Collections.emptyList();
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // 13. getIVASummary — IVA summary
    // ────────────────────────────────────────────────────────────────────

    /**
     * Returns IVA (tax) summary across emitted invoices.
     * Aggregates total tax amounts grouped by IVA rate.
     *
     * @param page page number (1-based), null for all
     * @param pageSize items per page, null for default
     * @return map with "data" (List of IVA records) and "total" (Long count)
     */
    @Nonnull
    public Map<String, Object> getIVASummary(@Nullable Integer page, @Nullable Integer pageSize) {
        Map<String, Object> result = new HashMap<>();
        try {
            TypedQuery<Object[]> query = em.createQuery(
                "SELECT imp.codigoTarifaIVA, SUM(imp.monto), COUNT(DISTINCT ce.id) " +
                "FROM ComprobantesEmitidos ce " +
                "JOIN ce.detalles d " +
                "JOIN d.lineasDetalle l " +
                "JOIN l.impuestos imp " +
                "WHERE ce.status = true " +
                "GROUP BY imp.codigoTarifaIVA " +
                "ORDER BY imp.codigoTarifaIVA",
                Object[].class
            );
            List<Object[]> rows = query.getResultList();

            List<Map<String, Object>> summaries = new ArrayList<>();
            for (Object[] row : rows) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("tarifaIVA", row[0]);
                entry.put("totalImpuesto", row[1]);
                entry.put("cantidadComprobantes", row[2]);
                summaries.add(entry);
            }

            int total = summaries.size();
            if (page != null && pageSize != null) {
                int from = (page - 1) * pageSize;
                int to = Math.min(from + pageSize, total);
                summaries = (from < total) ? summaries.subList(from, to) : Collections.emptyList();
            }

            result.put("data", summaries);
            result.put("total", (long) total);
        } catch (PersistenceException e) {
            alertasService.registrarAlerta("Error", "Error getting IVA summary: " + e.getMessage(),
                null, 0, "PublicInvoiceService.getIVASummary()", null, e.getMessage());
            result.put("data", Collections.emptyList());
            result.put("total", 0L);
        }
        return result;
    }
}
