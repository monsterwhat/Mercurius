package Controllers.Api.App;

import Models.DTO.ApiResponse;
import Models.DTO.LogActividadDTO;
import Models.DTO.PagedResponse;
import Models.Registros.Alertas;
import Models.Users;
import Services.AlertasService;
import Services.LoginService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
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
 * Audit-log endpoints for the NEW Qute/HTMX app surface (/app world),
 * mirroring the query surface of the legacy JSF
 * {@code Controllers.LogActividadController} (Log de Actividades page) as
 * REST.
 *
 * <p>The legacy page has no dedicated log entity: it queries
 * {@code Models.Registros.Alertas} through
 * {@link AlertasService#findFiltered} with date-range, user, tipo and source
 * filters. This resource exposes exactly that filter set — {@code fechaDesde},
 * {@code fechaHasta}, {@code usuario} (resolved by username through
 * {@link LoginService#findByUsername}, the same lookup the login flow uses),
 * {@code tipo} and {@code source} — and paginates in memory over the service
 * result, which is already ordered timestamp DESC and capped at 500 rows.
 * No service methods were added.</p>
 *
 * <p>Authorization: the legacy Log de Actividades page declares no role gate,
 * so this resource adds none either; it relies on the implicit
 * "authenticated user" policy of the {@code /api/app/*} surface (see
 * {@link AppAuthResource}).</p>
 *
 * <p>All responses follow the {@link ApiResponse}/{@link PagedResponse}
 * envelope conventions.</p>
 */
@Path("/api/app/logs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "App - Logs")
public class LogActividadResource {

    private static final Logger LOG = Logger.getLogger(LogActividadResource.class.getName());

    @Inject
    @Nonnull
    AlertasService alertasService;

    @Inject
    @Nonnull
    LoginService loginService;

    /**
     * Paginated audit log with the legacy filter surface.
     *
     * <p>Date parameters accept ISO-8601 ({@code yyyy-MM-dd} or
     * {@code yyyy-MM-dd'T'HH:mm[:ss]}). A date-only {@code fechaDesde} is read
     * as the start of that day and a date-only {@code fechaHasta} as the end
     * of that day (inclusive), so day-granularity filtering behaves as users
     * expect while full timestamps pass through untouched.</p>
     */
    @GET
    @Operation(summary = "List audit-log entries with pagination and legacy filters")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "400", description = "Invalid date format"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "404", description = "Unknown usuario filter"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response list(
            @QueryParam("page") @DefaultValue("0") @Parameter(description = "Page number (0-based)") int page,
            @QueryParam("size") @DefaultValue("20") @Parameter(description = "Page size (max 100)") int size,
            @QueryParam("fechaDesde") @Nullable
                @Parameter(description = "From date (ISO-8601 date or datetime)") String fechaDesde,
            @QueryParam("fechaHasta") @Nullable
                @Parameter(description = "To date, inclusive (ISO-8601 date or datetime)") String fechaHasta,
            @QueryParam("usuario") @Nullable
                @Parameter(description = "Filter by exact username") String usuario,
            @QueryParam("tipo") @Nullable
                @Parameter(description = "Filter by exact tipo (e.g. Error, Info)") String tipo,
            @QueryParam("source") @Nullable
                @Parameter(description = "Filter by source substring (case-insensitive)") String source) {

        // Clamp to the SuppliersController/UsersResource convention.
        size = Math.min(Math.max(size, 1), 100);
        page = Math.max(page, 0);

        try {
            Date desde = null;
            if (fechaDesde != null && !fechaDesde.isBlank()) {
                desde = parseFecha(fechaDesde, false);
                if (desde == null) {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(ApiResponse.error("VALIDATION_ERROR",
                                    "Formato de fecha inválido para fechaDesde. Use ISO-8601 (yyyy-MM-dd o yyyy-MM-dd'T'HH:mm)"))
                            .build();
                }
            }
            Date hasta = null;
            if (fechaHasta != null && !fechaHasta.isBlank()) {
                hasta = parseFecha(fechaHasta, true);
                if (hasta == null) {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(ApiResponse.error("VALIDATION_ERROR",
                                    "Formato de fecha inválido para fechaHasta. Use ISO-8601 (yyyy-MM-dd o yyyy-MM-dd'T'HH:mm)"))
                            .build();
                }
            }

            // Legacy selectedUser comes from a dropdown of Users rows; over REST
            // the caller names the user, resolved with the same lookup used at
            // login time. An unknown username yields 404 rather than silently
            // returning an unfiltered list.
            Users selectedUser = null;
            if (usuario != null && !usuario.isBlank()) {
                selectedUser = loginService.findByUsername(usuario.trim());
                if (selectedUser == null) {
                    return Response.status(Response.Status.NOT_FOUND)
                            .entity(ApiResponse.error("NOT_FOUND", "No se encontró el usuario: " + usuario))
                            .build();
                }
            }

            List<Alertas> registros = alertasService.findFiltered(
                    desde, hasta, selectedUser,
                    (tipo != null && !tipo.isBlank()) ? tipo : null,
                    (source != null && !source.isBlank()) ? source : null);

            long total = registros.size();
            List<LogActividadDTO> data = pageOf(registros, page, size).stream()
                    .map(LogActividadResource::toDTO)
                    .toList();
            return Response.ok(new PagedResponse<>(data, total, page, size)).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error listing logs de actividad", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error listando el log de actividades"))
                    .build();
        }
    }

    // ═══ Log de Actividades page/fragment surface (T30) ══════════════════════

    /** Page/fragment endpoint serving templates/pages/registros/logs.html. */
    private static final String PAGINA_URL = "/api/app/logs/pagina";

    /** Legacy tables render timestamps as yyyy-MM-dd HH:mm:ss. */
    private static final DateTimeFormatter FECHA_HORA =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Inject
    @Location("pages/registros/logs")
    Template paginaLogs;

    /**
     * Renders templates/pages/registros/logs.html: the Log de Actividades
     * page with the legacy filter panel (date range, user, tipo, source).
     * With the {@code HX-Request} header renders ONLY the {@code tabla}
     * fragment so filter/pager/sort swaps target {@code #tabla-logs} in
     * place; otherwise the full page through layout.html.
     *
     * <p>Same filter semantics as the JSON {@link #list}: ISO dates with
     * day-granularity end-of-day inclusion for fechaHasta and exact-username
     * user resolution. Over the HTML channel validation problems do NOT
     * answer 400/404 envelopes — they ride an out-of-band kit toast on a 200
     * fragment (ui-kit.md Pattern A) because htmx does not swap error
     * statuses by default. A page beyond the last yields the empty state,
     * never an error.</p>
     */
    @GET
    @Path("/pagina")
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Log de Actividades page (full page, or table fragment under HX-Request)")
    public Response pagina(@Context @Nonnull HttpHeaders headers,
                           @QueryParam("page") @DefaultValue("1") int page,
                           @QueryParam("size") @DefaultValue("20") int size,
                           @QueryParam("sort") @Nullable String sort,
                           @QueryParam("dir") @DefaultValue("asc") String dir,
                           @QueryParam("fechaDesde") @Nullable String fechaDesde,
                           @QueryParam("fechaHasta") @Nullable String fechaHasta,
                           @QueryParam("usuario") @Nullable String usuario,
                           @QueryParam("tipo") @Nullable String tipo,
                           @QueryParam("source") @Nullable String source) {
        boolean hx = headers.getHeaderString("HX-Request") != null;
        try {
            Map<String, Object> model = modeloPagina(page, size, sort, dir,
                    fechaDesde, fechaHasta, usuario, tipo, source, null, null);
            TemplateInstance vista = hx
                    ? paginaLogs.getFragment("tabla").data(model)
                    : paginaLogs.data(model);
            return Response.ok(vista)
                    .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                    .build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error renderizando la página del log de actividades", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR",
                            "No se pudo cargar la página del log de actividades"))
                    .build();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Read-side mapping ONLY, following the LogActividadDTO contract: the same
     * columns the legacy page displays (Tipo, Mensaje, Usuario, Origen, Valor
     * Anterior/Nuevo, Estado Leído/No leído). usuarioNombre stays null when
     * there is no user (the view renders it as "Sistema", exactly like the
     * legacy XHTML does).
     */
    private static LogActividadDTO toDTO(@Nonnull Alertas registro) {
        return new LogActividadDTO(
                registro.getCodigo(),
                registro.getTimestampAsDate(),
                registro.getTipo(),
                registro.getMensaje(),
                registro.getUser() != null ? registro.getUser().getUsername() : null,
                registro.getSource() != null ? registro.getSource() : "",
                registro.getAntes(),
                registro.getDespues(),
                registro.isVista());
    }

    /**
     * Parses an ISO-8601 date or datetime into a {@link Date}, mirroring how
     * findFiltered converts back to LocalDateTime in the system zone.
     * Returns null when {@code raw} is unparsable.
     */
    @Nullable
    private static Date parseFecha(@Nonnull String raw, boolean endOfDay) {
        try {
            if (raw.contains("T")) {
                return Date.from(LocalDateTime.parse(raw).atZone(ZoneId.systemDefault()).toInstant());
            }
            LocalDate dia = LocalDate.parse(raw);
            LocalDateTime dt = endOfDay ? dia.atTime(LocalTime.MAX) : dia.atStartOfDay();
            return Date.from(dt.atZone(ZoneId.systemDefault()).toInstant());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** In-memory window over a service result (findFiltered caps at 500). */
    private static <T> List<T> pageOf(@Nonnull List<T> source, int page, int size) {
        int from = Math.min(page * size, source.size());
        int to = Math.min(from + size, source.size());
        return source.subList(from, to);
    }

    // ── T30 page/fragment helpers ────────────────────────────────────────────

    /**
     * Full model for pages/registros/logs.html (both render modes share it).
     * Mirrors the legacy filter panel: invalid dates or an unknown usuario
     * produce a validation toast over an empty table instead of an error
     * status. Filter values round-trip raw into the inputs and URL-encoded
     * through the preserved params map (reserved keys excluded).
     */
    private Map<String, Object> modeloPagina(int page, int size, @Nullable String sort,
                                             @Nullable String dir,
                                             @Nullable String fechaDesde, @Nullable String fechaHasta,
                                             @Nullable String usuario, @Nullable String tipo,
                                             @Nullable String source,
                                             @Nullable String toastSeveridad,
                                             @Nullable String toastMessage) {
        int tamano = Math.min(Math.max(size, 1), 100);
        int paginaActual = Math.max(page, 1);
        String dirSegura = "desc".equalsIgnoreCase(dir) ? "desc" : "asc";
        String sortKey = (sort != null && !sort.isBlank()) ? sort.trim() : null;

        String fechaDesdeLimpia = limpiar(fechaDesde);
        String fechaHastaLimpia = limpiar(fechaHasta);
        String usuarioLimpio = limpiar(usuario);
        String tipoLimpio = limpiar(tipo);
        String sourceLimpio = limpiar(source);

        String mensajeValidacion = null;
        Date desde = null;
        Date hasta = null;
        Users selectedUser = null;
        if (fechaDesdeLimpia != null) {
            desde = parseFecha(fechaDesdeLimpia, false);
            if (desde == null) {
                mensajeValidacion =
                        "Formato de fecha inválido para fechaDesde. Use ISO-8601 (yyyy-MM-dd)";
            }
        }
        if (mensajeValidacion == null && fechaHastaLimpia != null) {
            hasta = parseFecha(fechaHastaLimpia, true);
            if (hasta == null) {
                mensajeValidacion =
                        "Formato de fecha inválido para fechaHasta. Use ISO-8601 (yyyy-MM-dd)";
            }
        }
        if (mensajeValidacion == null && usuarioLimpio != null) {
            selectedUser = loginService.findByUsername(usuarioLimpio);
            if (selectedUser == null) {
                mensajeValidacion = "No se encontró el usuario: " + usuarioLimpio;
            }
        }

        List<Alertas> registros = new ArrayList<>();
        if (mensajeValidacion == null) {
            registros = new ArrayList<>(alertasService.findFiltered(
                    desde, hasta, selectedUser, tipoLimpio, sourceLimpio));
            ordenar(registros, sortKey, dirSegura);
        }

        long total = registros.size();
        int totalPaginas = (int) Math.max(1L, (total + tamano - 1L) / tamano);
        long desdeIndice = (paginaActual - 1L) * (long) tamano;
        int from = (int) Math.min(desdeIndice, total);
        int to = (int) Math.min(desdeIndice + tamano, total);
        List<Map<String, Object>> filas = new ArrayList<>();
        for (Alertas registro : registros.subList(from, to)) {
            filas.add(fila(registro));
        }

        Map<String, Object> filtros = new LinkedHashMap<>();
        putFiltro(filtros, "fechaDesde", fechaDesdeLimpia);
        putFiltro(filtros, "fechaHasta", fechaHastaLimpia);
        putFiltro(filtros, "usuario", usuarioLimpio);
        putFiltro(filtros, "tipo", tipoLimpio);
        putFiltro(filtros, "source", sourceLimpio);

        Map<String, Object> tabla = new LinkedHashMap<>();
        tabla.put("id", "tabla-logs");
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

        if (mensajeValidacion != null) {
            toastSeveridad = "error";
            toastMessage = mensajeValidacion;
        }

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("titulo", "Log de Actividades");
        model.put("baseUrl", PAGINA_URL);
        model.put("modelo", tabla);
        model.put("fechaDesde", fechaDesdeLimpia);
        model.put("fechaHasta", fechaHastaLimpia);
        model.put("usuario", usuarioLimpio);
        model.put("tipo", tipoLimpio);
        model.put("source", sourceLimpio);
        model.put("tiposDisponibles", alertasService.findDistinctTipos());
        model.put("sourcesDisponibles", alertasService.findDistinctSources());
        model.put("toastSeveridad", toastSeveridad);
        model.put("toastMessage", toastMessage);
        return model;
    }

    /** Display row mirroring the legacy Log de Actividades columns. */
    private static Map<String, Object> fila(@Nonnull Alertas registro) {
        Map<String, Object> fila = new LinkedHashMap<>();
        fila.put("codigo", registro.getCodigo());
        fila.put("fecha", registro.getTimestamp() != null
                ? FECHA_HORA.format(registro.getTimestamp()) : "-");
        fila.put("tipo", registro.getTipo() != null ? registro.getTipo() : "-");
        fila.put("mensaje", registro.getMensaje() != null ? registro.getMensaje() : "");
        fila.put("usuario", registro.getUser() != null && registro.getUser().getUsername() != null
                ? registro.getUser().getUsername() : "Sistema");
        fila.put("origen", registro.getSource() != null ? registro.getSource() : "");
        fila.put("valorAnterior", registro.getAntes());
        fila.put("valorNuevo", registro.getDespues());
        fila.put("vista", registro.isVista());
        return fila;
    }

    /** Null when blank (same blank-means-unset rule as the JSON contract). */
    @Nullable
    private static String limpiar(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * The kit pager performs no encoding ("values must already be URL-safe"),
     * so preserved filter values are encoded here while the inputs keep the
     * raw round-trip.
     */
    private static void putFiltro(@Nonnull Map<String, Object> filtros,
                                  @Nonnull String clave, @Nullable String valor) {
        if (valor != null) {
            filtros.put(clave, URLEncoder.encode(valor, StandardCharsets.UTF_8));
        }
    }

    /** Columns mirror the legacy p:column set; null key ⇒ non-sortable. */
    private static List<Map<String, Object>> columnas() {
        return List.of(
                Map.of("label", "Fecha/Hora", "key", "fecha"),
                Map.of("label", "Tipo", "key", "tipo"),
                Map.of("label", "Mensaje"),
                Map.of("label", "Usuario", "key", "usuario"),
                Map.of("label", "Origen", "key", "origen"),
                Map.of("label", "Valor Anterior"),
                Map.of("label", "Valor Nuevo"),
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
            case "usuario" -> Comparator.comparing(LogActividadResource::usernameDe,
                    String.CASE_INSENSITIVE_ORDER);
            case "origen" -> Comparator.comparing(Alertas::getSource,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            default -> null;
        };
        if (cmp != null) {
            filas.sort("desc".equals(dir) ? cmp.reversed() : cmp);
        }
    }

    /** Legacy ternary parity: no user renders as "Sistema". */
    private static String usernameDe(@Nonnull Alertas registro) {
        return registro.getUser() != null && registro.getUser().getUsername() != null
                ? registro.getUser().getUsername() : "Sistema";
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
