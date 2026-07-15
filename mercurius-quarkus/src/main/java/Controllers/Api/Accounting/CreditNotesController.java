package Controllers.Api.Accounting;

import Models.ComprobantesEmitidos;
import Models.NotaCredito;
import Models.DTO.ApiResponse;
import Services.PublicInvoiceService;
import jakarta.annotation.Nonnull;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Accounting credit notes endpoints.
 * Read-only: provides credit note information for accounting.
 */
@Path("/api/v1/accounting/credit-notes")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Accounting - Credit Notes")
public class CreditNotesController {

    private static final Logger LOG = Logger.getLogger(CreditNotesController.class.getName());

    @Inject
    @Nonnull
    PublicInvoiceService publicInvoiceService;

    @GET
    @Operation(summary = "List all credit notes")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response listCreditNotes() {
        try {
            List<NotaCredito> notes = publicInvoiceService.getCreditNotes();

            List<CreditNoteDTO> dtos = notes.stream()
                    .map(this::toDTO)
                    .toList();

            return Response.ok(dtos).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error listing credit notes", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error listing credit notes"))
                    .build();
        }
    }

    private CreditNoteDTO toDTO(NotaCredito nc) {
        CreditNoteDTO dto = new CreditNoteDTO();
        dto.id = nc.getId();
        dto.fecha = nc.getFecha();
        dto.motivo = nc.getMotivo();
        dto.monto = nc.getMontoTotal();
        dto.usuario = nc.getUsuario();
        dto.haciendaClave = nc.getHaciendaClave();
        dto.haciendaEstado = nc.getHaciendaEstado();
        dto.status = nc.getStatus();
        dto.fechaAnulacion = nc.getFechaAnulacion();

        if (nc.getCliente() != null) {
            dto.cliente = nc.getCliente().getName();
        }

        if (nc.getComprobanteOriginal() != null) {
            dto.comprobanteOriginalId = nc.getComprobanteOriginal().getId();

            ComprobantesEmitidos ce = nc.getComprobanteOriginal();
            if (ce.getEncabezado() != null) {
                dto.numeroComprobanteOriginal = ce.getEncabezado().getNumeroConsecutivo();
            }
        }

        return dto;
    }

    /**
     * Public DTO for credit notes (no internal entity relationships).
     */
    public static class CreditNoteDTO {
        public Long id;
        public Date fecha;
        public String motivo;
        public BigDecimal monto;
        public String cliente;
        public String usuario;
        public String haciendaClave;
        public String haciendaEstado;
        public Boolean status;
        public Date fechaAnulacion;
        public Long comprobanteOriginalId;
        public String numeroComprobanteOriginal;
    }
}
