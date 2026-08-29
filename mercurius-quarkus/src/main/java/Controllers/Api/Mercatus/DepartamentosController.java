package Controllers.Api.Mercatus;

import Models.Departamento;
import Models.DTO.ApiResponse;
import Models.DTO.PagedResponse;
import Services.DepartamentoService;
import jakarta.annotation.Nonnull;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;

import org.jboss.logging.Logger;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Mercatus department catalog endpoints.
 * Read-only: provides department (distributor/supplier) information for the marketplace.
 */
@Path("/api/v1/mercatus/departamentos")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Mercatus - Departments")
public class DepartamentosController {

    private static final Logger LOG = Logger.getLogger(DepartamentosController.class);

    @Inject
    @Nonnull
    DepartamentoService departamentoService;

    @GET
    @Operation(summary = "List all active departments")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response listDepartamentos() {
        try {
            List<Departamento> allDepartamentos = departamentoService.listAllActive();
            if (allDepartamentos == null) allDepartamentos = List.of();

            List<DepartamentoDTO> dtos = allDepartamentos.stream()
                    .map(this::toDTO)
                    .toList();

            long total = dtos.size();
            PagedResponse<DepartamentoDTO> paged = new PagedResponse<>(dtos, total, 0, dtos.size());
            return Response.ok(paged).build();
        } catch (Exception e) {
            LOG.warn("Error listing departamentos", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error listing departamentos"))
                    .build();
        }
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Get a department by ID")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "404", description = "Not found"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response getDepartamento(@PathParam("id") @Parameter(description = "Resource ID") Long id) {
        try {
            Departamento departamento = departamentoService.findById(id.intValue());
            if (departamento == null || !Boolean.TRUE.equals(departamento.getStatus())) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("NOT_FOUND", "Departamento not found"))
                        .build();
            }
            return Response.ok(toDTO(departamento)).build();
        } catch (Exception e) {
            LOG.warn("Error getting departamento", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error getting departamento"))
                    .build();
        }
    }

    private DepartamentoDTO toDTO(Departamento d) {
        DepartamentoDTO dto = new DepartamentoDTO();
        dto.id = (long) d.getId();
        dto.name = d.getNombre();
        dto.contactName = d.getContactoNombre();
        dto.contactPhone = d.getContactoTelefono();
        dto.contactEmail = d.getContactoEmail();
        dto.paymentTermDays = d.getPlazoPagoDias();
        dto.deliveryDays = d.getTiempoEntregaDias();
        dto.notes = d.getNotas();
        dto.status = d.getStatus();
        return dto;
    }

    /**
     * Public DTO for department catalog (no internal user references).
     */
    public static class DepartamentoDTO {
        public Long id;
        public String name;
        public String contactName;
        public String contactPhone;
        public String contactEmail;
        public Integer paymentTermDays;
        public Integer deliveryDays;
        public String notes;
        public Boolean status;
    }
}
