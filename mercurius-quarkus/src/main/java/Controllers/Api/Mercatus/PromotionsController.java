package Controllers.Api.Mercatus;

import Models.Articulos.Promocion;
import Models.DTO.ApiResponse;
import Models.DTO.PagedResponse;
import Services.PromocionesService;
import jakarta.annotation.Nonnull;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
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
 * Mercatus promotion catalog endpoints.
 * Read-only: provides active promotion information for the marketplace.
 */
@Path("/api/v1/mercatus/promotions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Mercatus - Promotions")
public class PromotionsController {

    private static final Logger LOG = Logger.getLogger(PromotionsController.class.getName());

    @Inject
    @Nonnull
    PromocionesService promocionesService;

    @GET
    @Operation(summary = "List all active promotions")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response listPromotions() {
        try {
            List<Promocion> allPromotions = promocionesService.listAll();
            if (allPromotions == null) allPromotions = List.of();

            Date now = new Date();
            List<PromotionDTO> dtos = allPromotions.stream()
                    .filter(Promocion::isActiva)
                    .filter(p -> p.getFechaFin() == null || p.getFechaFin().after(now))
                    .map(this::toDTO)
                    .toList();

            long total = dtos.size();
            PagedResponse<PromotionDTO> paged = new PagedResponse<>(dtos, total, 0, dtos.size());
            return Response.ok(paged).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error listing promotions", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error listing promotions"))
                    .build();
        }
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Get a promotion by ID")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "404", description = "Not found"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response getPromotion(@PathParam("id") @Parameter(description = "Resource ID") Long id) {
        try {
            Promocion promocion = promocionesService.findById(id.intValue());
            if (promocion == null || !promocion.isActiva()) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("NOT_FOUND", "Promotion not found"))
                        .build();
            }
            return Response.ok(toDTO(promocion)).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error getting promotion", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error getting promotion"))
                    .build();
        }
    }

    private PromotionDTO toDTO(Promocion p) {
        PromotionDTO dto = new PromotionDTO();
        dto.id = (long) p.getId();
        dto.name = p.getNombre();
        dto.discount = p.getDescuento();
        dto.quantity = p.getCantidad();
        dto.startDate = p.getFechaInicio();
        dto.endDate = p.getFechaFin();
        dto.active = p.isActiva();
        dto.originAssembled = p.isEnsambladoOrigen();
        dto.discountCode = p.getCodigoDescuento();
        return dto;
    }

    /**
     * Public DTO for promotion catalog (no internal user or cart references).
     */
    public static class PromotionDTO {
        public Long id;
        public String name;
        public java.math.BigDecimal discount;
        public java.math.BigDecimal quantity;
        public Date startDate;
        public Date endDate;
        public boolean active;
        public boolean originAssembled;
        public String discountCode;
    }
}
