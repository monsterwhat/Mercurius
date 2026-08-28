package Controllers.Api.App;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Navbar routes rendered through the shared Qute layout (T11): the reportes hub
 * and the log-activities placeholder. All render {@code layout.html} so they get
 * the role-gated navbar, web-bundler tags and CSRF headers like every /app page.
 */
@Path("/app")
@Produces(MediaType.TEXT_HTML)
public class MiscPagesResource {

    @Inject
    @Location("pages/reportes/index")
    Template reportesPage;

    @Inject
    @Location("pages/registros/log")
    Template logPage;

    @GET @Path("/reportes") @RolesAllowed({"admin","registro","inventario","tributacion"})
    public Response reportes() {
        return Response.ok(reportesPage.instance().render())
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).build();
    }

    @GET @Path("/registros/log") @RolesAllowed({"admin","registro"})
    public Response registrosLog() {
        return Response.ok(logPage.instance().render())
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).build();
    }
}
