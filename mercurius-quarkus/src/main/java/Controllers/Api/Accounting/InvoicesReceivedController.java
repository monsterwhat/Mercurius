package Controllers.Api.Accounting;

import Models.ComprobantesRecibidos;
import Models.DTO.ApiResponse;
import Models.DTO.PagedResponse;
import Models.Encabezado.CorreoElectronicoEmisor;
import Services.PublicInvoiceService;
import jakarta.annotation.Nonnull;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Accounting received invoices endpoints.
 * Read-only: provides received invoice information for accounting.
 */
@Path("/api/v1/accounting/invoices/received")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Accounting - Invoices Received")
public class InvoicesReceivedController {

    private static final Logger LOG = Logger.getLogger(InvoicesReceivedController.class.getName());

    @Inject
    @Nonnull
    PublicInvoiceService publicInvoiceService;

    /**
     * List received invoices with pagination.
     *
     * @param page zero-based page index
     * @param size items per page (clamped to 1..100)
     * @return paged list of received invoices
     */
    @GET
    @Operation(summary = "List received invoices with pagination")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response listReceivedInvoices(
            @QueryParam("page") @DefaultValue("0") @Parameter(description = "Page number (0-based)") int page,
            @QueryParam("size") @DefaultValue("20") @Parameter(description = "Page size") int size) {

        size = Math.min(Math.max(size, 1), 100);
        page = Math.max(page, 0);

        try {
            // PublicInvoiceService uses 1-based pages
            int servicePage = page + 1;
            Map<String, Object> result = publicInvoiceService.getReceivedInvoices(servicePage, size);

            @SuppressWarnings("unchecked")
            List<ComprobantesRecibidos> data = (List<ComprobantesRecibidos>) result.get("data");
            Long total = (Long) result.get("total");

            if (data == null) data = List.of();
            if (total == null) total = 0L;

            List<InvoiceReceivedDTO> dtos = data.stream()
                    .map(this::toDTO)
                    .toList();

            PagedResponse<InvoiceReceivedDTO> paged = new PagedResponse<>(dtos, total, page, size);
            return Response.ok(paged).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error listing received invoices", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error listing received invoices"))
                    .build();
        }
    }

    /**
     * Get a single received invoice by ID.
     *
     * @param id the ComprobantesRecibidos.id
     * @return the received invoice DTO
     */
    @GET
    @Path("/{id}")
    @Operation(summary = "Get a received invoice by ID")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "404", description = "Not found"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response getReceivedInvoice(@PathParam("id") @Parameter(description = "Resource ID") Long id) {
        try {
            // PublicInvoiceService has no single-item method; fetch all and filter
            Map<String, Object> result = publicInvoiceService.getReceivedInvoices(null, null);

            @SuppressWarnings("unchecked")
            List<ComprobantesRecibidos> data = (List<ComprobantesRecibidos>) result.get("data");

            if (data == null) data = List.of();

            Optional<ComprobantesRecibidos> found = data.stream()
                    .filter(cr -> id.equals(cr.getId()))
                    .findFirst();

            if (found.isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("NOT_FOUND", "Received invoice not found"))
                        .build();
            }

            return Response.ok(toDTO(found.get())).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error getting received invoice", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error getting received invoice"))
                    .build();
        }
    }

    @SuppressWarnings("unchecked")
    private InvoiceReceivedDTO toDTO(ComprobantesRecibidos cr) {
        InvoiceReceivedDTO dto = new InvoiceReceivedDTO();
        dto.id = cr.getId();

        if (cr.getEncabezado() != null) {
            dto.tipoComprobante = cr.getEncabezado().getCodigoDocumento();
            dto.numeroControl = cr.getEncabezado().getNumeroConsecutivo();
            dto.fechaEmision = cr.getEncabezado().getFechaEmision();

            if (cr.getEncabezado().getEmisor() != null) {
                dto.razonSocial = cr.getEncabezado().getEmisor().getNombre();

                if (cr.getEncabezado().getEmisor().getIdentificacion() != null) {
                    dto.cedulaRuc = cr.getEncabezado().getEmisor().getIdentificacion().getNumero();
                }

                List<CorreoElectronicoEmisor> correos = cr.getEncabezado().getEmisor().getCorreosElectronicos();
                if (correos != null && !correos.isEmpty()) {
                    dto.correoElectronico = correos.get(0).getCorreo();
                }
            }
        }

        dto.status = cr.getStatus();

        if (cr.getResumen() != null) {
            dto.total = cr.getResumen().getTotalComprobante();
        }

        dto.isPaid = cr.getPaid() != null && cr.getPaid();

        return dto;
    }

    /**
     * Public DTO for received invoice (no entity relationships, no internal IDs).
     */
    public static class InvoiceReceivedDTO {
        public Long id;
        public String tipoComprobante;
        public String numeroControl;
        public java.time.LocalDateTime fechaEmision;
        public String razonSocial;
        public String cedulaRuc;
        public String correoElectronico;
        public Boolean status;
        public BigDecimal total;
        public boolean isPaid;
    }
}
