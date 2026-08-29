package Controllers.Api.Accounting;

import Models.Clients;
import Models.DTO.ApiResponse;
import Models.DTO.PagedResponse;
import Services.PublicInvoiceService;
import jakarta.annotation.Nonnull;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.*;

import org.jboss.logging.Logger;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Accounting suppliers endpoints.
 * Read-only: provides supplier information for accounting.
 * In this system, suppliers are stored as Clients.
 */
@Path("/api/v1/accounting/suppliers")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Accounting - Suppliers")
public class SuppliersController {

    private static final Logger LOG = Logger.getLogger(SuppliersController.class);

    @Inject
    @Nonnull
    PublicInvoiceService publicInvoiceService;

    @GET
    @Operation(summary = "List suppliers with pagination")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response listSuppliers(
            @QueryParam("page") @DefaultValue("0") @Parameter(description = "Page number (0-based)") int page,
            @QueryParam("size") @DefaultValue("20") @Parameter(description = "Page size") int size) {

        // Clamp size to max 100
        size = Math.min(Math.max(size, 1), 100);
        page = Math.max(page, 0);

        try {
            // PublicInvoiceService uses 1-based page; controller uses 0-based
            Map<String, Object> result = publicInvoiceService.getSuppliers(page + 1, size);

            @SuppressWarnings("unchecked")
            List<Clients> data = (List<Clients>) result.getOrDefault("data", List.of());
            Long total = (Long) result.getOrDefault("total", 0L);

            List<SupplierDTO> dtos = data.stream()
                    .map(this::toDTO)
                    .toList();

            PagedResponse<SupplierDTO> paged = new PagedResponse<>(dtos, total, page, size);
            return Response.ok(paged).build();
        } catch (Exception e) {
            LOG.warn("Error listing suppliers", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error listing suppliers"))
                    .build();
        }
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Get a supplier by ID")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "404", description = "Not found"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response getSupplier(@PathParam("id") @Parameter(description = "Resource ID") int id) {
        try {
            // Get all suppliers and find by id
            Map<String, Object> result = publicInvoiceService.getSuppliers(null, null);

            @SuppressWarnings("unchecked")
            List<Clients> data = (List<Clients>) result.getOrDefault("data", List.of());

            Optional<Clients> supplier = data.stream()
                    .filter(c -> c.getCode() == id)
                    .findFirst();

            if (supplier.isEmpty()) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("NOT_FOUND", "Supplier not found"))
                        .build();
            }
            return Response.ok(toDTO(supplier.get())).build();
        } catch (Exception e) {
            LOG.warn("Error getting supplier", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error getting supplier"))
                    .build();
        }
    }

    private SupplierDTO toDTO(Clients client) {
        SupplierDTO dto = new SupplierDTO();
        dto.id = client.getCode();
        dto.nombre = client.getName();
        dto.cedulaRuc = client.getIdNumber();
        dto.correoElectronico = client.getEmail();
        dto.telefono = client.getPhoneNumber();
        dto.direccion = client.getAddress();
        dto.status = client.getStatus();
        return dto;
    }

    /**
     * Public DTO for suppliers (no internal entity relationships).
     */
    public static class SupplierDTO {
        public int id;
        public String nombre;
        public String cedulaRuc;
        public String correoElectronico;
        public String telefono;
        public String direccion;
        public Boolean status;
    }
}
