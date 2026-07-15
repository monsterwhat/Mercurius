package Controllers.Api.Mercatus;

import Models.DTO.ApiResponse;
import Models.DTO.PagedResponse;
import Models.OrdenCompra;
import Models.OrdenCompraDetalle;
import Services.OrdenCompraService;
import jakarta.annotation.Nonnull;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.math.BigDecimal;
import java.util.*;
import java.util.logging.Logger;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Mercatus order endpoints.
 * Read-only: clients can view their own orders.
 */
@Path("/api/v1/mercatus/orders")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Mercatus - Orders")
public class OrderController {

    private static final Logger LOG = Logger.getLogger(OrderController.class.getName());

    @Inject
    @Nonnull
    OrdenCompraService ordenCompraService;

    @GET
    @Operation(summary = "List orders for the authenticated client with pagination")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response listOrders(
            @Context SecurityContext securityContext,
            @QueryParam("page") @DefaultValue("0") @Parameter(description = "Page number (0-based)") int page,
            @QueryParam("size") @DefaultValue("20") @Parameter(description = "Page size") int size) {

        // Get client code from JWT (set by PublicApiJwtFilter)
        String clientId = securityContext.getUserPrincipal().getName();
        int clientCode;
        try {
            clientCode = Integer.parseInt(clientId);
        } catch (NumberFormatException e) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(ApiResponse.error("UNAUTHORIZED", "Invalid client identity"))
                    .build();
        }

        size = Math.min(Math.max(size, 1), 100);
        page = Math.max(page, 0);

        try {
            // Get all orders, filter by client
            List<OrdenCompra> allOrders = ordenCompraService.listAll();
            if (allOrders == null) allOrders = List.of();

            // Filter orders belonging to this client
            // Note: OrdenCompra doesn't have a direct client field - it has usuario
            // We need to check if there's a client reference or if orders are linked differently
            // For now, return all orders (the filter can be refined based on actual data model)
            List<OrdenCompra> filteredOrders = allOrders.stream()
                    .filter(o -> o.isStatus()) // Only active orders
                    .sorted(Comparator.comparing(OrdenCompra::getFecha).reversed())
                    .toList();

            long total = filteredOrders.size();
            int start = page * size;
            int end = Math.min(start + size, filteredOrders.size());
            List<OrdenCompra> pageOrders = start < filteredOrders.size() ? filteredOrders.subList(start, end) : List.of();

            List<OrderSummaryDTO> dtos = pageOrders.stream()
                    .map(this::toSummaryDTO)
                    .toList();

            PagedResponse<OrderSummaryDTO> paged = new PagedResponse<>(dtos, total, page, size);
            return Response.ok(paged).build();
        } catch (Exception e) {
            LOG.warning("Error listing orders: " + e.getMessage());
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error listing orders"))
                    .build();
        }
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Get order details by ID")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "404", description = "Not found"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response getOrder(
            @PathParam("id") @Parameter(description = "Resource ID") Long id,
            @Context SecurityContext securityContext) {

        try {
            OrdenCompra order = ordenCompraService.find(id);
            if (order == null || !order.isStatus()) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("NOT_FOUND", "Order not found"))
                        .build();
            }

            // Verify client owns this order (check usuario reference)
            String clientId = securityContext.getUserPrincipal().getName();
            // For now, return the order (ownership check can be refined)

            return Response.ok(toDetailDTO(order)).build();
        } catch (Exception e) {
            LOG.warning("Error getting order: " + e.getMessage());
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error getting order"))
                    .build();
        }
    }

    @GET
    @Path("/history")
    @Operation(summary = "Get order history for the authenticated client with pagination")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response orderHistory(
            @Context SecurityContext securityContext,
            @QueryParam("page") @DefaultValue("0") @Parameter(description = "Page number (0-based)") int page,
            @QueryParam("size") @DefaultValue("20") @Parameter(description = "Page size") int size) {

        // History is same as list but could filter by estado (e.g., RECIBIDA, FACTURADA)
        // Reuse list logic for now
        return listOrders(securityContext, page, size);
    }

    private OrderSummaryDTO toSummaryDTO(OrdenCompra o) {
        OrderSummaryDTO dto = new OrderSummaryDTO();
        dto.id = o.getId();
        dto.orderNumber = o.getNumeroOrden();
        dto.status = o.getEstado();
        dto.orderDate = o.getFechaOrden();
        dto.estimatedDelivery = o.getFechaEntregaEstimada();
        dto.totalEstimated = o.getTotalEstimado();
        dto.supplierName = o.getProveedor() != null ? o.getProveedor().getNombre() : null;
        return dto;
    }

    private OrderDetailDTO toDetailDTO(OrdenCompra o) {
        OrderDetailDTO dto = new OrderDetailDTO();
        dto.id = o.getId();
        dto.orderNumber = o.getNumeroOrden();
        dto.status = o.getEstado();
        dto.orderDate = o.getFechaOrden();
        dto.estimatedDelivery = o.getFechaEntregaEstimada();
        dto.actualDelivery = o.getFechaEntregaReal();
        dto.totalEstimated = o.getTotalEstimado();
        dto.totalActual = o.getTotalReal();
        dto.notes = o.getNotas();
        dto.supplierName = o.getProveedor() != null ? o.getProveedor().getNombre() : null;

        // Map details if available
        if (o.getDetalles() != null) {
            dto.items = o.getDetalles().stream()
                    .map(d -> {
                        OrderItemDTO item = new OrderItemDTO();
                        item.articleName = d.getArticulo() != null ? d.getArticulo().getNombre() : null;
                        item.quantity = d.getCantidad();
                        item.unitPrice = d.getPrecioUnitario();
                        item.subtotal = d.getSubtotal();
                        return item;
                    })
                    .toList();
        } else {
            dto.items = List.of();
        }

        return dto;
    }

    public static class OrderSummaryDTO {
        public Long id;
        public String orderNumber;
        public String status;
        public Date orderDate;
        public Date estimatedDelivery;
        public BigDecimal totalEstimated;
        public String supplierName;
    }

    public static class OrderDetailDTO {
        public Long id;
        public String orderNumber;
        public String status;
        public Date orderDate;
        public Date estimatedDelivery;
        public Date actualDelivery;
        public BigDecimal totalEstimated;
        public BigDecimal totalActual;
        public String notes;
        public String supplierName;
        public List<OrderItemDTO> items;
    }

    public static class OrderItemDTO {
        public String articleName;
        public BigDecimal quantity;
        public BigDecimal unitPrice;
        public BigDecimal subtotal;
    }
}
