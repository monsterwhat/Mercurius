package Controllers.Api.App;

import Models.DTO.AlertaDTO;
import Models.DTO.ApiResponse;
import Models.DTO.PagedResponse;
import Models.Registros.Alertas;
import Services.AlertasService;
import jakarta.annotation.Nonnull;
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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
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
}
