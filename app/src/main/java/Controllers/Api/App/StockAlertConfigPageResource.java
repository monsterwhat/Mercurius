package Controllers.Api.App;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Página de umbrales de stock por artículo en una ruta limpia
 * ({@code /app/stock-alert-config}).
 *
 * <p>La pantalla solo existía en
 * {@code GET /api/app/stock-alert-config/pagina}: el enlace de Ajustes dejaba
 * la barra de direcciones en una URL de API. La ruta antigua sigue viva para
 * los fragmentos htmx (loader y guardado).</p>
 */
@Path("/app/stock-alert-config")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"admin", "inventario"})
public class StockAlertConfigPageResource {

    @Inject
    StockAlertConfigResource stockAlertConfigResource;

    @Context
    HttpHeaders headers;

    @GET
    public Response index(@QueryParam("articulo") String articulo,
                          @QueryParam("page") @jakarta.ws.rs.DefaultValue("1") int page,
                          @QueryParam("size") @jakarta.ws.rs.DefaultValue("10") int size) {
        return stockAlertConfigResource.pagina(headers, articulo, page, size);
    }
}
