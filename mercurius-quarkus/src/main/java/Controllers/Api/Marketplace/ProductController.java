package Controllers.Api.Marketplace;

import Models.DTO.ProductDTO;
import Models.DTO.ProductDetailDTO;
import Services.MarketplaceProductService;
import jakarta.annotation.Nonnull;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

@Path("/api/marketplace/products")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ProductController {

    private static final Logger LOG = Logger.getLogger(ProductController.class.getName());

    @Inject
    @Nonnull
    MarketplaceProductService productService;

    @GET
    @Nonnull
    public Response listProducts() {
        try {
            List<ProductDTO> products = productService.listActiveProducts();
            return Response.ok(products).build();
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Error listing products", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Error al cargar productos\"}")
                    .build();
        }
    }

    @GET
    @Path("/search")
    @Nonnull
    public Response searchProducts(@QueryParam("q") String query) {
        try {
            if (query == null || query.isBlank()) {
                return listProducts();
            }
            List<ProductDTO> products = productService.searchProducts(query);
            return Response.ok(products).build();
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Error searching products", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Error al buscar productos\"}")
                    .build();
        }
    }

    @GET
    @Path("/{codigo}")
    @Nonnull
    public Response getProductDetail(@PathParam("codigo") Long codigo) {
        try {
            ProductDetailDTO product = productService.getProductDetail(codigo);
            if (product == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity("{\"error\":\"Producto no encontrado\"}")
                        .build();
            }
            return Response.ok(product).build();
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Error getting product detail", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Error al cargar detalle del producto\"}")
                    .build();
        }
    }
}
