package Controllers.Api;

import Models.DTO.ApiResponse;
import Services.InvoicingService;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;

/**
 * API pública de ventas para integraciones (POS externo, e-commerce, caja
 * automática).
 *
 * <p>Autenticación: Bearer JWT emitido por el flujo OAuth2
 * client_credentials; {@code PublicApiJwtFilter} ya protege {@code /api/v1/**}.
 * Si el cliente declara scopes, este endpoint exige {@code invoicing:write};
 * los clientes legados sin scopes siguen funcionando (compatibilidad).</p>
 *
 * <p>Contrato mínimo: cajero, líneas por código de barras y pagos. El servidor
 * resuelve cliente, precio, CABYS, impuestos, caja e inventario.</p>
 */
@Path("/api/v1/invoicing")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Invoicing API")
public class InvoicingApiResource {

    private static final Logger LOG = Logger.getLogger(InvoicingApiResource.class);
    private static final String SCOPE = "invoicing:write";

    @Inject
    InvoicingService invoicingService;

    @Context
    ContainerRequestContext requestContext;

    @POST
    @Path("/sales")
    @Operation(summary = "Crea una venta (tiquete o factura) desde una integración")
    public Response crear(@Nullable InvoicingService.Solicitud solicitud) {
        String apiClientId = (String) requestContext.getProperty("apiClientId");
        if (apiClientId == null || apiClientId.isBlank()) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(ApiResponse.error("UNAUTHORIZED", "Token de API requerido."))
                    .build();
        }

        Object rawScopes = requestContext.getProperty("apiScopes");
        if (rawScopes instanceof Set<?> scopes && !scopes.isEmpty()
                && !scopes.contains(SCOPE)) {
            LOG.warn("Cliente de API sin scope " + SCOPE + ": " + apiClientId
                    + " | source=InvoicingApiResource.crear()");
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(ApiResponse.error("FORBIDDEN",
                            "El cliente de API no tiene el scope " + SCOPE + "."))
                    .build();
        }

        if (solicitud == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("VALIDATION_ERROR", "El cuerpo es requerido."))
                    .build();
        }

        InvoicingService.Resultado resultado = invoicingService.crear(apiClientId, solicitud);
        if (resultado.status() != 200) {
            return Response.status(resultado.status())
                    .entity(ApiResponse.error(
                            resultado.codigo() != null ? resultado.codigo() : "ERROR",
                            resultado.mensaje() != null ? resultado.mensaje() : "No se pudo crear la venta."))
                    .build();
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("comprobanteId", resultado.comprobanteId());
        data.put("consecutivo", resultado.consecutivo());
        data.put("clave", resultado.clave());
        data.put("estado", resultado.estado());
        data.put("total", resultado.total());
        data.put("vuelto", resultado.vuelto());
        data.put("advertencias", resultado.advertencias());
        data.put("pdfUrl", resultado.pdfUrl());
        data.put("tiqueteUrl", resultado.tiqueteUrl());
        return Response.ok(ApiResponse.ok(data)).build();
    }
}
