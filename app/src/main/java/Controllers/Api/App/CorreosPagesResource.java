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
 * HTML pages of the Correos module for the NEW Qute/HTMX app surface:
 * {@code GET /app/correos/reportes} (Reportes Programados) and
 * {@code GET /app/correos/plantillas} (Plantillas de Correo). This class only
 * renders HTML; all reads/actions live in the JSON twins
 * {@link ReporteProgramadoResource} ({@code /api/app/reportes-programados}) and
 * {@link EmailTemplateResource} ({@code /api/app/email-templates}), whose
 * full-page models are reused here so page and fragments can never disagree
 * (same split as ArticulosPagesResource / ArticuloResource).
 */
@Path("/app/correos")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"admin", "tributacion"})
public class CorreosPagesResource {
    private static final Logger LOG = Logger.getLogger(CorreosPagesResource.class);

    @Inject
    @Nonnull
    ReporteProgramadoResource reportes;

    @Inject
    @Nonnull
    EmailTemplateResource plantillas;

    @Inject
    @Nonnull
    @Location("pages/correos/reportes")
    Template reportesPage;

    @Inject
    @Nonnull
    @Location("pages/correos/templates")
    Template plantillasPage;

    @GET
    @Path("/reportes")
    public Response reportes() {
        return render(reportesPage, reportes.fullPageModel(),
                "No se pudieron cargar los reportes programados");
    }

    @GET
    @Path("/plantillas")
    public Response plantillas() {
        return render(plantillasPage, plantillas.fullPageModel(),
                "No se pudieron cargar las plantillas de correo");
    }

    private Response render(@Nonnull Template page, @Nonnull Map<String, Object> model,
                            @Nonnull String errorMessage) {
        try {
            TemplateInstance instance = page.instance();
            model.forEach(instance::data);
            return Response.ok(instance.render())
                    .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).build();
        } catch (RuntimeException e) {
            LOG.warn("Error renderizando la página de correos", e);
            return Response.serverError()
                    .entity(Models.DTO.ApiResponse.error("INTERNAL_ERROR", errorMessage))
                    .build();
        }
    }
}
