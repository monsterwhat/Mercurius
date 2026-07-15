package Controllers.Api.Accounting;

import Models.DTO.ApiResponse;
import Models.DTO.PagedResponse;
import Services.PublicInvoiceService;
import jakarta.annotation.Nonnull;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Accounting reports endpoints.
 * Read-only: provides profit margins, sales by category, and IVA summary.
 */
@Path("/api/v1/accounting/reports")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Accounting - Reports")
public class ReportsController {

    private static final Logger LOG = Logger.getLogger(ReportsController.class.getName());

    @Inject
    @Nonnull
    PublicInvoiceService publicInvoiceService;

    /**
     * Profit margin report — paginated list of articles with margin calculations.
     */
    @GET
    @Path("/profit-margins")
    @Operation(summary = "Get profit margin report for articles with pagination")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response getProfitMargins(
            @QueryParam("page") @DefaultValue("0") @Parameter(description = "Page number (0-based)") int page,
            @QueryParam("size") @DefaultValue("20") @Parameter(description = "Page size") int size) {

        size = Math.min(Math.max(size, 1), 100);
        page = Math.max(page, 0);

        try {
            Map<String, Object> result = publicInvoiceService.getProfitMargins(page + 1, size);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) result.getOrDefault("data", List.of());
            Long total = (Long) result.getOrDefault("total", 0L);

            List<ProfitMarginDTO> dtos = new ArrayList<>();
            for (Map<String, Object> entry : data) {
                ProfitMarginDTO dto = new ProfitMarginDTO();
                dto.codigoBarra = (String) entry.getOrDefault("codigo", "");
                dto.nombre = (String) entry.getOrDefault("nombre", "");
                dto.precioCosto = toBigDecimal(entry.get("precioCosto"));
                dto.precioVenta = toBigDecimal(entry.get("precioVenta"));

                if (dto.precioVenta != null && dto.precioCosto != null) {
                    dto.margen = dto.precioVenta.subtract(dto.precioCosto);
                } else {
                    dto.margen = BigDecimal.ZERO;
                }
                dto.margenPorcentaje = toBigDecimal(entry.get("margenPorcentaje"));
                dtos.add(dto);
            }

            PagedResponse<ProfitMarginDTO> paged = new PagedResponse<>(dtos, total, page, size);
            return Response.ok(paged).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error getting profit margins", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error getting profit margins"))
                    .build();
        }
    }

    /**
     * Sales by category — aggregated sales totals grouped by department/category.
     * No pagination: returns all categories.
     */
    @GET
    @Path("/sales-by-category")
    @Operation(summary = "Get aggregated sales totals by department/category")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response getSalesByCategory() {
        try {
            List<Map<String, Object>> raw = publicInvoiceService.getSalesByCategory();

            List<SalesByCategoryDTO> dtos = new ArrayList<>();
            for (Map<String, Object> entry : raw) {
                SalesByCategoryDTO dto = new SalesByCategoryDTO();
                dto.categoriaNombre = (String) entry.getOrDefault("departamento", "Sin categoría");
                dto.totalVendido = toBigDecimal(entry.get("totalVentas"));

                Object cantidadObj = entry.get("cantidadArticulos");
                dto.cantidadArticulos = cantidadObj instanceof Number ? ((Number) cantidadObj).longValue() : 0L;
                dtos.add(dto);
            }

            return Response.ok(dtos).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error getting sales by category", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error getting sales by category"))
                    .build();
        }
    }

    /**
     * IVA summary — tax breakdown by IVA tariff rate.
     */
    @GET
    @Path("/iva-summary")
    @Operation(summary = "Get IVA tax summary with pagination")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response getIVASummary(
            @QueryParam("page") @DefaultValue("0") @Parameter(description = "Page number (0-based)") int page,
            @QueryParam("size") @DefaultValue("20") @Parameter(description = "Page size") int size) {

        size = Math.min(Math.max(size, 1), 100);
        page = Math.max(page, 0);

        try {
            Map<String, Object> result = publicInvoiceService.getIVASummary(page + 1, size);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) result.getOrDefault("data", List.of());
            Long total = (Long) result.getOrDefault("total", 0L);

            List<IVASummaryDTO> dtos = new ArrayList<>();
            for (Map<String, Object> entry : data) {
                IVASummaryDTO dto = new IVASummaryDTO();
                dto.tarifa = toBigDecimal(entry.get("tarifaIVA"));
                dto.montoIVA = toBigDecimal(entry.get("totalImpuesto"));

                // baseImponible = montoIVA / (tarifa / 100) when tarifa > 0
                if (dto.montoIVA != null && dto.tarifa != null
                        && dto.tarifa.compareTo(BigDecimal.ZERO) > 0) {
                    dto.baseImponible = dto.montoIVA
                            .multiply(BigDecimal.valueOf(100))
                            .divide(dto.tarifa, 2, RoundingMode.HALF_UP);
                } else {
                    dto.baseImponible = BigDecimal.ZERO;
                }

                Object ventasObj = entry.get("cantidadComprobantes");
                dto.totalVentas = ventasObj instanceof Number ? ((Number) ventasObj).longValue() : 0L;
                dtos.add(dto);
            }

            PagedResponse<IVASummaryDTO> paged = new PagedResponse<>(dtos, total, page, size);
            return Response.ok(paged).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error getting IVA summary", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error getting IVA summary"))
                    .build();
        }
    }

    /**
     * Safe conversion to BigDecimal from an Object that may be null or non-numeric.
     */
    private static BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return BigDecimal.ZERO;
    }

    /**
     * DTO for profit margin reports.
     */
    public static class ProfitMarginDTO {
        public String codigoBarra;
        public String nombre;
        public BigDecimal precioCosto;
        public BigDecimal precioVenta;
        public BigDecimal margen;
        public BigDecimal margenPorcentaje;
    }

    /**
     * DTO for sales by category reports.
     */
    public static class SalesByCategoryDTO {
        public String categoriaNombre;
        public BigDecimal totalVendido;
        public Long cantidadArticulos;
    }

    /**
     * DTO for IVA summary reports.
     */
    public static class IVASummaryDTO {
        public BigDecimal tarifa;
        public BigDecimal baseImponible;
        public BigDecimal montoIVA;
        public Long totalVentas;
    }
}
