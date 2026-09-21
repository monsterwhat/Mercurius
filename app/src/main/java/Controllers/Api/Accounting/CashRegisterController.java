package Controllers.Api.Accounting;

import Models.CierreCaja;
import Models.DTO.ApiResponse;
import Models.DTO.PagedResponse;
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
 * Accounting cash register endpoints.
 * Read-only: provides cash register (cierre de caja) information for accounting.
 */
@Path("/api/v1/accounting/cash-register")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Accounting - Cash Register")
public class CashRegisterController {

    private static final Logger LOG = Logger.getLogger(CashRegisterController.class);

    @Inject
    @Nonnull
    PublicInvoiceService publicInvoiceService;

    @GET
    @Operation(summary = "List cash register entries with pagination")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response listCashRegister(
            @QueryParam("page") @DefaultValue("0") @Parameter(description = "Page number (0-based)") int page,
            @QueryParam("size") @DefaultValue("20") @Parameter(description = "Page size") int size) {

        // Clamp size to max 100
        size = Math.min(Math.max(size, 1), 100);
        page = Math.max(page, 0);

        try {
            // PublicInvoiceService uses 1-based page; controller uses 0-based
            Map<String, Object> result = publicInvoiceService.getCashRegister(page + 1, size);

            @SuppressWarnings("unchecked")
            List<CierreCaja> data = (List<CierreCaja>) result.getOrDefault("data", List.of());
            Long total = (Long) result.getOrDefault("total", 0L);

            List<CashRegisterDTO> dtos = data.stream()
                    .map(this::toDTO)
                    .toList();

            PagedResponse<CashRegisterDTO> paged = new PagedResponse<>(dtos, total, page, size);
            return Response.ok(paged).build();
        } catch (Exception e) {
            LOG.warn("Error listing cash register entries", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error listing cash register entries"))
                    .build();
        }
    }

    private CashRegisterDTO toDTO(CierreCaja cc) {
        CashRegisterDTO dto = new CashRegisterDTO();
        dto.id = cc.getId();
        dto.fechaApertura = cc.getFechaApertura();
        dto.fechaCierre = cc.getFechaCierre();
        dto.montoInicial = cc.getMontoInicial();
        dto.montoEsperadoEfectivo = cc.getMontoEsperadoEfectivo();
        dto.montoEsperadoSinpe = cc.getMontoEsperadoSinpe();
        dto.montoEsperadoTarjeta = cc.getMontoEsperadoTarjeta();
        dto.montoContadoEfectivo = cc.getMontoContadoEfectivo();
        dto.montoContadoSinpe = cc.getMontoContadoSinpe();
        dto.montoContadoTarjeta = cc.getMontoContadoTarjeta();
        dto.diferencia = cc.getDiferencia();
        dto.estado = cc.getEstado();
        dto.notas = cc.getNotas();
        return dto;
    }

    /**
     * Public DTO for cash register entries (no internal entity relationships).
     */
    public static class CashRegisterDTO {
        public Long id;
        public Date fechaApertura;
        public Date fechaCierre;
        public BigDecimal montoInicial;
        public BigDecimal montoEsperadoEfectivo;
        public BigDecimal montoEsperadoSinpe;
        public BigDecimal montoEsperadoTarjeta;
        public BigDecimal montoContadoEfectivo;
        public BigDecimal montoContadoSinpe;
        public BigDecimal montoContadoTarjeta;
        public BigDecimal diferencia;
        public String estado;
        public String notas;
    }
}
