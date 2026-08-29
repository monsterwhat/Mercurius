package Controllers.Api.Marketplace;

import Models.DTO.CreateOrderRequest;
import Models.DTO.OrderDTO;
import Services.MarketplaceOrderService;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.util.List;

import org.jboss.logging.Logger;

@Path("/api/marketplace/orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrderController {

    private static final Logger LOG = Logger.getLogger(OrderController.class);

    @Inject
    @Nonnull
    MarketplaceOrderService orderService;

    @Context
    SecurityContext securityContext;

    @GET
    @Nonnull
    public Response listOrders() {
        try {
            int clientCode = getClientCode();
            List<OrderDTO> orders = orderService.listClientOrders(clientCode);
            return Response.ok(orders).build();
        } catch (RuntimeException e) {
            LOG.error("Error listing orders", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Error al cargar órdenes\"}")
                    .build();
        }
    }

    @POST
    @Nonnull
    public Response createOrder(@Nullable CreateOrderRequest request) {
        try {
            int clientCode = getClientCode();
            if (request == null) request = new CreateOrderRequest();
            OrderDTO order = orderService.createOrderFromCart(clientCode, request);
            return Response.status(Response.Status.CREATED).entity(order).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"" + e.getMessage() + "\"}")
                    .build();
        } catch (RuntimeException e) {
            LOG.error("Error creating order", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Error al crear orden\"}")
                    .build();
        }
    }

    @GET
    @Path("/{orderId}")
    @Nonnull
    public Response getOrder(@PathParam("orderId") Long orderId) {
        try {
            int clientCode = getClientCode();
            OrderDTO order = orderService.getOrder(clientCode, orderId);
            if (order == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("{\"error\":\"Orden no encontrada\"}")
                        .build();
            }
            return Response.ok(order).build();
        } catch (RuntimeException e) {
            LOG.error("Error getting order", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Error al cargar orden\"}")
                    .build();
        }
    }

    @PUT
    @Path("/{orderId}/cancel")
    @Nonnull
    public Response cancelOrder(@PathParam("orderId") Long orderId) {
        try {
            int clientCode = getClientCode();
            boolean cancelled = orderService.cancelOrder(clientCode, orderId);
            if (!cancelled) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity("{\"error\":\"No se puede cancelar. Solo órdenes pendientes pueden cancelarse.\"}")
                        .build();
            }
            return Response.ok("{\"message\":\"Orden cancelada exitosamente\"}").build();
        } catch (RuntimeException e) {
            LOG.error("Error cancelling order", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Error al cancelar orden\"}")
                    .build();
        }
    }

    private int getClientCode() {
        if (securityContext == null || securityContext.getUserPrincipal() == null) {
            throw new RuntimeException("No autenticado");
        }
        return Integer.parseInt(securityContext.getUserPrincipal().getName());
    }
}
