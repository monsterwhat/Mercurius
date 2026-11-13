package Controllers.Api.App.Reportes;

import static Controllers.Api.App.AppRecursoBase.*;

import Models.ComprobantesEmitidos;
import Services.ComprobantesEmitidosService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * Drawer lateral de una factura emitida en la vista Consultas.
 *
 * <p>Solo lectura. Las acciones legales viven fuera de este recurso: la nota
 * de credito reutiliza {@code /app/devoluciones}, el envio de una fila en cola
 * y la anulacion de un pendiente local los resuelve
 * {@link ConsultaAnulacionResource}.</p>
 */
@Path("/app/tributacion/consulta")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"admin", "tributacion"})
public class ConsultaDetalleResource {

    private static final Logger LOG = Logger.getLogger(ConsultaDetalleResource.class);

    @Inject
    ComprobantesEmitidosService emitidosService;

    @Inject
    ConsultaDetalleModel detalleModel;

    @Inject
    @Location("pages/tributacion/consulta-detalle")
    Template detalleTemplate;

    @GET
    @Path("/{id}")
    @Transactional
    public Response detalle(@PathParam("id") long id) {
        try {
            ComprobantesEmitidos factura = emitidosService.find(id);
            if (factura == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                        .entity("<p>No se encontró la factura.</p>")
                        .build();
            }
            return htmlOk(detalleTemplate.data(detalleModel.modelo(factura)).render());
        } catch (RuntimeException e) {
            LOG.warn("Error renderizando el detalle de la consulta " + id, e);
            return Response.serverError()
                    .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                    .entity("<p>No se pudo cargar el detalle.</p>")
                    .build();
        }
    }
}
