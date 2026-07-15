package Controllers.Api.Accounting;

import Models.DTO.ApiResponse;
import Models.TipoCambio;
import Services.PublicInvoiceService;
import jakarta.annotation.Nonnull;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDateTime;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Accounting exchange rates endpoints.
 * Read-only: provides current exchange rate (TipoCambio) information.
 */
@Path("/api/v1/accounting/exchange-rates")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Accounting - Exchange Rates")
public class ExchangeRatesController {

    private static final Logger LOG = Logger.getLogger(ExchangeRatesController.class.getName());

    @Inject
    @Nonnull
    PublicInvoiceService publicInvoiceService;

    @GET
    @Operation(summary = "Get current exchange rates (buy and sell)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "404", description = "Not found"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response getExchangeRates() {
        try {
            TipoCambio tipoCambio = publicInvoiceService.getExchangeRates();
            if (tipoCambio == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("NOT_FOUND", "No exchange rate data available"))
                        .build();
            }
            return Response.ok(toDTO(tipoCambio)).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error getting exchange rates", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error getting exchange rates"))
                    .build();
        }
    }

    private ExchangeRateDTO toDTO(TipoCambio tc) {
        ExchangeRateDTO dto = new ExchangeRateDTO();
        dto.id = tc.getId();
        dto.fecha = tc.getFecha();
        dto.valorCompra = tc.getValorCompra();
        dto.valorVenta = tc.getValorVenta();
        return dto;
    }

    /**
     * Public DTO for exchange rates (no internal entity relationships).
     */
    public static class ExchangeRateDTO {
        public Long id;
        public LocalDateTime fecha;
        public double valorCompra;
        public double valorVenta;
    }
}
