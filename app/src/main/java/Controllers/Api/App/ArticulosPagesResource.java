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
 * HTML page of the Artículos module for the NEW Qute/HTMX app surface:
 * {@code GET /app/articulos} — the five-tab board (Activos / Inactivos /
 * Catálogo / Pendientes / Promociones). This class only renders HTML; all
 * reads/actions live in the JSON twin {@link ArticuloResource}
 * ({@code /api/app/articulos}), whose full-page model is reused here so page
 * and fragments can never disagree (same split as Recibos/Devoluciones).
 */
@Path("/app/articulos")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"admin", "inventario"})
public class ArticulosPagesResource {
    private static final Logger LOG = Logger.getLogger(ArticulosPagesResource.class);

    @Inject
    @Nonnull
    ArticuloResource articulos;

    @Inject
    @Nonnull
    @Location("pages/articulos/index")
    Template page;

    @GET
    public Response index() {
        try {
            Map<String, Object> model = articulos.fullPageModel();
            TemplateInstance instance = page.instance();
            model.forEach(instance::data);
            return Response.ok(instance.render())
                    .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).build();
        } catch (RuntimeException e) {
            LOG.warn("Error renderizando la página de artículos", e);
            return Response.serverError()
                    .entity(Models.DTO.ApiResponse.error("INTERNAL_ERROR",
                            "No se pudieron cargar los artículos"))
                    .build();
        }
    }
}
