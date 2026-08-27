package Controllers.Api.App;

import Models.DTO.AlertaDTO;
import Models.DTO.ApiResponse;
import Models.DTO.PagedResponse;
import Models.Registros.Alertas;
import Services.AlertasService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Internal-alert endpoints for the NEW Qute/HTMX app surface (/app world),
 * mirroring the legacy JSF {@code Controllers.AlertasController} (Registros
 * Internos page) as REST.
 *
 * <p>Reads go through {@link AlertasService#findFiltered} with every filter
 * unset — the same query shape the legacy log surfaces use (ordered by
 * timestamp DESC, hard-capped at 500 rows by the service). Pagination and the
 * {@code unreadOnly} facet are applied in memory over that result, because
 * {@code AlertasService} exposes no paginated/unread-only query and this lane
 * must not add service methods.</p>
 *
 * <p>{@code POST /{id}/ack} sets {@code vista = true} (acknowledge/read). The
 * legacy controller only offers {@code toggleVista()}, which flips the flag in
 * both directions; the REST contract here is the explicit "mark as read"
 * direction of that same action, implemented with the existing public surface
 * ({@code find} + entity setter + {@code GService.update}) so the operation is
 * idempotent instead of accidentally un-reading an already-read alert. Like
 * the legacy action, no audit alert is registered for the toggle itself.</p>
 *
 * <p>Role model: {@code admin}, {@code registro} or {@code usuario} — the
 * groups that can reach the legacy Registros pages. The {@code @RolesAllowed}
 * gate is dormant until the form-cookie auth block is enabled in
 * application.properties (see {@link AppAuthResource}).</p>
 *
 * <p>All responses follow the {@link ApiResponse}/{@link PagedResponse}
 * envelope conventions.</p>
 */
@Path("/api/app/alertas")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"admin", "registro", "usuario"})
@Tag(name = "App - Alertas")
public class AlertasResource {

    private static final Logger LOG = Logger.getLogger(AlertasResource.class.getName());

    @Inject
    @Nonnull
    AlertasService alertasService;

    /**
     * Paginated feed of internal alerts, newest first.
     *
     * @param unreadOnly when true, only alerts with vista == false are returned
     */
    @GET
    @Operation(summary = "List internal alerts with pagination (newest first)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Missing admin/registro/usuario role"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response list(
            @QueryParam("page") @DefaultValue("0") @Parameter(description = "Page number (0-based)") int page,
            @QueryParam("size") @DefaultValue("20") @Parameter(description = "Page size (max 100)") int size,
            @QueryParam("unreadOnly") @DefaultValue("false")
                @Parameter(description = "Only alerts not marked as read (vista = false)") boolean unreadOnly) {

        // Clamp to the SuppliersController/UsersResource convention.
        size = Math.min(Math.max(size, 1), 100);
        page = Math.max(page, 0);

        try {
            // Same query the legacy Registros surfaces run with filters unset:
            // ORDER BY timestamp DESC, capped at 500 rows inside the service.
            List<Alertas> filtered = alertasService.findFiltered(null, null, null, null, null);
            if (unreadOnly) {
                filtered = filtered.stream().filter(a -> !a.isVista()).toList();
            }

            long total = filtered.size();
            List<AlertaDTO> data = pageOf(filtered, page, size).stream()
                    .map(AlertasResource::toDTO)
                    .toList();
            return Response.ok(new PagedResponse<>(data, total, page, size)).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error listing alertas", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error listando las alertas"))
                    .build();
        }
    }

    /**
     * Acknowledge (mark as read) one alert: sets {@code vista = true}.
     * Idempotent; 404 when the codigo does not exist.
     */
    @POST
    @Path("/{id}/ack")
    @Transactional
    @Operation(summary = "Mark an alert as read (vista = true)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Alert acknowledged"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Missing admin/registro/usuario role"),
        @APIResponse(responseCode = "404", description = "Alert not found"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response ack(@PathParam("id") @Parameter(description = "Alert code (codigo)") int id) {
        try {
            Alertas alerta = alertasService.find(id);
            if (alerta == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("NOT_FOUND", "No se encontró la alerta: " + id))
                        .build();
            }

            // Explicit direction of the legacy toggleVista() action: always end
            // up read. update() is GService's @Transactional merge.
            alerta.setVista(true);
            alertasService.update(alerta);

            return Response.ok(ApiResponse.ok(toDTO(alerta))).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error acknowledging alerta " + id, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error marcando la alerta como leída"))
                    .build();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Read-side mapping ONLY, following the AlertaDTO contract:
     * tipo doubles as titulo and nivel; timestamp becomes Date via
     * getTimestampAsDate(); the user relation flattens to id + username
     * (null = Sistema, rendered client-side as in the legacy view).
     */
    private static AlertaDTO toDTO(@Nonnull Alertas alerta) {
        return new AlertaDTO(
                alerta.getCodigo(),
                alerta.getTimestampAsDate(),
                alerta.getTipo(),
                alerta.getMensaje(),
                alerta.getUser() != null ? alerta.getUser().getId() : null,
                alerta.getUser() != null ? alerta.getUser().getUsername() : null,
                alerta.getSource() != null ? alerta.getSource() : "",
                alerta.getTipo());
    }

    /** In-memory window over a service result (findFiltered caps at 500). */
    private static <T> List<T> pageOf(@Nonnull List<T> source, int page, int size) {
        int from = Math.min(page * size, source.size());
        int to = Math.min(from + size, source.size());
        return source.subList(from, to);
    }

    // ═══ Registros Internos page/fragment surface (T30) ══════════════════════

    /** Page/fragment endpoint serving templates/pages/registros/alertas.html. */
    private static final String PAGINA_URL = "/api/app/alertas/pagina";

    /** Legacy tables render timestamps as yyyy-MM-dd HH:mm:ss. */
    private static final DateTimeFormatter FECHA_HORA =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Inject
    @Location("pages/registros/alertas")
    Template paginaAlertas;

    /**
     * Renders templates/pages/registros/alertas.html: the Registros Internos
     * page. With the {@code HX-Request} header renders ONLY the {@code tabla}
     * fragment so pager/sort/filter swaps target {@code #tabla-alertas} in
     * place; otherwise the full page through layout.html.
     *
     * <p>Server contract (docs/ui-kit.md §3.1): page/size/sort/dir come from
     * the query string (defaults 1/20/null/asc), totalPages is computed
     * server-side and the {@code unreadOnly} facet re-emits through the
     * preserved params map. A page beyond the last yields the empty state,
     * never an error.</p>
     */
    @GET
    @Path("/pagina")
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Registros Internos page (full page, or table fragment under HX-Request)")
    public Response pagina(@Context @Nonnull HttpHeaders headers,
                           @QueryParam("page") @DefaultValue("1") int page,
                           @QueryParam("size") @DefaultValue("20") int size,
                           @QueryParam("sort") @Nullable String sort,
                           @QueryParam("dir") @DefaultValue("asc") String dir,
                           @QueryParam("unreadOnly") @Nullable String unreadOnly) {
        boolean hx = headers.getHeaderString("HX-Request") != null;
        try {
            Map<String, Object> model = modeloTabla(page, size, sort, dir, unreadOnly, null, null);
            TemplateInstance vista = hx
                    ? paginaAlertas.getFragment("tabla").data(model)
                    : paginaAlertas.data(model);
            return Response.ok(vista)
                    .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                    .build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error renderizando la página de registros internos", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR",
                            "No se pudo cargar la página de registros internos"))
                    .build();
        }
    }

    /**
     * HTMX channel of {@link #ack(int)}: same POST /{id}/ack path, selected
     * by the urlencoded content type htmx sends when the row button carries
     * hx-vals, answering with the REFRESHED table fragment instead of JSON —
     * legacy parity with the p:commandLink that updated the whole dataTable,
     * so the swapped rows show the read style AND the unreadOnly facet and
     * pagination stay consistent. Validation problems ride an out-of-band
     * error toast on a 200 fragment (ui-kit.md Pattern A) because htmx does
     * not swap error statuses by default.
     */
    @POST
    @Path("/{id}/ack")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    @Operation(summary = "Mark an alert as read from the HTMX table (refreshed fragment)", hidden = true)
    public Response ackFormulario(@PathParam("id") int id,
                                  @QueryParam("page") @DefaultValue("1") int page,
                                  @QueryParam("size") @DefaultValue("20") int size,
                                  @QueryParam("sort") @Nullable String sort,
                                  @QueryParam("dir") @DefaultValue("asc") String dir,
                                  @QueryParam("unreadOnly") @Nullable String unreadOnly) {
        String severidad;
        String mensaje;
        try {
            Alertas alerta = alertasService.find(id);
            if (alerta == null) {
                severidad = "error";
                mensaje = "No se encontró la alerta: " + id;
            } else {
                alerta.setVista(true);
                alertasService.update(alerta);
                severidad = "success";
                mensaje = "Alerta marcada como leída";
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error marcando la alerta como leída (HTMX): " + id, e);
            severidad = "error";
            mensaje = "Error marcando la alerta como leída";
        }
        try {
            Map<String, Object> model = modeloTabla(page, size, sort, dir, unreadOnly, severidad, mensaje);
            return Response.ok(paginaAlertas.getFragment("tabla").data(model))
                    .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                    .build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error renderizando el fragmento de registros internos", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR",
                            "No se pudo actualizar la tabla de registros internos"))
                    .build();
        }
    }

    // ── T30 view-half helpers ────────────────────────────────────────────────

    /** True for the truthy urlencoded spellings of the unreadOnly facet. */
    private static boolean parseUnreadOnly(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String valor = raw.trim().toLowerCase(Locale.ROOT);
        return valor.equals("true") || valor.equals("on") || valor.equals("1");
    }

    /**
     * Full model for pages/registros/alertas.html (both render modes share
     * it): filter → sort → slice over the capped findFiltered result plus the
     * kit data-table maps. Reserved keys (page/size/sort/dir) stay out of
     * filtros — the kit emits them itself.
     */
    private Map<String, Object> modeloTabla(int page, int size, @Nullable String sort,
                                            @Nullable String dir, @Nullable String unreadOnly,
                                            @Nullable String toastSeveridad,
                                            @Nullable String toastMessage) {
        boolean soloNoLeidas = parseUnreadOnly(unreadOnly);
        int tamano = Math.min(Math.max(size, 1), 100);
        int paginaActual = Math.max(page, 1);
        String dirSegura = "desc".equalsIgnoreCase(dir) ? "desc" : "asc";
        String sortKey = (sort != null && !sort.isBlank()) ? sort.trim() : null;

        List<Alertas> filtradas = new ArrayList<>(
                alertasService.findFiltered(null, null, null, null, null));
        if (soloNoLeidas) {
            filtradas.removeIf(alerta -> alerta.isVista());
        }
        ordenar(filtradas, sortKey, dirSegura);

        long total = filtradas.size();
        int totalPaginas = (int) Math.max(1L, (total + tamano - 1L) / tamano);
        long desdeIndice = (paginaActual - 1L) * (long) tamano;
        int from = (int) Math.min(desdeIndice, total);
        int to = (int) Math.min(desdeIndice + tamano, total);

        String estadoQuery = estadoUrl(paginaActual, tamano, sortKey, dirSegura, soloNoLeidas);
        List<Map<String, Object>> filas = new ArrayList<>();
        for (Alertas alerta : filtradas.subList(from, to)) {
            filas.add(fila(alerta, estadoQuery));
        }

        Map<String, Object> filtros = new LinkedHashMap<>();
        if (soloNoLeidas) {
            filtros.put("unreadOnly", "true");
        }

        Map<String, Object> tabla = new LinkedHashMap<>();
        tabla.put("id", "tabla-alertas");
        tabla.put("baseUrl", PAGINA_URL);
        tabla.put("columnas", columnas());
        tabla.put("filas", filas);
        tabla.put("sortKey", sortKey);
        tabla.put("sortDir", dirSegura);
        tabla.put("page", paginaActual);
        tabla.put("size", tamano);
        tabla.put("total", total);
        tabla.put("totalPages", totalPaginas);
        tabla.put("paginas", ventanaPaginas(paginaActual, totalPaginas));
        tabla.put("filtros", filtros);

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("titulo", "Registros Internos");
        model.put("baseUrl", PAGINA_URL);
        model.put("modelo", tabla);
        model.put("unreadOnly", soloNoLeidas);
        model.put("toastSeveridad", toastSeveridad);
        model.put("toastMessage", toastMessage);
        return model;
    }

    /** Query string replaying the current table state onto each ack URL. */
    private static String estadoUrl(int page, int size, @Nullable String sortKey,
                                    @Nonnull String dir, boolean soloNoLeidas) {
        StringBuilder qs = new StringBuilder();
        qs.append("?page=").append(page).append("&size=").append(size);
        if (sortKey != null) {
            qs.append("&sort=").append(sortKey).append("&dir=").append(dir);
        }
        if (soloNoLeidas) {
            qs.append("&unreadOnly=true");
        }
        return qs.toString();
    }

    /** Display row for the Registros Internos table (+ per-row ack URL). */
    private static Map<String, Object> fila(@Nonnull Alertas alerta, @Nonnull String estadoQuery) {
        Map<String, Object> fila = new LinkedHashMap<>();
        fila.put("codigo", alerta.getCodigo());
        fila.put("fecha", alerta.getTimestamp() != null
                ? FECHA_HORA.format(alerta.getTimestamp()) : "-");
        fila.put("tipo", alerta.getTipo() != null ? alerta.getTipo() : "-");
        fila.put("nivelColor", colorNivel(alerta.getTipo()));
        fila.put("mensaje", alerta.getMensaje() != null ? alerta.getMensaje() : "");
        fila.put("usuario", usernameDe(alerta));
        fila.put("origen", alerta.getSource() != null ? alerta.getSource() : "");
        fila.put("vista", alerta.isVista());
        fila.put("ackUrl", "/api/app/alertas/" + alerta.getCodigo() + "/ack" + estadoQuery);
        return fila;
    }

    /** Legacy ternary parity: no user renders as "Sistema". */
    private static String usernameDe(@Nonnull Alertas alerta) {
        return alerta.getUser() != null && alerta.getUser().getUsername() != null
                ? alerta.getUser().getUsername() : "Sistema";
    }

    /**
     * Severity badge color using the _kit/toast-item vocabulary
     * (docs/ui-kit.md §2.6): error→is-danger, warn/warning→is-warning,
     * success→is-success, anything else→is-info. The entity's tipo doubles as
     * the log level, so the badge rides the Tipo column.
     */
    private static String colorNivel(@Nullable String tipo) {
        String nivel = tipo == null ? "" : tipo.trim().toLowerCase(Locale.ROOT);
        return switch (nivel) {
            case "error" -> "is-danger";
            case "warn", "warning" -> "is-warning";
            case "success" -> "is-success";
            default -> "is-info";
        };
    }

    /** Columns mirror the legacy p:column set; null key ⇒ non-sortable. */
    private static List<Map<String, Object>> columnas() {
        return List.of(
                Map.of("label", "Fecha/Hora", "key", "fecha"),
                Map.of("label", "Tipo", "key", "tipo"),
                Map.of("label", "Mensaje"),
                Map.of("label", "Usuario", "key", "usuario"),
                Map.of("label", "Origen", "key", "origen"),
                Map.of("label", "Estado"));
    }

    /**
     * In-memory sort over the service result (service default order stays
     * timestamp DESC when no sort key is requested).
     */
    private static void ordenar(@Nonnull List<Alertas> filas, @Nullable String sort,
                                @Nonnull String dir) {
        if (filas.isEmpty() || sort == null) {
            return;
        }
        Comparator<Alertas> cmp = switch (sort) {
            case "fecha" -> Comparator.comparing(Alertas::getTimestamp,
                    Comparator.nullsLast(Comparator.<LocalDateTime>naturalOrder()));
            case "tipo" -> Comparator.comparing(Alertas::getTipo,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "usuario" -> Comparator.comparing(AlertasResource::usernameDe,
                    String.CASE_INSENSITIVE_ORDER);
            case "origen" -> Comparator.comparing(Alertas::getSource,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            default -> null;
        };
        if (cmp != null) {
            filas.sort("desc".equals(dir) ? cmp.reversed() : cmp);
        }
    }

    /** Up-to-5-pages window centered on the current page (Qute has no division). */
    private static List<Integer> ventanaPaginas(int page, int totalPages) {
        if (totalPages <= 1) {
            return List.of(1);
        }
        List<Integer> paginas = new ArrayList<>();
        int desde = Math.max(1, page - 2);
        int hasta = Math.min(totalPages, page + 2);
        for (int p = desde; p <= hasta; p++) {
            paginas.add(p);
        }
        return paginas;
    }
}
