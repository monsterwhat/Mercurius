package Controllers.Api.Accounting;

import Models.DTO.ApiResponse;
import Services.PublicInvoiceService;
import jakarta.annotation.Nonnull;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.util.*;

import org.jboss.logging.Logger;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Accounting inventory valuation endpoint.
 * Read-only: provides current stock levels with valuation data.
 */
@Path("/api/v1/accounting/inventory/valuation")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Accounting - Inventory Valuation")
public class InventoryValuationController {

    private static final Logger LOG = Logger.getLogger(InventoryValuationController.class);

    @Inject
    @Nonnull
    PublicInvoiceService publicInvoiceService;

    @GET
    @SuppressWarnings("unchecked")
    @Operation(summary = "Get current inventory valuation with stock levels")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response getInventoryValuation() {
        try {
            Map<String, Object> result = publicInvoiceService.getInventoryValuation();

            List<Map<String, Object>> items =
                    (List<Map<String, Object>>) result.getOrDefault("items", List.of());

            List<InventoryValuationDTO> dtos = items.stream()
                    .map(this::toDTO)
                    .toList();

            return Response.ok(dtos).build();
        } catch (Exception e) {
            LOG.warn("Error getting inventory valuation", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error getting inventory valuation"))
                    .build();
        }
    }

    @SuppressWarnings("unchecked")
    private InventoryValuationDTO toDTO(Map<String, Object> item) {
        InventoryValuationDTO dto = new InventoryValuationDTO();
        dto.codigoBarra = (String) item.get("codigoBarra");
        dto.stock = (BigDecimal) item.get("stock");
        dto.nombre = (String) item.get("nombre");
        dto.precioFinal = (BigDecimal) item.get("precioUnitario");
        dto.precioCostoSinIVA = (BigDecimal) item.get("precioCostoSinIVA");
        return dto;
    }

    /**
     * Public DTO for inventory valuation (no internal entity relationships).
     */
    public static class InventoryValuationDTO {
        public String codigoBarra;
        public BigDecimal stock;
        public String nombre;
        public BigDecimal precioFinal;
        public BigDecimal precioCostoSinIVA;
    }
}
