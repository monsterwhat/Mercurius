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
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import Models.AppSettings;
import Models.Clients;
import Models.PuntosTransaccion;
import Services.AppSettingsService;
import Services.ClientService;
import Services.LoyaltyService;

/**
 * Read-only "Programa de Lealtad - Reportes" page
 * ({@code GET /app/reportes/loyalty}) replacing
 * {@code secured/pages/Loyalty/Reportes/index.xhtml} (plan T20).
 *
 * <p>Same data as the legacy {@code loyaltyController} views, straight over
 * the services: top-customers table ({@link LoyaltyService#getTopLoyaltyCustomers(int)}
 * with the legacy fixed limit of 10), all-clients table
 * ({@link ClientService#listAll()} + legacy global-filter field coverage) and
 * a per-client points-history dialog fragment
 * ({@link LoyaltyService#getCustomerPointsHistory(Clients)}). The header stats
 * mirror the legacy cards (top size, cashback %, inactivity months).</p>
 *
 * <p>The legacy "Procesar Expiración" button is a MUTATION
 * ({@code checkAndExpireInactivePoints}) and is NOT ported here — T25 owns
 * that flow. Role gate mirrors web.xml: Loyalty/Reportes sat under the
 * generic {@code /secured/*} any-authenticated constraint, so no extra
 * {@code @RolesAllowed} beyond the /app/* authenticated policy.</p>
 */
@Path("/app/reportes/loyalty")
@Produces(MediaType.TEXT_HTML)
public class LoyaltyReportesResource {

    private static final String BASE_URL = "/app/reportes/loyalty";
    /** Legacy loadTopCustomers() used a fixed limit of 10. */
    private static final int TOP_LIMIT = 10;

    @Inject
    @Nonnull
    @Location("pages/reportes/loyalty")
    Template pagina;

    @Inject
    @Nonnull
    @Location("pages/reportes/_historial-puntos")
    Template historialFragment;

    @Inject
    @Nonnull
    LoyaltyService loyaltyService;

    @Inject
    @Nonnull
    ClientService clientService;

    @Inject
    @Nonnull
    AppSettingsService appSettingsService;

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

        List<Clients> todos = filterAndSort(orEmpty(clientService.listAll()), sort, dir, q);

        int current = ReportePageSupport.clampPage(page);
        int pageSize = ReportePageSupport.clampSize(size);
        long total = todos.size();
        int totalPages = ReportePageSupport.totalPages(total, pageSize);

        AppSettings settings = appSettingsService.returnCurrent();

        Map<String, Object> model = ReportePageSupport.model(
                "topClientes", orEmpty(loyaltyService.getTopLoyaltyCustomers(TOP_LIMIT)),
                "columnas", columnas(),
                "rows", ReportePageSupport.pageOf(todos, current, pageSize),
                "total", total,
                "totalPages", totalPages,
                "pages", ReportePageSupport.pageWindow(current, totalPages),
                "page", current,
                "size", pageSize,
                "sortKey", sort == null ? "" : sort,
                "sortDir", ReportePageSupport.isDescending(dir) ? "desc" : "asc",
                "filtros", ReportePageSupport.params("q", q),
                "cashbackPercentage", settings == null || settings.getCashbackPercentage() == null
                        ? BigDecimal.ZERO : settings.getCashbackPercentage(),
                "puntosInactivityMonths", settings == null || settings.getPuntosInactivityMonths() == null
                        ? 0 : settings.getPuntosInactivityMonths(),
                "baseUrl", BASE_URL);

        TemplateInstance instance = ReportePageSupport.isHxRequest(httpHeaders)
                ? pagina.getFragment("tabla").instance()
                : pagina.instance();
        model.forEach(instance::data);
        return Response.ok(instance.render())
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                .build();
    }

    /**
     * Points-history fragment for one client (dialog body of the legacy
     * pointsHistoryDialog), newest first per the service contract.
     */
    @GET
    @Path("/{codigo}/historial")
    public Response historial(@PathParam("codigo") int codigo) {
        Clients cliente = clientService.find(codigo);
        List<PuntosTransaccion> historial = cliente == null
                ? List.of()
                : orEmpty(loyaltyService.getCustomerPointsHistory(cliente));

        // Pre-formatted rows: PuntosTransaccion.fechaCreacion is java.util.Date,
        // which Qute's .format() cannot render directly.
        java.time.format.DateTimeFormatter fmt =
                java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
        List<Map<String, Object>> filas = new ArrayList<>();
        for (PuntosTransaccion t : historial) {
            Map<String, Object> fila = new java.util.LinkedHashMap<>();
            fila.put("id", t.getId());
            fila.put("fechaCreacion", t.getFechaCreacion() == null ? null
                    : fmt.format(new java.util.Date(t.getFechaCreacion().getTime())
                            .toInstant().atZone(java.time.ZoneId.systemDefault())));
            fila.put("tipoTransaccion", t.getTipoTransaccion());
            fila.put("descripcion", t.getDescripcion());
            fila.put("puntos", t.getPuntos());
            fila.put("saldoPuntos", t.getSaldoPuntos());
            filas.add(fila);
        }

        String html = historialFragment
                .data("cliente", cliente)
                .data("historial", filas)
                .render();
        return Response.ok(html)
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                .build();
    }

    private static @Nonnull List<Map<String, Object>> columnas() {
        return List.of(
                Map.of("label", "Nombre", "key", "nombre"),
                Map.of("label", "Correo", "key", "email"),
                Map.of("label", "Teléfono", "key", "telefono"),
                Map.of("label", "Puntos", "key", "puntos"),
                Map.of("label", "Última Compra", "key", "ultimacompra"),
                ReportePageSupport.columna("Historial", null));
    }

    /** Legacy globalFilterFunction coverage: name, email, idNumber, puntos. */
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
            case "email" -> ReportePageSupport.sortBy(Clients::getEmail, ascending);
            case "telefono" -> ReportePageSupport.sortBy(Clients::getPhoneNumber, ascending);
            case "puntos" -> ReportePageSupport.sortBy(Clients::getPuntosAcumulados, ascending);
            case "ultimacompra" -> ReportePageSupport.sortBy(Clients::getLastPurchaseDate, ascending);
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
                || contains(c.getIdNumber(), needle)
                || contains(c.getPuntosAcumulados() == null ? null : c.getPuntosAcumulados().toString(), needle);
    }

    private static boolean contains(@Nullable String value, @Nonnull String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static @Nonnull <T> List<T> orEmpty(@Nullable List<T> list) {
        return list != null ? list : new ArrayList<>();
    }
}
