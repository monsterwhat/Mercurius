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
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Landing page for the special emission documents that are not a regular sale:
 * Nota de Débito (03), Factura Electrónica de Exportación (05), Factura
 * Electrónica de Compra (08) and Recibo Electrónico de Pago (10).
 *
 * <p>Each card opens the POS with {@code tipoDocumento} preselected; the POS
 * checkout dialog carries the reference/condition fields those XSDs require.
 * Printing and emailing then work from the emitted-invoice detail for every
 * one of them.</p>
 */
@Path("/app/comprobantes")
@RolesAllowed({"admin", "facturacion"})
@Tag(name = "App - Comprobantes especiales")
public class ComprobantesEspecialesPageResource {

    @Inject
    @Location("pages/comprobantes/index")
    Template pagina;

    @GET
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Landing page for ND, FEE, FEC and REP issuance")
    public Response index() {
        return Response.ok(pagina.render())
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                .build();
    }
}
