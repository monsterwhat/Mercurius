package Controllers.Api.App;

import Models.Clients;
import Models.AppSettings;
import Models.DTO.ApiResponse;
import Models.DTO.LoyaltySummaryDTO;
import Models.DTO.PagedResponse;
import Models.DTO.PuntosTransaccionDTO;
import Models.PuntosTransaccion;
import Models.Users;
import Services.AlertasService;
import Services.AppSettingsService;
import Services.ClientService;
import Services.LoginService;
import Services.LoyaltyService;
import Utils.DiffUtils;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Loyalty-program endpoints for the NEW Qute/HTMX app surface (/app world),
 * mirroring the legacy JSF {@code Controllers.LoyaltyController} (Loyalty
 * pages) as REST.
 *
 * <p>Reads reuse the exact service queries the legacy controller calls:
 * {@link ClientService#find} for the client lookup (clientCode is the
 * {@code Clients} primary key), {@link LoyaltyService#getTopLoyaltyCustomers}
 * and {@link LoyaltyService#getCustomerPointsHistory}. The tier color is a
 * verbatim port of {@code LoyaltyController.getCustomerTierColor} thresholds
 * (#ffd700 Oro / #c0c0c0 Plata / #cd7f32 Bronce / #cccccc Básico).</p>
 *
 * <p>{@code PUT /settings} updates ONLY cashbackPercentage and
 * puntosInactivityMonths on the current settings row, mirroring
 * {@code LoyaltyController.saveLoyaltySettings()} guards exactly: the legacy
 * guard is {@code currentSession.isValid()} — ported here as an explicit
 * SecurityIdentity check (SessionController is a @SessionScoped JSF-bound bean
 * and must not be injected into JAX-RS resources, see {@link AppAuthResource})
 * — followed by {@code appSettingsService.update(selectedSettings)} and the
 * legacy audit alert text. The class-level {@code @RolesAllowed} adds the
 * admin/facturacion gate the assignment fixes for this surface; it is dormant
 * until the form-cookie auth block is enabled in application.properties.</p>
 *
 * <p>All responses follow the {@link ApiResponse}/{@link PagedResponse}
 * envelope conventions.</p>
 */
@Path("/api/app/loyalty")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"admin", "facturacion"})
@Tag(name = "App - Lealtad")
public class LoyaltyResource {

    private static final Logger LOG = Logger.getLogger(LoyaltyResource.class.getName());

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
    AlertasService alertasService;

    @Inject
    @Nonnull
    LoginService loginService;

    @Inject
    @Nonnull
    SecurityIdentity securityIdentity;

    /**
     * Loyalty summary for one client: points balance, point status,
     * last purchase date and the legacy tier color.
     */
    @GET
    @Path("/summary/{clientCode}")
    @Operation(summary = "Loyalty summary for one client (points, status, tier color)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Missing admin/facturacion role"),
        @APIResponse(responseCode = "404", description = "Client not found"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response summary(
            @PathParam("clientCode") @Parameter(description = "Client code (Clients.code)") int clientCode) {
        try {
            Clients cliente = clientService.find(clientCode);
            if (cliente == null) {
                return notFoundClient(clientCode);
            }
            return Response.ok(ApiResponse.ok(toSummaryDTO(cliente))).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error building loyalty summary for client " + clientCode, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error consultando el resumen de lealtad"))
                    .build();
        }
    }

    /**
     * Clients with the highest points balances — same query as the legacy
     * loadTopCustomers(), which uses a fixed limit of 10.
     */
    @GET
    @Path("/top")
    @Operation(summary = "Top loyalty customers by points balance")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Missing admin/facturacion role"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response top(
            @QueryParam("limit") @DefaultValue("10") @Parameter(description = "Max clients (1-100)") int limit) {
        limit = Math.min(Math.max(limit, 1), 100);
        try {
            List<LoyaltySummaryDTO> data = loyaltyService.getTopLoyaltyCustomers(limit).stream()
                    .map(LoyaltyResource::toSummaryDTO)
                    .toList();
            return Response.ok(ApiResponse.ok(data)).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error listing top loyalty customers", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error listando los mejores clientes de lealtad"))
                    .build();
        }
    }

    /**
     * Points transaction history for one client, newest first
     * ({@code getCustomerPointsHistory} orders by fechaCreacion DESC),
     * paginated in memory because the service returns the full list.
     */
    @GET
    @Path("/{clientCode}/history")
    @Operation(summary = "Paginated points transaction history for one client")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Missing admin/facturacion role"),
        @APIResponse(responseCode = "404", description = "Client not found"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response history(
            @PathParam("clientCode") @Parameter(description = "Client code (Clients.code)") int clientCode,
            @QueryParam("page") @DefaultValue("0") @Parameter(description = "Page number (0-based)") int page,
            @QueryParam("size") @DefaultValue("20") @Parameter(description = "Page size (max 100)") int size) {

        // Clamp to the SuppliersController/UsersResource convention.
        size = Math.min(Math.max(size, 1), 100);
        page = Math.max(page, 0);

        try {
            Clients cliente = clientService.find(clientCode);
            if (cliente == null) {
                return notFoundClient(clientCode);
            }

            List<PuntosTransaccion> historial = loyaltyService.getCustomerPointsHistory(cliente);
            long total = historial.size();
            List<PuntosTransaccionDTO> data = pageOf(historial, page, size).stream()
                    .map(LoyaltyResource::toTransaccionDTO)
                    .toList();
            return Response.ok(new PagedResponse<>(data, total, page, size)).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error listing points history for client " + clientCode, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error consultando el historial de puntos"))
                    .build();
        }
    }

    /**
     * Update the loyalty operational settings (cashbackPercentage,
     * puntosInactivityMonths). Guard chain mirrors
     * {@code LoyaltyController.saveLoyaltySettings()}: session validity first
     * ("Sesión Inválida" / "No tiene permisos para realizar esta acción"),
     * then update + legacy audit alert. Fields left null are not modified.
     */
    @PUT
    @Path("/settings")
    @Transactional
    @Operation(summary = "Update loyalty settings (cashbackPercentage, puntosInactivityMonths)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Settings updated"),
        @APIResponse(responseCode = "400", description = "Validation error"),
        @APIResponse(responseCode = "401", description = "Not authenticated / invalid session"),
        @APIResponse(responseCode = "403", description = "Missing admin/facturacion role"),
        @APIResponse(responseCode = "404", description = "No active settings row"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response updateSettings(@Nullable LoyaltySettingsRequest request) {
        try {
            // Guard parity with saveLoyaltySettings(): currentSession.isValid().
            // Ported against SecurityIdentity because SessionController cannot
            // be injected into JAX-RS resources.
            if (!sessionValid()) {
                return Response.status(Response.Status.UNAUTHORIZED)
                        .entity(ApiResponse.error("SESSION_INVALID",
                                "No tiene permisos para realizar esta acción"))
                        .build();
            }

            if (request == null || (request.cashbackPercentage == null && request.puntosInactivityMonths == null)) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(ApiResponse.error("VALIDATION_ERROR",
                                "Debe indicar al menos un campo a actualizar (cashbackPercentage o puntosInactivityMonths)"))
                        .build();
            }
            if (request.cashbackPercentage != null
                    && request.cashbackPercentage.compareTo(BigDecimal.ZERO) < 0) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(ApiResponse.error("VALIDATION_ERROR",
                                "El porcentaje de cashback no puede ser negativo"))
                        .build();
            }
            if (request.puntosInactivityMonths != null && request.puntosInactivityMonths <= 0) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(ApiResponse.error("VALIDATION_ERROR",
                                "Los meses de inactividad deben ser mayores a cero"))
                        .build();
            }

            // Legacy loads selectedSettings via appSettingsService.returnCurrent()
            // and silently skips the save when it is null; over REST that state
            // is surfaced as 404 instead of a silent no-op.
            AppSettings settings = appSettingsService.returnCurrent();
            if (settings == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("NOT_FOUND", "No hay una configuración activa del sistema"))
                        .build();
            }

            if (request.cashbackPercentage != null) {
                settings.setCashbackPercentage(request.cashbackPercentage);
            }
            if (request.puntosInactivityMonths != null) {
                settings.setPuntosInactivityMonths(request.puntosInactivityMonths);
            }
            appSettingsService.update(settings);

            // Audit parity with saveLoyaltySettings() (legacy type + message).
            // Deliberate deviation: legacy recorded settings.toString(); the
            // DiffUtils JSON snapshot matches SettingsDirController's
            // antes/despues convention and stays diffable.
            alertasService.registrarAlerta(
                    "Configuración de Lealtad Actualizada",
                    "Se han actualizado las configuraciones del programa de lealtad",
                    currentUserOrNull(),
                    0,
                    "LoyaltyResource.updateSettings()",
                    null,
                    DiffUtils.snapshotEntity(settings));

            return Response.ok(ApiResponse.ok(new LoyaltySettingsResponse(
                    settings.getCashbackPercentage(),
                    settings.getPuntosInactivityMonths()))).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error updating loyalty settings", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error actualizando la configuración de lealtad"))
                    .build();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Mirror of SessionController.isValid()'s SecurityIdentity fallback branch. */
    private boolean sessionValid() {
        return securityIdentity != null && !securityIdentity.isAnonymous();
    }

    /**
     * Resolves the authenticated Users row for audit attribution, mirroring
     * legacy currentSession.getCurrentUser(); null when anonymous/unknown.
     */
    @Nullable
    private Users currentUserOrNull() {
        if (securityIdentity == null || securityIdentity.isAnonymous()
                || securityIdentity.getPrincipal() == null) {
            return null;
        }
        return loginService.findByUsername(securityIdentity.getPrincipal().getName());
    }

    private static Response notFoundClient(int clientCode) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(ApiResponse.error("NOT_FOUND", "No se encontró el cliente: " + clientCode))
                .build();
    }

    /**
     * Manual mapper: Clients → LoyaltySummaryDTO with the tier color ported
     * verbatim from LoyaltyController.getCustomerTierColor().
     */
    private static LoyaltySummaryDTO toSummaryDTO(@Nonnull Clients cliente) {
        return new LoyaltySummaryDTO(
                cliente.getCode(),
                cliente.getName(),
                cliente.getPuntosAcumulados(),
                cliente.getStatusPuntos(),
                cliente.getLastPurchaseDate(),
                tierColor(cliente));
    }

    /** Verbatim port of LoyaltyController.getCustomerTierColor(). */
    @Nonnull
    private static String tierColor(@Nonnull Clients cliente) {
        if (cliente.getPuntosAcumulados() == null) {
            return "#cccccc"; // Gray
        }
        BigDecimal points = cliente.getPuntosAcumulados();
        if (points.compareTo(BigDecimal.valueOf(10000)) >= 0) {
            return "#ffd700"; // Gold
        } else if (points.compareTo(BigDecimal.valueOf(5000)) >= 0) {
            return "#c0c0c0"; // Silver
        } else if (points.compareTo(BigDecimal.ZERO) > 0) {
            return "#cd7f32"; // Bronze
        }
        return "#cccccc"; // Basic
    }

    /** Manual mapper: PuntosTransaccion → PuntosTransaccionDTO (cliente flattened). */
    private static PuntosTransaccionDTO toTransaccionDTO(@Nonnull PuntosTransaccion tx) {
        Clients cliente = tx.getCliente();
        return new PuntosTransaccionDTO(
                tx.getFechaCreacion(),
                tx.getTipoTransaccion(),
                tx.getPuntos(),
                tx.getSaldoPuntos(),
                tx.getDescripcion(),
                tx.getFacturaId(),
                cliente != null ? cliente.getCode() : 0,
                cliente != null ? cliente.getName() : "");
    }

    /** In-memory window over a service result. */
    private static <T> List<T> pageOf(@Nonnull List<T> source, int page, int size) {
        int from = Math.min(page * size, source.size());
        int to = Math.min(from + size, source.size());
        return source.subList(from, to);
    }

    /** PUT /settings payload; both fields optional, null = leave unchanged. */
    public static class LoyaltySettingsRequest {
        @Nullable
        public BigDecimal cashbackPercentage;
        @Nullable
        public Integer puntosInactivityMonths;
    }

    /** PUT /settings response: the effective values after the update. */
    public static class LoyaltySettingsResponse {
        @Nullable
        public BigDecimal cashbackPercentage;
        @Nullable
        public Integer puntosInactivityMonths;

        public LoyaltySettingsResponse(@Nullable BigDecimal cashbackPercentage,
                                       @Nullable Integer puntosInactivityMonths) {
            this.cashbackPercentage = cashbackPercentage;
            this.puntosInactivityMonths = puntosInactivityMonths;
        }
    }
}
