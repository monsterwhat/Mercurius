package Controllers.Api.Mercatus;

import Models.Articulos.ArticuloStock;
import Models.DTO.ApiResponse;
import Services.InventarioService;
import jakarta.annotation.Nonnull;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import org.jboss.logging.Logger;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Mercatus inventory status endpoints.
 * Read-only: provides stock information for the marketplace.
 */
@Path("/api/v1/mercatus/inventory")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Mercatus - Inventory")
public class InventoryController {

    private static final Logger LOG = Logger.getLogger(InventoryController.class);

    @Inject
    @Nonnull
    InventarioService inventarioService;

    @GET
    @Path("/{articleId}")
    @Operation(summary = "Get stock status for an article")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "404", description = "Not found"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response getStockStatus(@PathParam("articleId") @Parameter(description = "Article ID") Long articleId) {
        try {
            // Find the article stock by article code (used as articleId)
            ArticuloStock stock = inventarioService.findStockByArticleCode(articleId.intValue());
            
            if (stock == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("NOT_FOUND", "Article not found or no stock record"))
                        .build();
            }

            StockStatusDTO dto = new StockStatusDTO();
            dto.articleId = articleId;
            dto.barcode = stock.getCodigoBarra();
            dto.quantity = stock.getStock();
            dto.status = determineStockStatus(stock.getStock());

            return Response.ok(dto).build();
        } catch (Exception e) {
            LOG.warn("Error getting stock status: " + e.getMessage());
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error getting stock status"))
                    .build();
        }
    }

    private String determineStockStatus(BigDecimal quantity) {
        if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
            return "OUT_OF_STOCK";
        } else if (quantity.compareTo(BigDecimal.valueOf(5)) <= 0) {
            return "LOW_STOCK";
        } else {
            return "IN_STOCK";
        }
    }

    public static class StockStatusDTO {
        public Long articleId;
        public String barcode;
        public BigDecimal quantity;
        public String status; // IN_STOCK, LOW_STOCK, OUT_OF_STOCK
    }
}
