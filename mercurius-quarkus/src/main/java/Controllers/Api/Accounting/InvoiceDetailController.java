package Controllers.Api.Accounting;

import Models.ComprobantesEmitidos;
import Models.Detalles.Descuento;
import Models.Detalles.Impuesto;
import Models.Detalles.LineaDetalle;
import Models.DTO.ApiResponse;
import Models.Encabezado.Receptor;
import Models.Resumen.ResumenFactura;
import Services.PublicInvoiceService;
import jakarta.annotation.Nonnull;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jboss.logging.Logger;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Accounting invoice detail endpoints.
 * Read-only: provides full invoice detail and summary for accounting integration.
 */
@Path("/api/v1/accounting/invoices")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Accounting - Invoice Detail")
public class InvoiceDetailController {

    private static final Logger LOG = Logger.getLogger(InvoiceDetailController.class);

    @Inject
    @Nonnull
    PublicInvoiceService publicInvoiceService;

    @GET
    @Path("/{id}/detail")
    @Operation(summary = "Get full invoice detail with line items and taxes")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "404", description = "Not found"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response getInvoiceDetail(@PathParam("id") @Parameter(description = "Resource ID") Long id) {
        try {
            ComprobantesEmitidos invoice = publicInvoiceService.getInvoiceDetail(id);
            if (invoice == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("NOT_FOUND", "Invoice not found"))
                        .build();
            }
            return Response.ok(ApiResponse.ok(toDetailDTO(invoice))).build();
        } catch (Exception e) {
            LOG.warn("Error getting invoice detail for id=" + id, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error getting invoice detail"))
                    .build();
        }
    }

    @GET
    @Path("/{id}/summary")
    @Operation(summary = "Get invoice summary with totals only")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "404", description = "Not found"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response getInvoiceSummary(@PathParam("id") @Parameter(description = "Resource ID") Long id) {
        try {
            Map<String, Object> summary = publicInvoiceService.getInvoiceSummary(id);
            if (summary.isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("NOT_FOUND", "Invoice not found"))
                        .build();
            }
            return Response.ok(ApiResponse.ok(toSummaryDTO(summary))).build();
        } catch (Exception e) {
            LOG.warn("Error getting invoice summary for id=" + id, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error getting invoice summary"))
                    .build();
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // DTO mapping helpers
    // ────────────────────────────────────────────────────────────────────

    private InvoiceDetailDTO toDetailDTO(ComprobantesEmitidos invoice) {
        InvoiceDetailDTO dto = new InvoiceDetailDTO();
        dto.id = invoice.getId();

        if (invoice.getEncabezado() != null) {
            var encabezado = invoice.getEncabezado();
            dto.tipoComprobante = encabezado.getCodigoDocumento();
            dto.numeroControl = encabezado.getNumeroConsecutivo();
            dto.fechaEmision = encabezado.getFechaEmision();

            if (encabezado.getReceptor() != null) {
                Receptor receptor = encabezado.getReceptor();
                dto.razonSocial = receptor.getNombre();
                if (receptor.getIdentificacion() != null) {
                    dto.cedulaRuc = receptor.getIdentificacion().getNumero();
                }
            }
        }

        // Map line items
        dto.detalles = new ArrayList<>();
        if (invoice.getDetalles() != null && invoice.getDetalles().getLineasDetalle() != null) {
            for (LineaDetalle linea : invoice.getDetalles().getLineasDetalle()) {
                LineItemDTO lineItem = new LineItemDTO();
                lineItem.numeroLinea = linea.getNumeroLinea();
                lineItem.cantidad = linea.getCantidad();
                lineItem.unidadMedida = linea.getUnidadMedida();
                lineItem.detalle = linea.getDetalle();
                lineItem.precioUnitario = linea.getPrecioUnitario();
                lineItem.subTotal = linea.getSubTotal();
                lineItem.baseImponible = linea.getBaseImponible();
                lineItem.impuestoNeto = linea.getImpuestoNeto();
                lineItem.montoTotalLinea = linea.getMontoTotalLinea();

                // Map discounts
                lineItem.descuentos = new ArrayList<>();
                if (linea.getDescuentos() != null) {
                    for (Descuento desc : linea.getDescuentos()) {
                        DiscountDTO discount = new DiscountDTO();
                        discount.montoDescuento = desc.getMontoDescuento();
                        discount.codigoDescuento = desc.getCodigoDescuento();
                        discount.naturalezaDescuento = desc.getNaturalezaDescuento();
                        lineItem.descuentos.add(discount);
                    }
                }

                // Map taxes
                lineItem.impuestos = new ArrayList<>();
                if (linea.getImpuestos() != null) {
                    for (Impuesto imp : linea.getImpuestos()) {
                        TaxDTO tax = new TaxDTO();
                        tax.codigo = imp.getCodigo();
                        tax.codigoTarifaIVA = imp.getCodigoTarifaIVA();
                        tax.tarifa = imp.getTarifa();
                        tax.monto = imp.getMonto();
                        lineItem.impuestos.add(tax);
                    }
                }

                dto.detalles.add(lineItem);
            }
        }

        // Map summary/resumen
        dto.resumen = new InvoiceSummaryResumen();
        if (invoice.getResumen() != null) {
            ResumenFactura resumen = invoice.getResumen();
            dto.resumen.subtotal = resumen.getTotalVentaNeta();
            dto.resumen.totalDescuentos = resumen.getTotalDescuentos();
            dto.resumen.totalImpuesto = resumen.getTotalImpuesto();
            dto.resumen.totalComprobante = resumen.getTotalComprobante();
        }

        return dto;
    }

    private InvoiceSummaryDTO toSummaryDTO(Map<String, Object> summary) {
        InvoiceSummaryDTO dto = new InvoiceSummaryDTO();
        dto.subtotal = getBigDecimal(summary, "totalVentaNeta");
        dto.totalDescuento = getBigDecimal(summary, "totalDescuentos");
        dto.totalIVA = getBigDecimal(summary, "totalImpuesto");
        dto.total = getBigDecimal(summary, "totalComprobante");

        // mediosPago not included in summary map; fetch payments separately
        dto.mediosPago = new ArrayList<>();
        return dto;
    }

    private BigDecimal getBigDecimal(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof BigDecimal bd) {
            return bd;
        }
        return BigDecimal.ZERO;
    }

    // ────────────────────────────────────────────────────────────────────
    // DTO classes
    // ────────────────────────────────────────────────────────────────────

    /**
     * Full invoice detail DTO for accounting integration.
     */
    public static class InvoiceDetailDTO {
        public Long id;
        public String tipoComprobante;
        public String numeroControl;
        public LocalDateTime fechaEmision;
        public String razonSocial;
        public String cedulaRuc;
        public List<LineItemDTO> detalles;
        public InvoiceSummaryResumen resumen;
    }

    /**
     * Invoice summary (totals only) for accounting integration.
     */
    public static class InvoiceSummaryDTO {
        public BigDecimal subtotal;
        public BigDecimal totalDescuento;
        public BigDecimal totalIVA;
        public BigDecimal total;
        public List<PaymentDTO> mediosPago;
    }

    /**
     * Line item within an invoice detail.
     */
    public static class LineItemDTO {
        public Integer numeroLinea;
        public BigDecimal cantidad;
        public String unidadMedida;
        public String detalle;
        public BigDecimal precioUnitario;
        public BigDecimal subTotal;
        public BigDecimal baseImponible;
        public BigDecimal impuestoNeto;
        public BigDecimal montoTotalLinea;
        public List<DiscountDTO> descuentos;
        public List<TaxDTO> impuestos;
    }

    /**
     * Discount applied to a line item.
     */
    public static class DiscountDTO {
        public BigDecimal montoDescuento;
        public String codigoDescuento;
        public String naturalezaDescuento;
    }

    /**
     * Tax applied to a line item.
     */
    public static class TaxDTO {
        public String codigo;
        public String codigoTarifaIVA;
        public BigDecimal tarifa;
        public BigDecimal monto;
    }

    /**
     * Summary totals embedded in the full detail response.
     */
    public static class InvoiceSummaryResumen {
        public BigDecimal subtotal;
        public BigDecimal totalDescuentos;
        public BigDecimal totalImpuesto;
        public BigDecimal totalComprobante;
    }

    /**
     * Payment method entry.
     */
    public static class PaymentDTO {
        public String tipoMedioPago;
        public BigDecimal totalMedioPago;
    }
}
