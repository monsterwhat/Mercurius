package Controllers.Api.Marketplace;

import Models.DTO.AddToCartRequest;
import Models.DTO.CartResponse;
import Models.DTO.UpdateCartItemRequest;
import Services.MarketplaceCartService;
import jakarta.annotation.Nonnull;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
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
import java.util.logging.Level;
import java.util.logging.Logger;

@Path("/api/marketplace/cart")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CartController {

    private static final Logger LOG = Logger.getLogger(CartController.class.getName());

    @Inject
    @Nonnull
    MarketplaceCartService cartService;

    @Context
    SecurityContext securityContext;

    @GET
    @Nonnull
    public Response getCart() {
        try {
            int clientCode = getClientCode();
            CartResponse cart = cartService.getCart(clientCode);
            return Response.ok(cart).build();
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Error getting cart", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Error al obtener carrito\"}")
                    .build();
        }
    }

    @POST
    @Nonnull
    public Response addItem(@Nonnull AddToCartRequest request) {
        try {
            int clientCode = getClientCode();
            cartService.addItem(clientCode, request);
            CartResponse cart = cartService.getCart(clientCode);
            return Response.ok(cart).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"" + e.getMessage() + "\"}")
                    .build();
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Error adding cart item", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Error al agregar al carrito\"}")
                    .build();
        }
    }

    @PUT
    @Path("/{itemId}")
    @Nonnull
    public Response updateItem(@PathParam("itemId") Long itemId, @Nonnull UpdateCartItemRequest request) {
        try {
            int clientCode = getClientCode();
            cartService.updateItemQuantity(clientCode, itemId, request);
            CartResponse cart = cartService.getCart(clientCode);
            return Response.ok(cart).build();
        } catch (IllegalArgumentException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"" + e.getMessage() + "\"}")
                    .build();
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Error updating cart item", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Error al actualizar carrito\"}")
                    .build();
        }
    }

    @DELETE
    @Path("/{itemId}")
    @Nonnull
    public Response removeItem(@PathParam("itemId") Long itemId) {
        try {
            int clientCode = getClientCode();
            cartService.removeItem(clientCode, itemId);
            CartResponse cart = cartService.getCart(clientCode);
            return Response.ok(cart).build();
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Error removing cart item", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Error al eliminar del carrito\"}")
                    .build();
        }
    }

    @DELETE
    @Nonnull
    public Response clearCart() {
        try {
            int clientCode = getClientCode();
            cartService.clearCart(clientCode);
            return Response.ok(new CartResponse(java.util.Collections.emptyList(), 0, java.math.BigDecimal.ZERO)).build();
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Error clearing cart", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Error al limpiar carrito\"}")
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
