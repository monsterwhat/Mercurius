package Controllers.Api.App.Compras;

import Controllers.Api.App.OrdenCompraResource;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Real page route for purchase orders (D6/D13 phase 1): exposes the same
 * full page the dual-mode {@code /api/app/ordenes/table} endpoint renders
 * when called without {@code HX-Request}, under a clean {@code /app} URL.
 *
 * <p>No business logic is duplicated: rendering delegates to
 * {@link OrdenCompraResource}.</p>
 */
@Path("/app/compras/ordenes")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"admin", "inventario"})
public class OrdenesPagesResource {

    @Inject
    OrdenCompraResource ordenCompraResource;

    @GET
    public Response index() {
        return ordenCompraResource.renderOrdersFullPage();
    }
}