package Controllers.Api.App;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.Nonnull;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.logging.Level;
import java.util.logging.Logger;

@Path("/app/usuarios")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"admin", "usuario"})
public class UsuariosPagesResource {
    private static final Logger LOG = Logger.getLogger(UsuariosPagesResource.class.getName());
    @Inject @Nonnull @Location("pages/usuarios/index") Template page;
    @GET public Response index() {
        try {
            // usuarios/index may be form-based; fall back to simple render
            TemplateInstance i = page.instance();
            return Response.ok(i.render()).type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error renderizando usuarios", e);
            return Response.ok("<html><body><h1>Usuarios</h1><p>Vista en construccion</p></body></html>").type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).build();
        }
    }
}
