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

import Models.ComprobantesEmitidos;
import Services.ComprobantesEmitidosService;

/**
 * Read-only "Facturas Emitidas" report page ({@code GET /app/reportes/facturas})
 * replacing {@code secured/pages/Facturas/Reportes/index.xhtml} (plan T20).
 *
 * <p>Serves the same listing as the legacy
 * {@code ComprobantesEmitidosController.getFilteredComprobantesEmitidos()}
 * over {@link ComprobantesEmitidosService#listAll()}, but ONLY the read-only
 * view: the legacy toggle/reenviar/selection actions are mutations owned by
 * T27 and are deliberately absent here.</p>
 *
 * <p>Role gate mirrors web.xml: {@code /secured/pages/Facturas/*} allows
 * {@code facturacion} + {@code admin}. HTMX requests receive only the
 * {@code {#fragment id=tabla}} section of the template.</p>
 */
@Path("/app/reportes/facturas")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"facturacion", "admin"})
public class FacturasReportesResource {

    private static final String BASE_URL = "/app/reportes/facturas";

    @Inject
    @Nonnull
    @Location("pages/reportes/facturas")
    Template pagina;

    @Inject
    @Nonnull
    ComprobantesEmitidosService comprobantesService;

    @Inject
    @Nonnull
    HttpHeaders httpHeaders;

    @GET
    public Response render(
            @QueryParam("page") @Nullable Integer page,
            @QueryParam("size") @Nullable Integer size,
            @QueryParam("sort") @Nullable String sort,
            @QueryParam("dir") @DefaultValue("asc") @Nullable String dir,
            @QueryParam("q") @Nullable String q) {

        List<ComprobantesEmitidos> rows = filterAndSort(orEmpty(comprobantesService.listAll()), sort, dir, q);

        int current = ReportePageSupport.clampPage(page);
        int pageSize = ReportePageSupport.clampSize(size);
        long total = rows.size();
        int totalPages = ReportePageSupport.totalPages(total, pageSize);

        Map<String, Object> model = ReportePageSupport.model(
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
                "baseUrl", BASE_URL);

        TemplateInstance instance = ReportePageSupport.isHxRequest(httpHeaders)
                ? pagina.getFragment("tabla").instance()
                : pagina.instance();
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
                Map.of("label", "Estado Hacienda", "key", "estado"),
                Map.of("label", "Total", "key", "total"));
    }

    /**
     * Mirrors the legacy globalFilterFunction field coverage that is safe to
     * evaluate on fetched entities: consecutivo, condicionVenta, emisor name,
     * emision date and hacienda estado.
     */
    private static @Nonnull List<ComprobantesEmitidos> filterAndSort(
            @Nonnull List<ComprobantesEmitidos> all,
            @Nullable String sort, @Nullable String dir, @Nullable String q) {

        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<ComprobantesEmitidos> result = new ArrayList<>();
        for (ComprobantesEmitidos f : all) {
            if (needle.isEmpty() || matches(f, needle)) {
                result.add(f);
            }
        }

        boolean ascending = !ReportePageSupport.isDescending(dir);
        Comparator<ComprobantesEmitidos> comparator = switch (sort == null ? "" : sort) {
            case "consecutivo" -> ReportePageSupport.sortBy(f -> encabezado(f) == null ? null : encabezado(f).getNumeroConsecutivo(), ascending);
            case "fecha" -> ReportePageSupport.sortBy(f -> encabezado(f) == null ? null : encabezado(f).getFechaEmision(), ascending);
            case "emisor" -> ReportePageSupport.sortBy(ComprobantesEmitidos::getUser, ascending);
            case "condicion" -> ReportePageSupport.sortBy(f -> encabezado(f) == null ? null : encabezado(f).getCondicionVenta(), ascending);
            case "estado" -> ReportePageSupport.sortBy(ComprobantesEmitidos::getHaciendaEstado, ascending);
            case "total" -> ReportePageSupport.sortBy(f -> f.getResumen() == null ? null : f.getResumen().getTotalComprobante(), ascending);
            default -> null;
        };
        if (comparator != null) {
            result.sort(comparator);
        }
        return result;
    }

    private static boolean matches(@Nonnull ComprobantesEmitidos f, @Nonnull String needle) {
        String consecutivo = encabezado(f) == null ? null : encabezado(f).getNumeroConsecutivo();
        String condicion = encabezado(f) == null ? null : encabezado(f).getCondicionVenta();
        String emisorNombre = encabezado(f) != null && encabezado(f).getEmisor() != null
                ? encabezado(f).getEmisor().getNombre() : null;
        String fecha = encabezado(f) == null || encabezado(f).getFechaEmision() == null
                ? null : encabezado(f).getFechaEmision().toString();
        return contains(consecutivo, needle) || contains(condicion, needle)
                || contains(emisorNombre, needle) || contains(fecha, needle)
                || contains(f.getHaciendaEstado(), needle) || contains(f.getUser(), needle);
    }

    private static boolean contains(@Nullable String value, @Nonnull String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static @Nullable Models.Encabezado.Encabezado encabezado(@Nonnull ComprobantesEmitidos f) {
        return f.getEncabezado();
    }

    private static @Nonnull List<ComprobantesEmitidos> orEmpty(@Nullable List<ComprobantesEmitidos> list) {
        return list != null ? list : new ArrayList<>();
    }
}
