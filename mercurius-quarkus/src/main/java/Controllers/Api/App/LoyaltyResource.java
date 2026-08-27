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
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    // ── W4B view-half (T25): HTML surface over the JSON contracts above ─────

    private static final String BASE_URL = "/api/app/loyalty";
    /** Legacy loadTopCustomers() used a fixed limit of 10. */
    private static final int TOP_LIMIT = 10;

    /** Request context (quarkus-rest injectable) — source of HX-Request. */
    @Inject
    @Nonnull
    RoutingContext routing;

    @Inject
    @Nonnull
    @Location("pages/loyalty/index.html")
    Template pageIndex;

    @Inject
    @Nonnull
    @Location("pages/loyalty/tabla-top.html")
    Template tablaTop;

    @Inject
    @Nonnull
    @Location("pages/loyalty/panel-ajustes.html")
    Template panelAjustes;

    @Inject
    @Nonnull
    @Location("pages/loyalty/drawer-historial.html")
    Template drawerHistorial;

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

    // ── W4B view-half (T25): additive HTML surface ───────────────────────────
    //
    // The JSON contracts above stay untouched. These endpoints serve
    // templates/pages/loyalty/* per docs/ui-kit.md: a dual-mode table, drawer
    // fragments, and a form-urlencoded twin of PUT /settings that DELEGATES to
    // updateSettings() so the guard chain is mirrored by construction
    // (ClientsResource form-twin convention, T18).

    /**
     * GET /table?page&size&sort&dir — top-customers data-table. With the
     * {@code HX-Request} header returns ONLY the data-table include
     * ({@code pages/loyalty/tabla-top.html}); without it renders the FULL
     * loyalty page. One endpoint renders page and fragments, all
     * paging/sorting state lives in the URL, {@code page} is 1-based here
     * (the JSON endpoints keep their own 0-based contracts untouched). Rows
     * are the legacy fixed top-10 mapped through {@link #toSummaryDTO};
     * sorting is in-memory over that small list.
     */
    @GET
    @Path("/table")
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Top-customers data-table fragment (HX-Request) or full loyalty page", hidden = true)
    @APIResponses({
        @APIResponse(responseCode = "200", description = "HTML fragment or full page"),
        @APIResponse(responseCode = "500", description = "Template rendering failure")
    })
    public Response table(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("10") int size,
            @QueryParam("sort") @Nullable String sort,
            @QueryParam("dir") @DefaultValue("asc") String dir) {
        try {
            if (isHxRequest()) {
                return htmlOk(tablaInstance(page, size, sort, dir));
            }
            return htmlOk(pageIndex
                    .data("modelo", tablaModel(page, size, sort, dir))
                    .data("ajustes", ajustesMap()));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error renderizando la página de lealtad", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error renderizando la página de lealtad"))
                    .build();
        }
    }

    /**
     * GET /{clientCode}/view?page&size — history drawer body: the client
     * summary card ({@link LoyaltySummaryDTO} with tier badge) plus the
     * paginated points history ({@link PuntosTransaccionDTO}, newest first).
     * Page contract mirrors {@link #history}: 0-based page, size clamped
     * 1..100. Unknown clients answer 404 with an HTML body (semantic parity
     * with the JSON endpoints; htmx callers only reach live rows).
     */
    @GET
    @Path("/{clientCode}/view")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    @Operation(summary = "Client summary card + paginated points-history drawer body", hidden = true)
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Drawer body HTML"),
        @APIResponse(responseCode = "404", description = "Client not found"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response view(
            @PathParam("clientCode") int clientCode,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {
        try {
            Clients cliente = clientService.find(clientCode);
            if (cliente == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                        .entity("<div class=\"notification is-danger is-light\">No se encontró el cliente: "
                                + clientCode + "</div>")
                        .build();
            }

            size = Math.min(Math.max(size, 1), 100);
            page = Math.max(page, 0);

            List<PuntosTransaccion> historial = orEmpty(loyaltyService.getCustomerPointsHistory(cliente));
            long total = historial.size();
            int totalPages = (int) Math.max(1L, (long) Math.ceil(total / (double) size));
            List<PuntosTransaccionDTO> filas = pageOf(historial, page, size).stream()
                    .map(LoyaltyResource::toTransaccionDTO)
                    .toList();

            String html = drawerHistorial
                    .data("resumen", toSummaryDTO(cliente))
                    .data("filas", filas)
                    .data("paginaActual", page + 1)
                    .data("totalPages", totalPages)
                    .data("total", total)
                    .data("paginaAnterior", page > 0 ? page - 1 : -1)
                    .data("paginaSiguiente", page + 1 < totalPages ? page + 1 : -1)
                    .data("size", size)
                    .render();
            return Response.ok(html)
                    .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                    .build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error renderizando el historial de puntos del cliente " + clientCode, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error renderizando el historial de puntos"))
                    .build();
        }
    }

    /**
     * Form-urlencoded twin of {@link #updateSettings} for the HTMX settings
     * panel (JAX-RS selects by Content-Type; the JSON contract is untouched).
     * Guard parity is structural: the payload is parsed into a
     * {@link LoyaltySettingsRequest} and handed to {@link #updateSettings()},
     * so session / at-least-one-field / cashback / months rejections return
     * the EXACT same status codes and messages as the JSON surface. HTMX
     * callers get the redisplayed panel fragment plus an out-of-band toast
     * (ui-kit §5 Pattern A); any other caller receives the original JSON
     * response unchanged.
     */
    @PUT
    @Path("/settings/form")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    @Operation(summary = "Update loyalty settings from the HTMX panel form", hidden = true)
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Panel fragment with updated values + success toast"),
        @APIResponse(responseCode = "400", description = "Validation error (guard parity with PUT /settings)"),
        @APIResponse(responseCode = "401", description = "Invalid session (guard parity with PUT /settings)"),
        @APIResponse(responseCode = "404", description = "No active settings row"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response settingsForm(
            @FormParam("cashbackPercentage") @Nullable String cashbackPercentage,
            @FormParam("puntosInactivityMonths") @Nullable String puntosInactivityMonths) {
        try {
            BigDecimal cashback = parseDecimal(cashbackPercentage);
            if (cashback == null && hasText(cashbackPercentage)) {
                return redisplayAjustes(Response.Status.BAD_REQUEST.getStatusCode(),
                        "El porcentaje de cashback indicado no es un número válido");
            }
            Integer meses = parseInteger(puntosInactivityMonths);
            if (meses == null && hasText(puntosInactivityMonths)) {
                return redisplayAjustes(Response.Status.BAD_REQUEST.getStatusCode(),
                        "Los meses de inactividad indicados no son un número válido");
            }

            LoyaltySettingsRequest request = new LoyaltySettingsRequest();
            request.cashbackPercentage = cashback;
            request.puntosInactivityMonths = meses;
            Response result = updateSettings(request);

            if (!isHxRequest()) {
                return result;
            }

            if (result.getStatus() == Response.Status.OK.getStatusCode()) {
                return Response.ok(ajustesInstance(null, "success",
                                "Las configuraciones de lealtad se han guardado exitosamente").render())
                        .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                        .build();
            }
            return redisplayAjustes(result.getStatus(), mensajeDe(result));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error actualizando la configuración de lealtad desde el formulario", e);
            return redisplayAjustes(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(),
                    "Error actualizando la configuración de lealtad");
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

    // ── W4B template-model helpers (ClientsResource/T18 conventions) ────────

    /** True when the call comes from HTMX (layout hx-headers carries it). */
    private boolean isHxRequest() {
        String header = routing.request().getHeader("HX-Request");
        return header != null && !"false".equalsIgnoreCase(header);
    }

    private static Response htmlOk(@Nonnull TemplateInstance template) {
        return Response.ok(template.render())
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).build();
    }

    @Nullable
    private static String mensajeDe(@Nonnull Response result) {
        if (result.getEntity() instanceof ApiResponse<?> api && api.getError() != null) {
            return api.getError().getMessage();
        }
        return "No se pudo guardar la configuración";
    }

    /** Panel fragment redisplayed with the CURRENT settings + error toast. */
    private Response redisplayAjustes(int status, @Nullable String mensaje) {
        return Response.status(status)
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                .entity(ajustesInstance(mensaje, "error", mensaje).render())
                .build();
    }

    private TemplateInstance ajustesInstance(@Nullable String errorGeneral,
                                             @Nullable String toastSeverity,
                                             @Nullable String toastMessage) {
        return panelAjustes
                .data("ajustes", ajustesMap())
                .data("errorGeneral", errorGeneral)
                .data("toastSeverity", toastSeverity)
                .data("toastMessage", toastMessage);
    }

    /** Current loyalty settings for the panel; ZERO/0 defaults mirror T20 reportes stats. */
    private Map<String, Object> ajustesMap() {
        AppSettings settings = appSettingsService.returnCurrent();
        Map<String, Object> ajustes = new HashMap<>();
        ajustes.put("cashbackPercentage",
                settings == null || settings.getCashbackPercentage() == null
                        ? BigDecimal.ZERO : settings.getCashbackPercentage());
        ajustes.put("puntosInactivityMonths",
                settings == null || settings.getPuntosInactivityMonths() == null
                        ? Integer.valueOf(0) : settings.getPuntosInactivityMonths());
        return ajustes;
    }

    private TemplateInstance tablaInstance(int page, int size, @Nullable String sort,
                                           @Nullable String dir) {
        return tablaTop.data("modelo", tablaModel(page, size, sort, dir));
    }

    /**
     * Kit data-table model over the legacy fixed top-10: id/baseUrl/columnas/
     * filas/sortKey/sortDir/page/size/total/totalPages/paginas/filtros.
     */
    private Map<String, Object> tablaModel(int page, int size, @Nullable String sort,
                                           @Nullable String dir) {
        List<LoyaltySummaryDTO> filas = new ArrayList<>(orEmpty(loyaltyService.getTopLoyaltyCustomers(TOP_LIMIT))
                .stream().map(LoyaltyResource::toSummaryDTO).toList());

        boolean descending = "desc".equalsIgnoreCase(dir);
        Comparator<LoyaltySummaryDTO> comparator = switch (sort == null ? "" : sort) {
            case "nombre" -> Comparator.comparing(LoyaltySummaryDTO::getClienteNombre,
                    Comparator.nullsLast(String::compareTo));
            case "puntos", "nivel" -> Comparator.comparing(LoyaltySummaryDTO::getPuntosAcumulados,
                    Comparator.nullsLast(BigDecimal::compareTo));
            case "ultimacompra" -> Comparator.comparing(LoyaltySummaryDTO::getLastPurchaseDate,
                    Comparator.nullsLast(Date::compareTo));
            default -> null;
        };
        if (comparator != null) {
            filas.sort(descending ? comparator.reversed() : comparator);
        }

        page = Math.max(page, 1);
        size = Math.min(Math.max(size, 1), 100);
        long total = filas.size();
        int totalPages = (int) Math.max(1L, (long) Math.ceil(total / (double) size));
        if (page > totalPages) {
            page = totalPages;
        }
        List<Integer> paginas = new ArrayList<>();
        for (int p = Math.max(1, page - 2); p <= Math.min(totalPages, page + 2); p++) {
            paginas.add(p);
        }

        Map<String, Object> modelo = new HashMap<>();
        modelo.put("id", "loyalty-top-tabla");
        modelo.put("baseUrl", BASE_URL + "/table");
        modelo.put("columnas", columnasTop());
        modelo.put("filas", filas);
        modelo.put("sortKey", sort == null ? "" : sort);
        modelo.put("sortDir", descending ? "desc" : "asc");
        modelo.put("page", page);
        modelo.put("size", size);
        modelo.put("total", total);
        modelo.put("totalPages", totalPages);
        modelo.put("paginas", paginas);
        modelo.put("filtros", Map.of());
        return modelo;
    }

    /** Legacy column coverage; Acciones is non-sortable (null key). */
    private static @Nonnull List<Map<String, Object>> columnasTop() {
        return List.of(
                columna("Cliente", "nombre"),
                columna("Puntos Acumulados", "puntos"),
                columna("Nivel", "nivel"),
                columna("Última Compra", "ultimacompra"),
                columna("Acciones", null));
    }

    /** Null-tolerant header builder (Map.of rejects null values). */
    private static @Nonnull Map<String, Object> columna(@Nonnull String label, @Nullable String key) {
        Map<String, Object> map = new HashMap<>();
        map.put("label", label);
        map.put("key", key);
        return map;
    }

    private static @Nonnull <T> List<T> orEmpty(@Nullable List<T> list) {
        return list != null ? list : new ArrayList<>();
    }

    @Nullable
    private static BigDecimal parseDecimal(@Nullable String raw) {
        if (!hasText(raw)) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    private static Integer parseInteger(@Nullable String raw) {
        if (!hasText(raw)) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean hasText(@Nullable String raw) {
        return raw != null && !raw.isBlank();
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
