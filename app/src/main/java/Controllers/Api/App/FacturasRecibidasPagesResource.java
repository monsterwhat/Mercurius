package Controllers.Api.App;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * D14 — real page route for the received-invoices inbox
 * ({@code GET /app/facturas-recibidas}).
 *
 * <p>Reuses {@link FacturasRecibidasResource#tablaModel(String, int, int, String,
 * String, String)} and the existing page template, so the page and the
 * {@code /api/app/facturas-recibidas/tabla} fragment cannot disagree. Accepts
 * the same query keys as the fragment.</p>
 */
@Path("/app/facturas-recibidas")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"admin", "facturacion"})
public class FacturasRecibidasPagesResource {

    private static final Logger LOG = Logger.getLogger(FacturasRecibidasPagesResource.class);

    @Inject
    @Nonnull
    FacturasRecibidasResource recibidosResource;

    @Inject
    @Nonnull
    @Location("pages/facturas-recibidas/index")
    Template pageIndex;

    @GET
    public Response index(
            @QueryParam("bucket") @DefaultValue(FacturasRecibidasResource.BUCKET_TODAS) String bucket,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("sort") @Nullable String sort,
            @QueryParam("dir") @DefaultValue("asc") String dir,
            @QueryParam("q") @Nullable String q) {
        try {
            Map<String, Object> modelo = recibidosResource.tablaModel(bucket, page, size, sort, dir, q);
            String qSafe = q == null ? "" : q;
            return Response.ok(pageIndex.instance()
                            .data("modelo", modelo)
                            .data("q", qSafe)
                            .data("bucket", modelo.get("bucket")))
                    .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                    .build();
        } catch (RuntimeException e) {
            LOG.warn("Error renderizando la página de facturas recibidas", e);
            return Response.serverError()
                    .entity("No se pudieron cargar las facturas recibidas")
                    .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                    .build();
        }
    }
}
