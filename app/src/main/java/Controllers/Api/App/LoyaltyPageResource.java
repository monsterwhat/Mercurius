package Controllers.Api.App;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Programa de Lealtad (gestión) en una ruta limpia ({@code /app/loyalty}).
 *
 * <p>La pantalla solo existía en {@code GET /api/app/loyalty/table}; el hub de
 * Reportes únicamente enlazaba el reporte de puntos. Esto expone la gestión de
 * ajustes e historial sin teclear una URL de API.</p>
 */
@Path("/app/loyalty")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"admin", "facturacion"})
public class LoyaltyPageResource {

    @Inject
    LoyaltyResource loyaltyResource;

    @GET
    public Response index() {
        return loyaltyResource.table(1, 10, null, "asc");
    }
}
