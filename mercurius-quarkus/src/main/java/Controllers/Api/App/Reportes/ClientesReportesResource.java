package Controllers.Api.App.Reportes;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
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

import Models.Clients;
import Services.ClientService;

/**
 * Read-only "Reportes de Clientes" page ({@code GET /app/reportes/clientes})
 * replacing {@code secured/pages/Clientes/Reportes/index.xhtml} (plan T20).
 *
 * <p>Same aggregates the legacy page consumed through
 * {@code ClientsController}/{@code ReportesClientesController}, taken
 * directly from {@link ClientService}: activos/inactivos/total counts (same
 * stream predicates as {@code clientsActivosCount()}/
 * {@code clientsInactivosCount()}) plus the full client listing with the
 * legacy filter coverage. The date filters of the legacy form were never
 * applied by {@code ReportesClientesController.getClientesFiltrados()} (only
 * its text filter was), so only that effective behavior is ported.</p>
 *
 * <p>Role gate mirrors web.xml: Clientes/Reportes sat under the generic
 * {@code /secured/*} any-authenticated constraint. Mutations: none.</p>
 */
@Path("/app/reportes/clientes")
@Produces(MediaType.TEXT_HTML)
public class ClientesReportesResource {

    private static final String BASE_URL = "/app/reportes/clientes";

    @Inject
    @Nonnull
    @Location("pages/reportes/clientes")
    Template pagina;

    @Inject
    @Nonnull
    ClientService clientService;

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

        List<Clients> rows = filterAndSort(orEmpty(clientService.listAll()), sort, dir, q);

        int current = ReportePageSupport.clampPage(page);
        int pageSize = ReportePageSupport.clampSize(size);
        long total = rows.size();
        int totalPages = ReportePageSupport.totalPages(total, pageSize);

        long activos = clientService.listAll().stream()
                .filter(c -> c.getStatus() != null && c.getStatus()).count();
        long inactivos = clientService.listAll().stream()
                .filter(c -> c.getStatus() == null || !c.getStatus()).count();

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
                "activos", activos,
                "inactivos", inactivos,
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
                Map.of("label", "Nombre", "key", "nombre"),
                Map.of("label", "Dirección", "key", "direccion"),
                Map.of("label", "Correo", "key", "email"),
                Map.of("label", "Cédula", "key", "cedula"),
                Map.of("label", "Teléfono", "key", "telefono"),
                Map.of("label", "Tributario", "key", "tributario"),
                Map.of("label", "Última Compra", "key", "ultimacompra"),
                Map.of("label", "Puntos", "key", "puntos"));
    }

    /**
     * Legacy ReportesClientesController.getClientesFiltrados(): name, email
     * and idNumber contains-match.
     */
    private static @Nonnull List<Clients> filterAndSort(
            @Nonnull List<Clients> all,
            @Nullable String sort, @Nullable String dir, @Nullable String q) {

        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<Clients> result = new ArrayList<>();
        for (Clients c : all) {
            if (needle.isEmpty() || matches(c, needle)) {
                result.add(c);
            }
        }

        boolean ascending = !ReportePageSupport.isDescending(dir);
        Comparator<Clients> comparator = switch (sort == null ? "" : sort) {
            case "nombre" -> ReportePageSupport.sortBy(Clients::getName, ascending);
            case "direccion" -> ReportePageSupport.sortBy(Clients::getAddress, ascending);
            case "email" -> ReportePageSupport.sortBy(Clients::getEmail, ascending);
            case "cedula" -> ReportePageSupport.sortBy(Clients::getIdNumber, ascending);
            case "telefono" -> ReportePageSupport.sortBy(Clients::getPhoneNumber, ascending);
            case "ultimacompra" -> ReportePageSupport.sortBy(Clients::getLastPurchaseDate, ascending);
            case "puntos" -> ReportePageSupport.sortBy(Clients::getPuntosAcumulados, ascending);
            default -> null;
        };
        if (comparator != null) {
            result.sort(comparator);
        }
        return result;
    }

    private static boolean matches(@Nonnull Clients c, @Nonnull String needle) {
        return contains(c.getName(), needle)
                || contains(c.getEmail(), needle)
                || contains(c.getIdNumber(), needle);
    }

    private static boolean contains(@Nullable String value, @Nonnull String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static @Nonnull <T> List<T> orEmpty(@Nullable List<T> list) {
        return list != null ? list : new ArrayList<>();
    }
}
