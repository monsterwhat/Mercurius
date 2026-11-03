package Controllers.Api.App;

import io.quarkus.security.Authenticated;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;

/**
 * Rutas desconocidas bajo {@code /app/*}.
 *
 * <p>El {@link Controllers.NotFoundExceptionMapper} documenta que un enlace
 * viejo de un usuario autenticado debe terminar en el dashboard, pero el router
 * de Quarkus resolvia esos paths ANTES de RESTEasy y devolvia la pagina cruda
 * "Not Found" (sin layout). Este catch-all completa el contrato: las rutas
 * especificas siguen ganando por especificidad de JAX-RS y solo cae aqui lo que
 * no existe.</p>
 *
 * <p>No toca {@code /api/*}: ahi el 404 JSON sigue siendo el contrato.</p>
 */
@Path("/app")
@Produces(MediaType.TEXT_HTML)
@Authenticated
public class AppFallbackResource {

    private static final String DASHBOARD = "/app/dashboard";

    @GET
    @Path("/{resto:.*}")
    public Response rutaDesconocida() {
        // Deployment-relative: el contenedor antepone el root path.
        return Response.seeOther(URI.create(DASHBOARD)).build();
    }
}
