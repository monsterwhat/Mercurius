package Controllers.Api.App;

import jakarta.annotation.Nonnull;
import io.quarkus.security.Authenticated;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * "Recibos" son las facturas que se RECIBEN (las que pasan los proveedores).
 *
 * <p>La ruta vieja ({@code /app/recibos}) mostraba las EMITIDAS, que es justo
 * lo que confunde: el tablero de las emitidas vive en {@code /app/facturas} y
 * esta ruta lleva al buzón de las recibidas.</p>
 */
@Path("/app/recibos")
@Produces(MediaType.TEXT_HTML)
@Authenticated
public class RecibosRedirectResource {

    /** El buzón de facturas recibidas (la tabla con sus cubos). */
    private static final String RECIBIDAS = "/api/app/facturas-recibidas/tabla";

    @Context
    @Nonnull
    HttpHeaders httpHeaders;

    @GET
    public Response recibidos() {
        String root = ConfigProvider.getConfig().getValue("quarkus.http.root-path", String.class);
        return Response.status(Response.Status.FOUND)
                .header("Location", (root == null ? "" : root) + RECIBIDAS)
                .build();
    }
}
