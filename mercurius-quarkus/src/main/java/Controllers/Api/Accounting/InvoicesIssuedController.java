package Controllers.Api.Accounting;

import Models.ComprobantesEmitidos;
import Models.DTO.ApiResponse;
import Models.DTO.PagedResponse;
import Services.PublicInvoiceService;
import jakarta.annotation.Nonnull;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Accounting invoices issued endpoints.
 * Read-only: provides issued invoice information for accounting.
 */
@Path("/api/v1/accounting/invoices/issued")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Accounting - Invoices Issued")
public class InvoicesIssuedController {

    private static final Logger LOG = Logger.getLogger(InvoicesIssuedController.class.getName());

    @Inject
    @Nonnull
    PublicInvoiceService publicInvoiceService;

    @GET
    @Operation(summary = "List issued invoices with pagination")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response listIssuedInvoices(
            @QueryParam("page") @DefaultValue("0") @Parameter(description = "Page number (0-based)") int page,
            @QueryParam("size") @DefaultValue("20") @Parameter(description = "Page size") int size) {

        // Clamp size to max 100
        size = Math.min(Math.max(size, 1), 100);
        page = Math.max(page, 0);

        try {
            // PublicInvoiceService uses 1-based page; controller uses 0-based
            Map<String, Object> result = publicInvoiceService.getInvoicedInvoices(page + 1, size);

            @SuppressWarnings("unchecked")
            List<ComprobantesEmitidos> data = (List<ComprobantesEmitidos>) result.getOrDefault("data", List.of());
            Long total = (Long) result.getOrDefault("total", 0L);

            List<InvoiceDTO> dtos = data.stream()
                    .map(this::toDTO)
                    .toList();

            PagedResponse<InvoiceDTO> paged = new PagedResponse<>(dtos, total, page, size);
            return Response.ok(paged).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error listing issued invoices", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error listing issued invoices"))
                    .build();
        }
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Get an issued invoice by ID")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "404", description = "Not found"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response getIssuedInvoice(@PathParam("id") @Parameter(description = "Resource ID") Long id) {
        try {
            ComprobantesEmitidos invoice = publicInvoiceService.getInvoiceDetail(id);
            if (invoice == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("NOT_FOUND", "Invoice not found"))
                        .build();
            }
            return Response.ok(toDTO(invoice)).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error getting issued invoice", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error getting issued invoice"))
                    .build();
        }
    }

    @SuppressWarnings("unchecked")
    private InvoiceDTO toDTO(ComprobantesEmitidos ce) {
        InvoiceDTO dto = new InvoiceDTO();
        dto.id = ce.getId();
        dto.status = ce.getStatus();

        if (ce.getEncabezado() != null) {
            dto.tipoComprobante = ce.getEncabezado().getCodigoDocumento();
            dto.numeroControl = ce.getEncabezado().getNumeroConsecutivo();
            dto.fechaEmision = ce.getEncabezado().getFechaEmision();

            if (ce.getEncabezado().getReceptor() != null) {
                dto.razonSocial = ce.getEncabezado().getReceptor().getNombre();

                if (ce.getEncabezado().getReceptor().getIdentificacion() != null) {
                    dto.cedulaRuc = ce.getEncabezado().getReceptor().getIdentificacion().getNumero();
                }

                List<?> correos = ce.getEncabezado().getReceptor().getCorreosElectronicos();
                if (correos != null && !correos.isEmpty()) {
                    var primerCorreo = correos.get(0);
                    if (primerCorreo instanceof Models.Encabezado.CorreoElectronicoReceptor c) {
                        dto.correoElectronico = c.getCorreo();
                    }
                }
            }
        }

        if (ce.getResumen() != null) {
            dto.total = ce.getResumen().getTotalComprobante();
        }

        return dto;
    }

    /**
     * Public DTO for issued invoices (no internal entity relationships).
     */
    public static class InvoiceDTO {
        public Long id;
        public String tipoComprobante;
        public String numeroControl;
        public LocalDateTime fechaEmision;
        public String razonSocial;
        public String cedulaRuc;
        public String correoElectronico;
        public Boolean status;
        public BigDecimal total;
    }
}
