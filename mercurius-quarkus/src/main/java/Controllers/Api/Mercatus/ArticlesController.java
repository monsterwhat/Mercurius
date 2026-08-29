package Controllers.Api.Mercatus;

import Models.Articulos.ArticuloImagen;
import Models.Articulos.ArticuloPrecio;
import Models.Articulos.Articulos;
import Models.DTO.ApiResponse;
import Models.DTO.PagedResponse;
import Services.ArticulosService;
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
 * Mercatus product catalog endpoints.
 * Read-only: provides article information for the marketplace.
 */
@Path("/api/v1/mercatus/articles")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Mercatus - Articles")
public class ArticlesController {

    private static final Logger LOG = Logger.getLogger(ArticlesController.class);

    @Inject
    @Nonnull
    ArticulosService articulosService;

    @GET
    @Operation(summary = "List articles with pagination and optional search")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response listArticles(
            @QueryParam("page") @DefaultValue("0") @Parameter(description = "Page number (0-based)") int page,
            @QueryParam("size") @DefaultValue("20") @Parameter(description = "Page size") int size,
            @QueryParam("search") @Parameter(description = "Search term to filter articles by name") String search) {

        // Clamp size to max 100
        size = Math.min(Math.max(size, 1), 100);
        page = Math.max(page, 0);

        try {
            List<Articulos> allArticles;
            if (search != null && !search.isBlank()) {
                allArticles = articulosService.findByNameContaining(search);
            } else {
                allArticles = articulosService.listAllActivosYProcesados();
            }

            if (allArticles == null) allArticles = List.of();

            long total = allArticles.size();
            int start = page * size;
            int end = Math.min(start + size, allArticles.size());
            List<Articulos> pageArticles = start < allArticles.size() ? allArticles.subList(start, end) : List.of();

            List<ArticleDTO> dtos = pageArticles.stream()
                    .map(this::toDTO)
                    .toList();

            PagedResponse<ArticleDTO> paged = new PagedResponse<>(dtos, total, page, size);
            return Response.ok(paged).build();
        } catch (Exception e) {
            LOG.warn("Error listing articles", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error listing articles"))
                    .build();
        }
    }

    @GET
    @Path("/{id}")
    @Operation(summary = "Get an article by ID")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "404", description = "Not found"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response getArticle(@PathParam("id") @Parameter(description = "Resource ID") Long id) {
        try {
            Articulos articulo = articulosService.findById(id.intValue());
            if (articulo == null || !articulo.isStatus() || !articulo.isProcessed()) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("NOT_FOUND", "Article not found"))
                        .build();
            }
            return Response.ok(toDTO(articulo)).build();
        } catch (Exception e) {
            LOG.warn("Error getting article", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error getting article"))
                    .build();
        }
    }

    @GET
    @Path("/{id}/pictures")
    @Operation(summary = "Get pictures for an article")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "404", description = "Not found"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    @Nonnull
    public Response getArticlePictures(@PathParam("id") @Parameter(description = "Resource ID") Long id) {
        try {
            Articulos articulo = articulosService.findById(id.intValue());
            if (articulo == null || !articulo.isStatus() || !articulo.isProcessed()) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("NOT_FOUND", "Article not found"))
                        .build();
            }

            List<ArticuloImagen> images = articulo.getImagenes();
            if (images == null) images = List.of();

            List<ImageDTO> dtos = images.stream()
                    .map(img -> {
                        ImageDTO dto = new ImageDTO();
                        dto.id = img.getId();
                        dto.url = img.getRuta();
                        dto.name = img.getNombreOriginal();
                        dto.mimeType = img.getMimeType();
                        dto.order = img.getOrden();
                        return dto;
                    })
                    .sorted(Comparator.comparingInt(dto -> dto.order))
                    .toList();

            return Response.ok(dtos).build();
        } catch (Exception e) {
            LOG.warn("Error getting article pictures", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error getting article pictures"))
                    .build();
        }
    }

    private ArticleDTO toDTO(Articulos a) {
        ArticleDTO dto = new ArticleDTO();
        dto.id = a.getCodigo();
        dto.name = a.getNombre();
        dto.description = a.getDescripcion();
        dto.barcode = a.getCodigoBarra();
        dto.unit = a.getUnidadMedida();
        dto.status = a.isStatus();
        dto.processed = a.isProcessed();

        ArticuloPrecio lastPrecio = a.getLastPrecio();
        if (lastPrecio != null) {
            dto.price = lastPrecio.getPrecioFinal();
            dto.costPrice = lastPrecio.getPrecioCostoSinIVA();
        } else {
            dto.price = BigDecimal.ZERO;
            dto.costPrice = BigDecimal.ZERO;
        }

        if (a.getDepartamento() != null) {
            dto.departmentId = a.getDepartamento().getId();
            dto.departmentName = a.getDepartamento().getNombre();
        }

        if (a.getFamilia() != null) {
            dto.familyId = a.getFamilia().getId();
            dto.familyName = a.getFamilia().getNombre();
        }

        return dto;
    }

    /**
     * Public DTO for article catalog (no internal IDs, no entity relationships).
     */
    public static class ArticleDTO {
        public Long id;
        public String name;
        public String description;
        public String barcode;
        public String unit;
        public BigDecimal price;
        public BigDecimal costPrice;
        public boolean status;
        public boolean processed;
        public Integer departmentId;
        public String departmentName;
        public Integer familyId;
        public String familyName;
    }

    public static class ImageDTO {
        public Long id;
        public String url;
        public String name;
        public String mimeType;
        public int order;
    }
}
