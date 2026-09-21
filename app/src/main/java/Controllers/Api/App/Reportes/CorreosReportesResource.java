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

import Models.Correos.ReporteProgramado;
import Services.Correos.ReportesProgramadosService;

/**
 * Read-only listing view of the scheduled email reports
 * ({@code GET /app/reportes/correos}) replacing
 * {@code secured/pages/Correos/Reportes/index.xhtml} (plan T20).
 *
 * <p>Listing parity with
 * {@code ReportesProgramadosController.getFilteredReportesProgramados()} over
 * {@link ReportesProgramadosService#listAll()}: perfil, correos, frecuencia,
 * reportes and the active/inactive chip. The create/edit/send/toggle/delete
 * actions of the legacy page are mutations owned by T24
 * (ReporteProgramadoResource) and are deliberately absent here.</p>
 *
 * <p>Role gate mirrors web.xml {@code /secured/pages/Correos/*}:
 * {@code facturacion} + {@code admin}.</p>
 */
@Path("/app/reportes/correos")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"facturacion", "admin"})
public class CorreosReportesResource {

    private static final String BASE_URL = "/app/reportes/correos";

    @Inject
    @Nonnull
    @Location("pages/reportes/correos")
    Template pagina;

    @Inject
    @Nonnull
    ReportesProgramadosService reportesProgramadosService;

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

        List<ReporteProgramado> rows = filterAndSort(
                orEmpty(reportesProgramadosService.listAll()), sort, dir, q);

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
                ReportePageSupport.columna("Nombre", "perfil"),
                ReportePageSupport.columna("Correos", null),
                ReportePageSupport.columna("Frecuencia", null),
                ReportePageSupport.columna("Reportes", null),
                ReportePageSupport.columna("Ultima Ejecucion", "lastrun"));
    }

    private static @Nonnull List<ReporteProgramado> filterAndSort(
            @Nonnull List<ReporteProgramado> all,
            @Nullable String sort, @Nullable String dir, @Nullable String q) {

        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<ReporteProgramado> result = new ArrayList<>();
        for (ReporteProgramado r : all) {
            if (needle.isEmpty() || matches(r, needle)) {
                result.add(r);
            }
        }

        boolean ascending = !ReportePageSupport.isDescending(dir);
        Comparator<ReporteProgramado> comparator = switch (sort == null ? "" : sort) {
            case "perfil" -> ReportePageSupport.sortBy(ReporteProgramado::getPerfil, ascending);
            case "lastrun" -> ReportePageSupport.sortBy(ReporteProgramado::getLastRun, ascending);
            default -> null;
        };
        if (comparator != null) {
            result.sort(comparator);
        }
        return result;
    }

    /**
     * Same field coverage as the legacy globalFilterFunction: perfil plus the
     * string form of the correos/reportes/frecuencia lists.
     */
    private static boolean matches(@Nonnull ReporteProgramado r, @Nonnull String needle) {
        return contains(r.getPerfil(), needle)
                || contains(String.valueOf(r.getCorreos()), needle)
                || contains(String.valueOf(r.getReportes()), needle)
                || contains(String.valueOf(r.getFrecuencia()), needle);
    }

    private static boolean contains(@Nullable String value, @Nonnull String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static @Nonnull <T> List<T> orEmpty(@Nullable List<T> list) {
        return list != null ? list : new ArrayList<>();
    }
}
