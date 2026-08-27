package Controllers.Api.App.Reportes;

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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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
 * {@code filteredFacturasVencidas}, whose backing queries are
 * {@link ComprobantesRecibidosService#listPendientes()} /
 * {@link ComprobantesRecibidosService#listVencidas()}. Those service calls are
 * invoked directly here so FacturasController (owned by the T27/T36 lanes)
 * stays untouched while keeping query parity — including the vencido rule:
 * unpaid received invoices whose {@code fechaEmision + plazoCredito} days mark
 * is today or past.</p>
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
        return render(pendientesPage, BASE_PENDIENTES, "Pendientes",
                orEmpty(recibidosService.listPendientes()), page, size, sort, dir, q);
    }

    @GET
    @Path("/vencidos")
    public Response vencidos(
            @QueryParam("page") @Nullable Integer page,
            @QueryParam("size") @Nullable Integer size,
            @QueryParam("sort") @Nullable String sort,
            @QueryParam("dir") @DefaultValue("asc") @Nullable String dir,
            @QueryParam("q") @Nullable String q) {
        return render(vencidosPage, BASE_VENCIDOS, "Vencidos",
                recibidosService.listVencidas(), page, size, sort, dir, q);
    }

    private Response render(
            @Nonnull Template template, @Nonnull String baseUrl, @Nonnull String titulo,
            @Nonnull List<ComprobantesRecibidos> all,
            @Nullable Integer page, @Nullable Integer size,
            @Nullable String sort, @Nullable String dir, @Nullable String q) {

        List<ComprobantesRecibidos> rows = filterAndSort(all, sort, dir, q);

        int current = ReportePageSupport.clampPage(page);
        int pageSize = ReportePageSupport.clampSize(size);
        long total = rows.size();
        int totalPages = ReportePageSupport.totalPages(total, pageSize);

        Map<String, Object> model = ReportePageSupport.model(
                "titulo", titulo,
                "columnas", columnas(),
                "rows", ReportePageSupport.pageOf(rows, current, pageSize),
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
                Map.of("label", "Numero Consecutivo", "key", "consecutivo"),
                Map.of("label", "Fecha", "key", "fecha"),
                Map.of("label", "Emisor", "key", "emisor"),
                Map.of("label", "Condicion Venta", "key", "condicion"),
                Map.of("label", "Plazo Credito", "key", "plazo"),
                Map.of("label", "Total", "key", "total"));
    }

    private static @Nonnull List<ComprobantesRecibidos> filterAndSort(
            @Nonnull List<ComprobantesRecibidos> all,
            @Nullable String sort, @Nullable String dir, @Nullable String q) {

        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<ComprobantesRecibidos> result = new ArrayList<>();
        for (ComprobantesRecibidos f : all) {
            if (needle.isEmpty() || matches(f, needle)) {
                result.add(f);
            }
        }

        boolean ascending = !ReportePageSupport.isDescending(dir);
        Comparator<ComprobantesRecibidos> comparator = switch (sort == null ? "" : sort) {
            case "consecutivo" -> ReportePageSupport.sortBy(f -> encabezado(f) == null ? null : encabezado(f).getNumeroConsecutivo(), ascending);
            case "fecha" -> ReportePageSupport.sortBy(f -> encabezado(f) == null ? null : encabezado(f).getFechaEmision(), ascending);
            case "emisor" -> ReportePageSupport.sortBy(RecibosReportesResource::emisorNombre, ascending);
            case "condicion" -> ReportePageSupport.sortBy(f -> encabezado(f) == null ? null : encabezado(f).getCondicionVenta(), ascending);
            case "plazo" -> ReportePageSupport.sortBy(f -> encabezado(f) == null ? null : encabezado(f).getPlazoCredito(), ascending);
            case "total" -> ReportePageSupport.sortBy(f -> f.getResumen() == null ? null : f.getResumen().getTotalComprobante(), ascending);
            default -> null;
        };
        if (comparator != null) {
            result.sort(comparator);
        }
        return result;
    }

    private static boolean matches(@Nonnull ComprobantesRecibidos f, @Nonnull String needle) {
        return contains(emisorNombre(f), needle)
                || contains(encabezado(f) == null ? null : encabezado(f).getNumeroConsecutivo(), needle)
                || contains(encabezado(f) == null ? null : encabezado(f).getCondicionVenta(), needle)
                || contains(encabezado(f) == null || encabezado(f).getFechaEmision() == null
                        ? null : encabezado(f).getFechaEmision().toString(), needle);
    }

    private static @Nullable Models.Encabezado.Encabezado encabezado(@Nonnull ComprobantesRecibidos f) {
        return f.getEncabezado();
    }

    private static @Nullable String emisorNombre(@Nonnull ComprobantesRecibidos f) {
        return encabezado(f) != null && encabezado(f).getEmisor() != null
                ? encabezado(f).getEmisor().getNombre() : null;
    }

    private static boolean contains(@Nullable String value, @Nonnull String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static @Nonnull <T> List<T> orEmpty(@Nullable List<T> list) {
        return list != null ? list : new ArrayList<>();
    }
}
