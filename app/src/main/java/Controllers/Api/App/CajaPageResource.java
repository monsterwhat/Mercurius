package Controllers.Api.App;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Control de Caja en una ruta limpia ({@code /app/caja}).
 *
 * <p>La pantalla solo existía en {@code GET /api/app/caja/table} y no tenía
 * ningún enlace: quedaba inalcanzable salvo escribiendo la URL de la API.</p>
 */
@Path("/app/caja")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"admin", "facturacion"})
public class CajaPageResource {

    @Inject
    CierreCajaResource cierreCajaResource;

    @GET
    public Response index() {
        return cierreCajaResource.table(1, 10, null, "asc");
    }
}
