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

@Path("/app/cabys")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"admin", "tributacion"})
public class CabysPagesResource {
    private static final Logger LOG = Logger.getLogger(CabysPagesResource.class.getName());
    @Inject @Nonnull @Location("pages/cabys/index") Template page;
    @GET public Response index() {
        try {
            TemplateInstance i = page.instance();
            return Response.ok(i.render()).type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error renderizando cabys", e);
            return Response.ok("<html><body><h1>CaByS</h1><p>Vista CaByS (placeholder)</p><a href=\"/Mercurius/app\">Inicio</a></body></html>").type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).build();
        }
    }
}
