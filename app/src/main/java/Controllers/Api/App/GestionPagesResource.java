package Controllers.Api.App;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.logging.Logger;

/**
 * Landing page for the Gestión module (D13 phase 1): a single navigation
 * surface gathering the management screens that previously lived only in
 * the navbar dropdowns (Artículos, Categorías, Inventarios, Órdenes de
 * compra, Etiquetas, Usuarios, Clientes, Plantillas, Menú de correos,
 * Registros). Each card is role-gated individually so a user only sees the
 * screens they can actually open.
 *
 * <p>Role gate mirrors the union of the cards' destination gates:
 * {@code admin, registro, inventario, tributacion}. {@code usuario} and
 * {@code facturacion} never reach this landing.</p>
 */
@Path("/app/gestion")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"admin", "registro", "inventario", "tributacion"})
public class GestionPagesResource {

    private static final Logger LOG = Logger.getLogger(GestionPagesResource.class);

    @Inject
    @Location("pages/gestion/index")
    Template indexPage;

    @GET
    public Response index() {
        try {
            TemplateInstance instance = indexPage.instance();
            return htmlOk(instance);
        } catch (RuntimeException e) {
            LOG.warn("Error renderizando la página de gestión", e);
            return Response.serverError().build();
        }
    }

    private static Response htmlOk(TemplateInstance template) {
        return Controllers.Api.App.AppRecursoBase.htmlOk(template);
    }
}