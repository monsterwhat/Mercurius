package Controllers.Api.Mercatus;

import Models.DTO.ApiResponse;
import Models.DTO.PagedResponse;
import Models.Familia;
import Services.FamiliaService;
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
 * Mercatus product family catalog endpoints.
 * Read-only: provides family (product grouping) information for the marketplace.
 */
@Path("/api/v1/mercatus/families")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Mercatus - Families")
public class FamiliesController {

    private static final Logger LOG = Logger.getLogger(FamiliesController.class);

    @Inject
    @Nonnull
    FamiliaService familiaService;

    @GET
    @Operation(summary = "List all product families")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response listFamilies() {
        try {
            List<Familia> allFamilies = familiaService.listAll();
            if (allFamilies == null) allFamilies = List.of();

            List<FamilyDTO> dtos = allFamilies.stream()
                    .map(this::toDTO)
                    .toList();

            long total = dtos.size();
            PagedResponse<FamilyDTO> paged = new PagedResponse<>(dtos, total, 0, dtos.size());
            return Response.ok(paged).build();
        } catch (Exception e) {
            LOG.warn("Error listing families", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error listing families"))
                    .build();
        }
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Get a product family by ID")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "404", description = "Not found"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response getFamily(@PathParam("id") @Parameter(description = "Resource ID") Long id) {
        try {
            Familia familia = familiaService.findById(id.intValue());
            if (familia == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("NOT_FOUND", "Family not found"))
                        .build();
            }
            return Response.ok(toDTO(familia)).build();
        } catch (Exception e) {
            LOG.warn("Error getting family", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error getting family"))
                    .build();
        }
    }

    private FamilyDTO toDTO(Familia f) {
        FamilyDTO dto = new FamilyDTO();
        dto.id = (long) f.getId();
        dto.name = f.getNombre();
        dto.status = f.getStatus();
        return dto;
    }

    /**
     * Public DTO for family catalog (no internal user references).
     */
    public static class FamilyDTO {
        public Long id;
        public String name;
        public Boolean status;
    }
}
