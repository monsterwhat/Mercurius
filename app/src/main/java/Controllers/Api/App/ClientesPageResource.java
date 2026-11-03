package Controllers.Api.App;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Página de GESTIÓN de clientes en una ruta limpia ({@code /app/clientes}).
 *
 * <p>La pantalla ya existía en {@code GET /api/app/clientes/table} (modo dual:
 * fragmento con HX-Request, página completa sin él), pero no tenía enlace en
 * ninguna parte: la tarjeta "Clientes" del hub y el sidebar llevaban al
 * REPORTE. Esto deja la gestión (crear/editar/archivar) alcanzable sin teclear
 * una URL de API.</p>
 */
@Path("/app/clientes")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"admin", "usuario"})
public class ClientesPageResource {

    @Inject
    ClientsResource clientsResource;

    @GET
    public Response index() {
        // Sin HX-Request, el endpoint dual devuelve la página completa.
        return clientsResource.table(1, 20, null, "asc", null, null);
    }
}
