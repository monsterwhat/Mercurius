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
import org.jboss.logging.Logger;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Mercatus order endpoints over {@link OrdenCompra} (purchase orders).
 *
 * <p><b>RETIRED — both endpoints now answer 501.</b></p>
 *
 * <p>They previously read as if they were client-scoped ("clients can view their
 * own orders") and were not: {@code OrdenCompra} is a SUPPLIER purchase order
 * that links to {@code Usuarios} via {@code usuario_id}, with no column, and no
 * reachable join, to {@code Clientes}. The implementation acknowledged this in a
 * comment and shipped anyway — {@code listOrders} returned
 * {@code ordenCompraService.listAll()}, i.e. the entire order book with supplier
 * names, notes and prices, to any holder of any valid token, and
 * {@code getOrder} read the caller's principal into an unused local
 * ({@code // For now, return the order}). An id chosen freely then returned any
 * order in the system.</p>
 *
 * <p>Rather than invent a data model (adding a client FK to a purchase order is
 * a product decision, not a security fix), these endpoints are closed. Client
 * order visibility lives on the sibling surface, which IS properly scoped:
 * {@code /api/marketplace/orders} over {@code MarketplaceOrder}, where
 * {@code MarketplaceOrderService} filters by {@code clientCode} on list
 * ({@code listClientOrders}), get ({@code getOrder}) and cancel
 * ({@code cancelOrder}).</p>
 *
 * <p>To restore supplier purchase orders for API clients, give
 * {@code OrdenCompra} a client/owner reference, then filter on it here the same
 * way — and keep the "verify ownership" comment only once a check exists.</p>
 */
@Path("/api/v1/mercatus/orders")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Mercatus - Orders")
public class OrderController {

    private static final Logger LOG = Logger.getLogger(OrderController.class);

    @Inject
    @Nonnull
    OrdenCompraService ordenCompraService;

    /**
     * Single body for both retired endpoints, so the reason is never a bare
     * status code. It names the working alternative so an integrator is not left
     * guessing.
     */
    private static Response noImplementado() {
        return Response.status(Response.Status.NOT_IMPLEMENTED)
                .entity(ApiResponse.error("NOT_IMPLEMENTED",
                        "Este recurso no puede limitarse al cliente solicitante: las ordenes de compra "
                        + "no tienen una referencia a la cuenta de mercatus. Use /api/marketplace/orders "
                        + "para las ordenes del cliente, que si están limitadas a su titular."))
                .build();
    }

    @GET
    @Operation(summary = "Retired: purchase orders cannot be scoped to the calling client")
    @APIResponses({
        @APIResponse(responseCode = "501", description = "Not implemented (see message for the scoped alternative)"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response listOrders(
            @Context SecurityContext securityContext,
            @QueryParam("page") @DefaultValue("0") @Parameter(description = "Page number (0-based)") int page,
            @QueryParam("size") @DefaultValue("20") @Parameter(description = "Page size") int size) {

        LOG.warn("GET /api/v1/mercatus/orders retirado: devolveria ordenes de compra sin titular"
                + " | source=OrderController.listOrders()");
        return noImplementado();
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Retired: a purchase order by id cannot be authorised against the caller")
    @APIResponses({
        @APIResponse(responseCode = "501", description = "Not implemented (see message for the scoped alternative)"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response getOrder(
            @PathParam("id") @Parameter(description = "Resource ID") Long id,
            @Context SecurityContext securityContext) {

        LOG.warn("GET /api/v1/mercatus/orders/" + id + " retirado: no hay comprobacion de titularidad"
                + " | source=OrderController.getOrder()");
        return noImplementado();
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

    /**
     * Mappers below are retained for the restore path described in the class
     * javadoc: once {@code OrdenCompra} gains an owner reference, the filter
     * goes back in the two endpoints above and these become live again. They are
     * the only place that knows the response shape.
     */
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
