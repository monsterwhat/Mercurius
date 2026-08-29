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
import java.util.Map;

import org.jboss.logging.Logger;

/**
 * HTML page of the Usuarios module for the NEW Qute/HTMX app surface:
 * {@code GET /app/usuarios} — the route the T11 navbar reserved for the
 * legacy secured/pages/Usuarios/index.xhtml.
 *
 * <p>Read-only page renderer: builds the full-page model (data table + stat
 * counters) exactly like {@link UsersResource#fullPageModel()} and renders
 * {@code pages/usuarios/index.html}. All mutation, fragment and JSON
 * endpoints live in the API twin {@link UsersResource}
 * ({@code /api/app/users}).</p>
 *
 * <p><b>Role gate</b>: {@code admin} + {@code usuario}, mirroring the
 * module's managing roles (creation narrows to {@code admin} via the API
 * twin's own gate).</p>
 */
@Path("/app/usuarios")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"admin", "usuario"})
public class UsuariosPagesResource {
    private static final Logger LOG = Logger.getLogger(UsuariosPagesResource.class);

    @Inject
    @Nonnull
    UsersResource users;

    @Inject
    @Nonnull
    @Location("pages/usuarios/index")
    Template page;

    @GET
    public Response index() {
        try {
            Map<String, Object> model = users.fullPageModel();
            TemplateInstance instance = page.instance();
            model.forEach(instance::data);
            return Response.ok(instance.render())
                    .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).build();
        } catch (RuntimeException e) {
            LOG.warn("Error renderizando la página de usuarios", e);
            return Response.serverError()
                    .entity(Models.DTO.ApiResponse.error("INTERNAL_ERROR",
                            "No se pudieron cargar los usuarios"))
                    .build();
        }
    }
}
