package Controllers.Api.Accounting;

import Models.DTO.ApiResponse;
import Models.Resumen.MedioPagoR;
import Services.PublicInvoiceService;
import jakarta.annotation.Nonnull;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.util.*;

import org.jboss.logging.Logger;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Accounting payment endpoints.
 * Read-only: provides payment information for invoices.
 */
@Path("/api/v1/accounting/invoices")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Accounting - Payments")
public class PaymentsController {

    private static final Logger LOG = Logger.getLogger(PaymentsController.class);

    @Inject
    @Nonnull
    PublicInvoiceService publicInvoiceService;

    @GET
    @Path("/{id}/payments")
    @Operation(summary = "Get payment methods for an invoice")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "404", description = "Not found"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    @Nonnull
    public Response getPayments(@PathParam("id") @Parameter(description = "Resource ID") Long id) {
        try {
            List<?> payments = publicInvoiceService.getPayments(id);

            List<PaymentDTO> dtos = payments.stream()
                    .map(obj -> {
                        MedioPagoR pago = (MedioPagoR) obj;
                        PaymentDTO dto = new PaymentDTO();
                        dto.id = pago.getId();
                        dto.formaPago = pago.getTipoMedioPago();
                        dto.monto = pago.getTotalMedioPago();
                        dto.fecha = null;
                        return dto;
                    })
                    .toList();

            return Response.ok(dtos).build();
        } catch (Exception e) {
            LOG.warn("Error getting payments for invoice " + id, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error getting payments for invoice"))
                    .build();
        }
    }

    /**
     * Public DTO for payment information (no entity relationships).
     */
    public static class PaymentDTO {
        public Long id;
        public String formaPago;
        public BigDecimal monto;
        public Date fecha;
    }
}
