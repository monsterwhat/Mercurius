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

import Models.Users;
import Services.LoginService;

/**
 * Read-only "Gestión de Usuarios" detail listing
 * ({@code GET /app/reportes/usuarios}) replacing
 * {@code secured/pages/Usuarios/Reportes/index.xhtml} (plan T20).
 *
 * <p>Same data the legacy page consumed from {@code UsersController}:
 * activos/inactivos stat cards ({@link LoginService#countActivos()}/
 * {@link LoginService#countInactivos()}) and the user table with the legacy
 * username/groupName filter coverage. The create/edit/toggle actions are
 * mutations owned by T22 (UsersResource) and are deliberately absent.</p>
 *
 * <p>Role gate mirrors web.xml: {@code /secured/pages/Usuarios/*} is
 * admin-only. Password hashes are NEVER emitted — the template only renders
 * username/groupName/status.</p>
 */
@Path("/app/reportes/usuarios")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed("admin")
public class UsuariosReportesResource {

    private static final String BASE_URL = "/app/reportes/usuarios";

    @Inject
    @Nonnull
    @Location("pages/reportes/usuarios")
    Template pagina;

    @Inject
    @Nonnull
    LoginService loginService;

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

        List<Users> rows = filterAndSort(orEmpty(loginService.listAll()), sort, dir, q);

        int current = ReportePageSupport.clampPage(page);
        int pageSize = ReportePageSupport.clampSize(size);
        long total = rows.size();
        int totalPages = ReportePageSupport.totalPages(total, pageSize);

        Long activos = loginService.countActivos();
        Long inactivos = loginService.countInactivos();

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
                "activos", activos == null ? 0L : activos,
                "inactivos", inactivos == null ? 0L : inactivos,
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
                Map.of("label", "Nombre de usuario", "key", "username"),
                Map.of("label", "Permisos", "key", "permisos"),
                Map.of("label", "Activo", "key", "activo"));
    }

    /** Legacy globalFilterFunction coverage: username + groupName. */
    private static @Nonnull List<Users> filterAndSort(
            @Nonnull List<Users> all,
            @Nullable String sort, @Nullable String dir, @Nullable String q) {

        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<Users> result = new ArrayList<>();
        for (Users u : all) {
            if (needle.isEmpty() || matches(u, needle)) {
                result.add(u);
            }
        }

        boolean ascending = !ReportePageSupport.isDescending(dir);
        Comparator<Users> comparator = switch (sort == null ? "" : sort) {
            case "username" -> ReportePageSupport.sortBy(Users::getUsername, ascending);
            case "permisos" -> ReportePageSupport.sortBy(Users::getGroupName, ascending);
            case "activo" -> ReportePageSupport.sortBy(Users::getStatus, ascending);
            default -> null;
        };
        if (comparator != null) {
            result.sort(comparator);
        }
        return result;
    }

    private static boolean matches(@Nonnull Users u, @Nonnull String needle) {
        return contains(u.getUsername(), needle)
                || contains(u.getGroupName(), needle);
    }

    private static boolean contains(@Nullable String value, @Nonnull String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static @Nonnull <T> List<T> orEmpty(@Nullable List<T> list) {
        return list != null ? list : new ArrayList<>();
    }
}
