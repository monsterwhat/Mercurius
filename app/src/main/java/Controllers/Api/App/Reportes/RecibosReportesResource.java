package Controllers.Api.App.Reportes;

import static Controllers.Api.App.AppRecursoBase.*;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;

import Models.ComprobantesRecibidos;
import Services.ComprobantesRecibidosService;

/**
 * Read-only "Recibos Pendientes" / "Recibos Vencidos" report pages
 * ({@code GET /app/reportes/recibos/pendientes} and {@code GET
 * /app/reportes/recibos/vencidos}) replacing
 * {@code secured/pages/Recibos/Pendientes/index.xhtml} and
 * {@code secured/pages/Recibos/Vencidos/index.xhtml} (plan T20).
 *
 * <p>The legacy pages bind
 * {@code #{facturasController.filteredFacturasPendientes}} /
 * {@code filteredFacturasVencidas}, whose backing queries were
 * {@link ComprobantesRecibidosService#listPendientes()} /
 * {@link ComprobantesRecibidosService#listVencidas()}. Calling those services
 * per request materialised every unpaid received invoice (50k rows plus their
 * detail graph), did the due-date math in Java and paged in memory, which is
 * why both pages exceeded 30 s. The predicates, the {@code q} filter, the
 * ORDER BY and the window now run in SQL through
 * {@link ComprobantesRecibidosService#countRecibosPorVencimiento} /
 * {@link ComprobantesRecibidosService#pageRecibosPorVencimiento}, so a request
 * only hydrates the visible page and the pager total comes from the same
 * WHERE. The vencido rule is unchanged: unpaid received invoices whose
 * {@code fechaEmision + plazoCredito} days mark is today or past.</p>
 *
 * <p>Status filters ONLY: no pay/process/accept/reject actions (T27 owns
 * those). Role gate mirrors web.xml {@code /secured/pages/Recibos/*}:
 * {@code facturacion} + {@code admin}.</p>
 */
@Path("/app/reportes/recibos")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"facturacion", "admin"})
public class RecibosReportesResource {

    private static final String BASE_PENDIENTES = "/app/reportes/recibos/pendientes";
    private static final String BASE_VENCIDOS = "/app/reportes/recibos/vencidos";

    @Inject
    @Nonnull
    @Location("pages/reportes/recibos-pendientes")
    Template pendientesPage;

    @Inject
    @Nonnull
    @Location("pages/reportes/recibos-vencidos")
    Template vencidosPage;

    @Inject
    @Nonnull
    ComprobantesRecibidosService recibidosService;

    @Inject
    @Nonnull
    HttpHeaders httpHeaders;

    @GET
    @Path("/pendientes")
    public Response pendientes(
            @QueryParam("page") @Nullable Integer page,
            @QueryParam("size") @Nullable Integer size,
            @QueryParam("sort") @Nullable String sort,
            @QueryParam("dir") @DefaultValue("asc") @Nullable String dir,
            @QueryParam("q") @Nullable String q) {
        return render(pendientesPage, BASE_PENDIENTES, "Pendientes", false,
                page, size, sort, dir, q);
    }

    @GET
    @Path("/vencidos")
    public Response vencidos(
            @QueryParam("page") @Nullable Integer page,
            @QueryParam("size") @Nullable Integer size,
            @QueryParam("sort") @Nullable String sort,
            @QueryParam("dir") @DefaultValue("asc") @Nullable String dir,
            @QueryParam("q") @Nullable String q) {
        return render(vencidosPage, BASE_VENCIDOS, "Vencidos", true,
                page, size, sort, dir, q);
    }

    /**
     * Counts, filters, sorts and slices the report in SQL. The template model
     * keys and their meaning are the same the in-memory implementation
     * produced: {@code total} is the filtered row count, {@code totalPages}/
     * {@code pages} drive the kit pager and {@code rows} is only the current
     * page (empty list when the page is past the end, so the empty state still
     * renders).
     */
    private Response render(
            @Nonnull Template template, @Nonnull String baseUrl, @Nonnull String titulo,
            boolean vencidos,
            @Nullable Integer page, @Nullable Integer size,
            @Nullable String sort, @Nullable String dir, @Nullable String q) {

        int current = ReportePageSupport.clampPage(page);
        int pageSize = ReportePageSupport.clampSize(size);
        long total = recibidosService.countRecibosPorVencimiento(vencidos, q);
        int totalPages = ReportePageSupport.totalPages(total, pageSize);

        // Past the last page the old pageOf() returned an empty slice; keep it
        // (and skip the query, which also keeps a hostile `page` from
        // overflowing the offset).
        List<ComprobantesRecibidos> rows = total == 0 || current > totalPages
                ? List.of()
                : recibidosService.pageRecibosPorVencimiento(
                        vencidos, q, sort, dir, (current - 1) * pageSize, pageSize);

        Map<String, Object> model = ReportePageSupport.model(
                "titulo", titulo,
                "columnas", columnas(),
                "rows", rows,
                "total", total,
                "totalPages", totalPages,
                "pages", ReportePageSupport.pageWindow(current, totalPages),
                "page", current,
                "size", pageSize,
                "sortKey", sort == null ? "" : sort,
                "sortDir", ReportePageSupport.isDescending(dir) ? "desc" : "asc",
                "filtros", ReportePageSupport.params("q", q),
                "baseUrl", baseUrl);

        TemplateInstance instance = ReportePageSupport.isHxRequest(httpHeaders)
                ? template.getFragment("tabla").instance()
                : template.instance();
        model.forEach(instance::data);
        return Response.ok(instance.render())
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                .build();
    }

    private static @Nonnull List<Map<String, Object>> columnas() {
        return List.of(
                Map.of("label", "Estado", "key", "estado"),
                Map.of("label", "Numero Consecutivo", "key", "consecutivo"),
                Map.of("label", "Fecha", "key", "fecha"),
                Map.of("label", "Emisor", "key", "emisor"),
                Map.of("label", "Condicion Venta", "key", "condicion"),
                Map.of("label", "Plazo Credito", "key", "plazo"),
                Map.of("label", "Total", "key", "total"));
    }
}
