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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import Services.PrinterService;

/**
 * HTML page of the Impresoras module for the NEW Qute/HTMX app surface:
 * {@code GET /app/impresoras} — lists the print services (printers) the JVM
 * can see via {@link PrinterService}, marking the system default. Read-only
 * page renderer; there is no JSON twin nor legacy Facelets page for this
 * module.
 */
@Path("/app/impresoras")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"admin"})
public class ImpresorasPagesResource {
    private static final Logger LOG = Logger.getLogger(ImpresorasPagesResource.class.getName());

    @Inject
    @Nonnull
    PrinterService printerService;

    @Inject
    @Nonnull
    @Location("pages/impresoras/index")
    Template page;

    @GET
    public Response index() {
        try {
            List<String> impresoras = printerService.listarImpresoras();
            Map<String, Object> model = new LinkedHashMap<>();
            model.put("impresoras", impresoras);
            model.put("defaultImpresora", printerService.defaultImpresora());
            model.put("totalImpresoras", impresoras.size());
            TemplateInstance instance = page.instance();
            model.forEach(instance::data);
            return Response.ok(instance.render())
                    .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error renderizando la página de impresoras", e);
            return Response.serverError()
                    .entity(Models.DTO.ApiResponse.error("INTERNAL_ERROR",
                            "No se pudieron cargar las impresoras"))
                    .build();
        }
    }
}
