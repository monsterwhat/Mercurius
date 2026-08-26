package Controllers.Api.App;

import Models.DTO.ApiResponse;
import Models.DTO.LogActividadDTO;
import Models.DTO.PagedResponse;
import Models.Registros.Alertas;
import Models.Users;
import Services.AlertasService;
import Services.LoginService;
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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.List;
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
}
