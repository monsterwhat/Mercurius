package Controllers.Api.App;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.Nonnull;
import Controllers.Api.App.Reportes.ReportePageSupport;
import jakarta.annotation.Nullable;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import Models.ComprobantesEmitidos;

import org.jboss.logging.Logger;

/**
 * T27 — Recibos page ({@code GET /app/recibos}) for the NEW Qute/HTMX
 * surface: the four-table board (Todas / Pagadas / Procesadas / Vencidos)
 * that replaces {@code secured/pages/Recibos/index.xhtml}. Same split as the
 * other W4 modules (Devoluciones/Tributación): this class only renders HTML,
 * while all reads/actions live in the JSON twin {@link RecibosResource}
 * ({@code /api/app/recibos}), whose table/stats models are reused here so
 * page and fragments can never disagree.
 *
 * <p><b>Roles:</b> {@code admin} + {@code facturacion} (legacy web.xml gate
 * for {@code /secured/pages/Recibos/*}).</p>
 */
/**
 * Tablero de las facturas EMITIDAS (las que el negocio emite y tiene que
 * cobrar).
 *
 * <p>Antes esta pantalla vivia en {@code /app/recibos}, pero "recibos" en el
 * sistema viejo son las facturas que se RECIBEN (las que pasan los
 * proveedores). La pantalla de emitidas quedo en {@code /app/facturas} y
 * {@code /app/recibos} lleva a las recibidas.</p>
 */
@Path("/app/facturas")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"admin", "facturacion"})
public class RecibosPagesResource {

    private static final Logger LOG = Logger.getLogger(RecibosPagesResource.class);

    @Inject
    @Nonnull
    RecibosResource recibos;

    @Context
    @Nonnull
    HttpHeaders httpHeaders;

    @Inject
    @Nonnull
    @Location("pages/recibos/index")
    Template pageIndex;

    /**
     * Full board page. With {@code HX-Request} only the {@code tablero}
     * fragment (stats + four tables) is rendered — the same fragment the
     * action endpoints swap, so ids stay stable everywhere.
     */
    @GET
    public Response index(
            @QueryParam("bucket") @DefaultValue(RecibosResource.BUCKET_TODAS) String bucket,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("sort") @Nullable String sort,
            @QueryParam("dir") @DefaultValue("asc") String dir,
            @QueryParam("q") @Nullable String q) {
        try {
            String cubeta = bucket == null || bucket.isBlank()
                    ? RecibosResource.BUCKET_TODAS : bucket;
            // Cada bucket se consulta paginado en SQL: el historico completo no
            // cabe en memoria.
            Map<String, Object> modelos = new LinkedHashMap<>();
            for (String b : List.of(RecibosResource.BUCKET_TODAS, RecibosResource.BUCKET_PAGADAS,
                    RecibosResource.BUCKET_PROCESADAS, RecibosResource.BUCKET_VENCIDOS)) {
                if (b.equals(cubeta)) {
                    modelos.put(b, recibos.tablaModel(b, page, size, sort, dir, q, null));
                } else {
                    modelos.put(b, recibos.tablaModel(b, 1, 20, null, "asc", null, null));
                }
            }
            TemplateInstance instance;
            if (ReportePageSupport.isHxRequest(httpHeaders)) {
                instance = recibos.tableroInstance(null, null);
            } else {
                instance = pageIndex.instance()
                        .data("stats", recibos.statsView(null))
                        .data("modelos", modelos)
                        .data("bucket", cubeta)
                        .data("q", q);
            }
            return Response.ok(instance.render())
                    .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).build();
        } catch (RuntimeException e) {
            LOG.warn("Error renderizando la página de facturas emitidas", e);
            return Response.serverError()
                    .entity(Models.DTO.ApiResponse.error("INTERNAL_ERROR",
                            "No se pudieron cargar las facturas emitidas"))
                    .build();
        }
    }
}
