package Controllers.Api.App;

import Models.CierreCaja;
import Models.DTO.ApiResponse;
import Models.DTO.CierreCajaDTO;
import Models.DTO.PagedResponse;
import Models.Users;
import Services.CierreCajaService;
import Services.LoginService;
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
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
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
 * Cierre de caja (cash-shift) endpoints for the NEW Qute/HTMX app surface,
 * replacing the legacy JSF {@code Controllers.CierreCajaController}
 * (@ViewScoped, pages/Caja/index.xhtml) as REST + server-rendered views.
 *
 * <p>Delegation is verbatim: {@link CierreCajaService#findSesionAbierta},
 * {@link CierreCajaService#listHistorial}, {@code create}/{@code update} from
 * {@code GService}. The close math is a line-for-line port of
 * {@code CierreCajaController.cerrarSesion()}: null contado amounts become
 * ZERO, {@code totalEsperado} sums the three nullable expected buckets,
 * {@code diferencia = totalContado - totalEsperado}, then contado fields +
 * diferencia + fechaCierre + estado="cerrado" + notas are persisted through
 * {@code cierreCajaService.update}. The open path ports
 * {@code abrirSesion()} including its guard ({@code monto <= 0} rejected with
 * the legacy message "Debe ingresar un monto inicial valido"); like the
 * legacy controller it does NOT guard against an already-open session (the
 * legacy page simply hid the form — the UI-level guard lives in
 * {@code pages/caja/estado-caja.html}).</p>
 *
 * <p><b>Documented deviations</b> (behavior-preserving otherwise):</p>
 * <ul>
 *   <li>The legacy silent no-op when closing without an open session
 *       ({@code if (sesionActual == null) return;}) surfaces here as
 *       404 NOT_FOUND with an explicit message — the LoyaltyResource (T25)
 *       convention for legacy silent states.</li>
 *   <li>The legacy open audit alert recorded the {@code montoApertura} field
 *       AFTER it was nulled, so it always logged "monto inicial: null". This
 *       port logs the real amount; DB effects are identical.</li>
 *   <li>The legacy INFO FacesMessage "Sesion de caja cerrada. Diferencia: X"
 *       is preserved verbatim as {@link CloseResult#mensaje} and additionally
 *       classified: {@link CloseResult#advertenciaDiferencia} is true when
 *       the difference is non-zero (the "open-difference warning path",
 *       previously only human-readable).</li>
 * </ul>
 *
 * <p>HTML half follows the T25/T18 conventions: one dual-mode endpoint
 * ({@code GET /table}: HX-Request renders ONLY the history table fragment,
 * otherwise the full page through layout.html) and form-urlencoded twins of
 * the JSON mutations that DELEGATE to them so guards match by construction
 * (docs/ui-kit.md §5 Pattern A redisplay + out-of-band toast).</p>
 */
@Path("/api/app/caja")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"admin", "facturacion"})
@Tag(name = "App - Cierre de Caja")
public class CierreCajaResource {

    private static final Logger LOG = Logger.getLogger(CierreCajaResource.class.getName());

    /** Legacy FacesMessage texts, kept byte-for-byte. */
    static final String MSG_APERTURA_INVALIDA = "Debe ingresar un monto inicial valido";
    static final String MSG_SESION_CERRADA = "Sesion de caja cerrada. Diferencia: ";
    static final String MSG_SESION_ABIERTA_OK = "Sesion de caja abierta correctamente";
    static final String MSG_SIN_SESION = "No hay una sesion de caja abierta";

    private static final String BASE_URL = "/api/app/caja";
    private static final String DATE_PATTERN = "dd/MM/yyyy HH:mm";
    private static final String MONEY_SYMBOL = "\u20A1"; // colón

    @Nonnull
    @Inject
    CierreCajaService cierreCajaService;

    @Nonnull
    @Inject
    LoginService loginService;

    @Inject
    @Nonnull
    SecurityIdentity securityIdentity;

    /** Request context (quarkus-rest injectable) — source of HX-Request. */
    @Nonnull
    @Inject
    RoutingContext routing;

    @Nonnull
    @Location("pages/caja/index.html")
    @Inject
    Template pageIndex;

    @Nonnull
    @Location("pages/caja/estado-caja.html")
    @Inject
    Template estadoCaja;

    @Nonnull
    @Location("pages/caja/tabla-historial.html")
    @Inject
    Template tablaHistorial;

    // ── JSON surface ─────────────────────────────────────────────────────────

    /**
     * Current open session for the authenticated user as
     * {@link CierreCajaDTO}; {@code data} is {@code null} when no session is
     * open (parity with legacy init(), which left sesionActual null and let
     * the page render the open-session form).
     */
    @GET
    @Operation(summary = "Current open cash-shift session summary (data null when none open)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success (data may be null)"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Missing admin/facturacion role"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response summary() {
        try {
            Users usuario = currentUserOrNull();
            if (usuario == null) {
                return unauthorized();
            }
            CierreCaja sesion = cierreCajaService.findSesionAbierta(usuario);
            return Response.ok(ApiResponse.ok(sesion == null ? null : toDTO(sesion))).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error consultando la sesion de caja actual", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error consultando la sesion de caja"))
                    .build();
        }
    }

    /**
     * Open a cash shift. Guard parity with {@code abrirSesion()}: a null or
     * non-positive monto rejects with the legacy validation message. Creates
     * the CierreCaja row (estado="abierto") and writes the legacy audit
     * alert.
     */
    @POST
    @Path("/open")
    @Transactional
    @Operation(summary = "Open a cash-shift session with an initial amount")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Session opened"),
        @APIResponse(responseCode = "400", description = "Validation error (monto inicial vacio o <= 0)"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Missing admin/facturacion role"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response open(@Nullable OpenRequest request) {
        try {
            Users usuario = currentUserOrNull();
            if (usuario == null) {
                return unauthorized();
            }
            BigDecimal monto = request == null ? null : request.montoApertura;
            if (monto == null || monto.compareTo(BigDecimal.ZERO) <= 0) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(ApiResponse.error("VALIDATION_ERROR", MSG_APERTURA_INVALIDA))
                        .build();
            }

            CierreCaja sesion = new CierreCaja();
            sesion.setUsuario(usuario);
            sesion.setFechaApertura(new Date());
            sesion.setMontoInicial(monto);
            sesion.setEstado("abierto");
            cierreCajaService.create(sesion);

            // Legacy alert text; deliberate deviation: the legacy controller
            // logged the field after nulling it (always "null").
            LOG.log(Level.INFO, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s",
                    "Caja abierta", "Sesion de caja abierta con monto inicial: " + monto.toPlainString(),
                    usuario != null ? usuario.getUsername() : "Sistema",
                    0, "CierreCajaResource.open()", null, null));

            return Response.ok(ApiResponse.ok(toDTO(sesion))).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error abriendo la sesion de caja", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error abriendo la sesion de caja"))
                    .build();
        }
    }

    /**
     * Close the current open cash shift — verbatim port of
     * {@code cerrarSesion()}. The computed difference is returned both raw
     * and as the structured {@link CloseResult#advertenciaDiferencia} flag
     * (true when non-zero), preserving the legacy open-difference warning
     * path as data instead of a FacesMessage.
     */
    @POST
    @Path("/close")
    @Transactional
    @Operation(summary = "Close the open cash-shift session (structured difference warning)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Session closed (check advertenciaDiferencia)"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Missing admin/facturacion role"),
        @APIResponse(responseCode = "404", description = "No open session (legacy silent no-op, surfaced)"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response close(@Nullable CloseRequest request) {
        try {
            Users usuario = currentUserOrNull();
            if (usuario == null) {
                return unauthorized();
            }
            CierreCaja sesionActual = cierreCajaService.findSesionAbierta(usuario);
            if (sesionActual == null) {
                // Legacy silently returned; surfaced as 404 per T25 convention.
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("NOT_FOUND", MSG_SIN_SESION))
                        .build();
            }

            BigDecimal montoContadoEfectivo = request == null || request.montoContadoEfectivo == null
                    ? BigDecimal.ZERO : request.montoContadoEfectivo;
            BigDecimal montoContadoSinpe = request == null || request.montoContadoSinpe == null
                    ? BigDecimal.ZERO : request.montoContadoSinpe;
            BigDecimal montoContadoTarjeta = request == null || request.montoContadoTarjeta == null
                    ? BigDecimal.ZERO : request.montoContadoTarjeta;

            sesionActual.setMontoContadoEfectivo(montoContadoEfectivo);
            sesionActual.setMontoContadoSinpe(montoContadoSinpe);
            sesionActual.setMontoContadoTarjeta(montoContadoTarjeta);

            BigDecimal totalEsperado = BigDecimal.ZERO;
            if (sesionActual.getMontoEsperadoEfectivo() != null) {
                totalEsperado = totalEsperado.add(sesionActual.getMontoEsperadoEfectivo());
            }
            if (sesionActual.getMontoEsperadoSinpe() != null) {
                totalEsperado = totalEsperado.add(sesionActual.getMontoEsperadoSinpe());
            }
            if (sesionActual.getMontoEsperadoTarjeta() != null) {
                totalEsperado = totalEsperado.add(sesionActual.getMontoEsperadoTarjeta());
            }

            BigDecimal totalContado = montoContadoEfectivo.add(montoContadoSinpe).add(montoContadoTarjeta);
            BigDecimal diferencia = totalContado.subtract(totalEsperado);

            sesionActual.setDiferencia(diferencia);
            sesionActual.setFechaCierre(new Date());
            sesionActual.setEstado("cerrado");
            sesionActual.setNotas(request == null ? null : request.notas);
            cierreCajaService.update(sesionActual);

            LOG.log(Level.INFO, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s",
                    "Caja cerrada", MSG_SESION_CERRADA + diferencia,
                    usuario != null ? usuario.getUsername() : "Sistema",
                    0, "CierreCajaResource.close()", null, null));

            boolean advertencia = diferencia.compareTo(BigDecimal.ZERO) != 0;
            CloseResult result = new CloseResult(
                    toDTO(sesionActual), totalEsperado, totalContado, diferencia,
                    advertencia, MSG_SESION_CERRADA + diferencia);
            return Response.ok(ApiResponse.ok(result)).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error cerrando la sesion de caja", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error cerrando la sesion de caja"))
                    .build();
        }
    }

    /**
     * Session history for the authenticated user, newest first
     * ({@code listHistorial} orders by fechaApertura DESC), paginated in
     * memory because the service returns the full list.
     */
    @GET
    @Path("/history")
    @Operation(summary = "Paginated cash-shift history for the authenticated user")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Missing admin/facturacion role"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response history(
            @QueryParam("page") @DefaultValue("0") @Parameter(description = "Page number (0-based)") int page,
            @QueryParam("size") @DefaultValue("20") @Parameter(description = "Page size (max 100)") int size) {

        size = Math.min(Math.max(size, 1), 100);
        page = Math.max(page, 0);

        try {
            Users usuario = currentUserOrNull();
            if (usuario == null) {
                return unauthorized();
            }
            List<CierreCaja> historial = orEmpty(cierreCajaService.listHistorial(usuario));
            long total = historial.size();
            List<CierreCajaDTO> data = pageOf(historial, page, size).stream()
                    .map(CierreCajaResource::toDTO)
                    .toList();
            return Response.ok(new PagedResponse<>(data, total, page, size)).build();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error listando el historial de caja", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error listando el historial de caja"))
                    .build();
        }
    }

    // ── HTML surface (T38 view-half, T18/T25 conventions) ───────────────────

    /**
     * GET /table?page&size&sort&dir — history data-table. With the
     * {@code HX-Request} header returns ONLY the table include
     * ({@code pages/caja/tabla-historial.html}); without it renders the FULL
     * caja page. One endpoint renders page and fragments; all paging/sorting
     * state lives in the URL; {@code page} is 1-based here (the JSON
     * /history contract stays 0-based and untouched).
     */
    @GET
    @Path("/table")
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "History data-table fragment (HX-Request) or full caja page", hidden = true)
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
            Users usuario = currentUserOrNull();
            if (usuario == null) {
                return unauthorized();
            }
            if (isHxRequest()) {
                return htmlOk(tablaHistorial.data("modelo", tablaModel(usuario, page, size, sort, dir)));
            }
            return htmlOk(pageIndex
                    .data("sesion", sesionView(usuario))
                    .data("modelo", tablaModel(usuario, page, size, sort, dir)));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error renderizando la pagina de caja", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error renderizando la pagina de caja"))
                    .build();
        }
    }

    /**
     * Form-urlencoded twin of {@link #open} for the HTMX open-session form.
     * Parses the amount and delegates to {@link #open}, so the guard chain is
     * mirrored by construction. HTMX callers get the estado-caja region
     * redisplayed plus an out-of-band toast (ui-kit §5 Pattern A); any other
     * caller receives the original JSON response unchanged.
     */
    @POST
    @Path("/open/form")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    @Operation(summary = "Open a cash-shift session from the HTMX form", hidden = true)
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Estado fragment + success toast"),
        @APIResponse(responseCode = "400", description = "Validation error (guard parity with POST /open)"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response openForm(@FormParam("montoApertura") @Nullable String montoApertura) {
        try {
            BigDecimal monto = parseDecimal(montoApertura);
            OpenRequest request = new OpenRequest();
            request.montoApertura = monto;
            Response result = open(request);

            if (!isHxRequest()) {
                return result;
            }
            if (result.getStatus() == Response.Status.OK.getStatusCode()) {
                return estadoOk(null, "success", MSG_SESION_ABIERTA_OK);
            }
            return estadoError(result.getStatus(), mensajeDe(result, MSG_APERTURA_INVALIDA));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error abriendo la sesion de caja desde el formulario", e);
            return estadoError(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(),
                    "Error abriendo la sesion de caja");
        }
    }

    /**
     * Form-urlencoded twin of {@link #close} for the HTMX close-session form
     * (the confirm modal comes from _kit/confirm.html via hx-confirm on the
     * submit button). Delegates to {@link #close}; on success the estado-caja
     * region swaps back to the open-session form and the legacy close message
     * rides along as an OOB toast whose severity IS the difference warning
     * (warning when non-zero, success when balanced).
     */
    @POST
    @Path("/close/form")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    @Operation(summary = "Close the cash-shift session from the HTMX form", hidden = true)
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Estado fragment + warning/success toast"),
        @APIResponse(responseCode = "404", description = "No open session (guard parity with POST /close)"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response closeForm(
            @FormParam("montoContadoEfectivo") @Nullable String montoContadoEfectivo,
            @FormParam("montoContadoSinpe") @Nullable String montoContadoSinpe,
            @FormParam("montoContadoTarjeta") @Nullable String montoContadoTarjeta,
            @FormParam("notas") @Nullable String notas) {
        try {
            CloseRequest request = new CloseRequest();
            request.montoContadoEfectivo = parseDecimal(montoContadoEfectivo);
            request.montoContadoSinpe = parseDecimal(montoContadoSinpe);
            request.montoContadoTarjeta = parseDecimal(montoContadoTarjeta);
            request.notas = notas;
            Response result = close(request);

            if (!isHxRequest()) {
                return result;
            }
            if (result.getStatus() == Response.Status.OK.getStatusCode()) {
                ApiResponse<?> api = (ApiResponse<?>) result.getEntity();
                CloseResult cierre = (CloseResult) api.getData();
                return estadoOk(null,
                        cierre.advertenciaDiferencia ? "warning" : "success",
                        cierre.mensaje);
            }
            return estadoError(result.getStatus(), mensajeDe(result, MSG_SIN_SESION));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error cerrando la sesion de caja desde el formulario", e);
            return estadoError(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode(),
                    "Error cerrando la sesion de caja");
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Resolves the authenticated Users row; null when anonymous/unknown. */
    @Nullable
    private Users currentUserOrNull() {
        if (securityIdentity == null || securityIdentity.isAnonymous()
                || securityIdentity.getPrincipal() == null) {
            return null;
        }
        return loginService.findByUsername(securityIdentity.getPrincipal().getName());
    }

    private static Response unauthorized() {
        return Response.status(Response.Status.UNAUTHORIZED)
                .entity(ApiResponse.error("UNAUTHENTICATED", "Sesion no valida"))
                .build();
    }

    /** Manual mapper: CierreCaja → CierreCajaDTO (usuario flattened). */
    private static CierreCajaDTO toDTO(@Nonnull CierreCaja cc) {
        Users usuario = cc.getUsuario();
        return new CierreCajaDTO(
                cc.getId(),
                usuario != null ? usuario.getId() : null,
                usuario != null ? usuario.getUsername() : null,
                cc.getFechaApertura(),
                cc.getFechaCierre(),
                cc.getMontoInicial(),
                cc.getMontoEsperadoEfectivo(),
                cc.getMontoEsperadoSinpe(),
                cc.getMontoEsperadoTarjeta(),
                cc.getMontoContadoEfectivo(),
                cc.getMontoContadoSinpe(),
                cc.getMontoContadoTarjeta(),
                cc.getDiferencia(),
                cc.getEstado(),
                cc.getNotas());
    }

    private static <T> List<T> pageOf(@Nonnull List<T> source, int page, int size) {
        int from = Math.min(page * size, source.size());
        int to = Math.min(from + size, source.size());
        return source.subList(from, to);
    }

    private static @Nonnull <T> List<T> orEmpty(@Nullable List<T> list) {
        return list != null ? list : new ArrayList<>();
    }

    // ── W4D template-model helpers ───────────────────────────────────────────

    /** True when the call comes from HTMX (layout hx-headers carries it). */
    private boolean isHxRequest() {
        String header = routing.request().getHeader("HX-Request");
        return header != null && !"false".equalsIgnoreCase(header);
    }

    private static Response htmlOk(@Nonnull TemplateInstance template) {
        return Response.ok(template.render())
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).build();
    }

    /** Estado-caja fragment, 200, with optional OOB toast. */
    private Response estadoOk(@Nullable String errorGeneral,
                              @Nullable String toastSeverity,
                              @Nullable String toastMessage) {
        Users usuario = currentUserOrNull();
        return htmlOk(estadoInstance(usuario, errorGeneral, toastSeverity, toastMessage));
    }

    /** Estado-caja fragment redisplayed with the given status + error toast. */
    private Response estadoError(int status, @Nullable String mensaje) {
        Users usuario = currentUserOrNull();
        return Response.status(status)
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                .entity(estadoInstance(usuario, mensaje, "error", mensaje).render())
                .build();
    }

    private TemplateInstance estadoInstance(@Nullable Users usuario,
                                            @Nullable String errorGeneral,
                                            @Nullable String toastSeverity,
                                            @Nullable String toastMessage) {
        return estadoCaja
                .data("sesion", sesionView(usuario))
                .data("errorGeneral", errorGeneral)
                .data("toastSeverity", toastSeverity)
                .data("toastMessage", toastMessage);
    }

    @Nullable
    private static String mensajeDe(@Nonnull Response result, @Nonnull String fallback) {
        if (result.getEntity() instanceof ApiResponse<?> api && api.getError() != null) {
            return api.getError().getMessage();
        }
        return fallback;
    }

    /**
     * View-model of the current session state for estado-caja.html: either
     * {@code null} (render the open-session form) or a map of ready-to-render
     * strings for the active-session cards (legacy f:convertNumber/
     * convertDateTime outputs).
     */
    @Nullable
    private Map<String, Object> sesionView(@Nullable Users usuario) {
        if (usuario == null) {
            return null;
        }
        CierreCaja sesion = cierreCajaService.findSesionAbierta(usuario);
        if (sesion == null) {
            return null;
        }
        Map<String, Object> view = new HashMap<>();
        view.put("montoInicial", formatMoney(sesion.getMontoInicial()));
        view.put("fechaApertura", formatDateTime(sesion.getFechaApertura()));
        view.put("usuarioUsername",
                sesion.getUsuario() != null ? sesion.getUsuario().getUsername() : "");
        return view;
    }

    /**
     * Kit data-table model over the user's history: id/baseUrl/columnas/
     * filas/sortKey/sortDir/page/size/total/totalPages/paginas/filtros.
     * Rows are pre-formatted maps (dates/money rendered server-side, matching
     * the legacy f:convertDateTime/f:convertNumber output). Default order is
     * the service order (fechaApertura DESC); sorting is in-memory.
     */
    private Map<String, Object> tablaModel(@Nonnull Users usuario, int page, int size,
                                           @Nullable String sort, @Nonnull String dir) {
        List<CierreCaja> historial = new ArrayList<>(orEmpty(cierreCajaService.listHistorial(usuario)));

        boolean descending = "desc".equalsIgnoreCase(dir);
        Comparator<CierreCaja> comparator = switch (sort == null ? "" : sort) {
            case "fechaapertura" -> Comparator.comparing(CierreCaja::getFechaApertura,
                    Comparator.nullsLast(Date::compareTo));
            case "fechacierre" -> Comparator.comparing(CierreCaja::getFechaCierre,
                    Comparator.nullsLast(Date::compareTo));
            case "montoinicial" -> Comparator.comparing(CierreCaja::getMontoInicial,
                    Comparator.nullsLast(BigDecimal::compareTo));
            case "efectivo" -> Comparator.comparing(CierreCaja::getMontoContadoEfectivo,
                    Comparator.nullsLast(BigDecimal::compareTo));
            case "sinpe" -> Comparator.comparing(CierreCaja::getMontoContadoSinpe,
                    Comparator.nullsLast(BigDecimal::compareTo));
            case "tarjeta" -> Comparator.comparing(CierreCaja::getMontoContadoTarjeta,
                    Comparator.nullsLast(BigDecimal::compareTo));
            case "diferencia" -> Comparator.comparing(CierreCaja::getDiferencia,
                    Comparator.nullsLast(BigDecimal::compareTo));
            case "estado" -> Comparator.comparing(CierreCaja::getEstado,
                    Comparator.nullsLast(String::compareTo));
            default -> null;
        };
        if (comparator != null) {
            historial.sort(descending ? comparator.reversed() : comparator);
        }

        page = Math.max(page, 1);
        size = Math.min(Math.max(size, 1), 100);
        long total = historial.size();
        int totalPages = (int) Math.max(1L, (long) Math.ceil(total / (double) size));
        if (page > totalPages) {
            page = totalPages;
        }
        List<Integer> paginas = new ArrayList<>();
        for (int p = Math.max(1, page - 2); p <= Math.min(totalPages, page + 2); p++) {
            paginas.add(p);
        }

        List<Map<String, Object>> filas = pageOf(historial, page - 1, size).stream()
                .map(CierreCajaResource::filaView)
                .toList();

        Map<String, Object> modelo = new HashMap<>();
        modelo.put("id", "caja-historial-tabla");
        modelo.put("baseUrl", BASE_URL + "/table");
        modelo.put("columnas", columnasHistorial());
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

    /** Legacy column coverage of pages/Caja/index.xhtml (all sortable). */
    private static @Nonnull List<Map<String, Object>> columnasHistorial() {
        return List.of(
                columna("Fecha Apertura", "fechaapertura"),
                columna("Fecha Cierre", "fechacierre"),
                columna("Monto Inicial", "montoinicial"),
                columna("Efectivo", "efectivo"),
                columna("SINPE", "sinpe"),
                columna("Tarjeta", "tarjeta"),
                columna("Diferencia", "diferencia"),
                columna("Estado", "estado"));
    }

    /** Null-tolerant header builder (Map.of rejects null values). */
    private static @Nonnull Map<String, Object> columna(@Nonnull String label, @Nullable String key) {
        Map<String, Object> map = new HashMap<>();
        map.put("label", label);
        map.put("key", key);
        return map;
    }

    /** Pre-formatted row for tabla-historial.html (legacy cell semantics). */
    private static @Nonnull Map<String, Object> filaView(@Nonnull CierreCaja cc) {
        Map<String, Object> fila = new HashMap<>();
        fila.put("fechaApertura", formatDateTime(cc.getFechaApertura()));
        fila.put("fechaCierre", formatDateTime(cc.getFechaCierre()));
        fila.put("montoInicial", formatMoney(cc.getMontoInicial()));
        fila.put("efectivo", formatMoney(cc.getMontoContadoEfectivo()));
        fila.put("sinpe", formatMoney(cc.getMontoContadoSinpe()));
        fila.put("tarjeta", formatMoney(cc.getMontoContadoTarjeta()));
        BigDecimal diferencia = cc.getDiferencia();
        fila.put("diferencia", formatMoney(diferencia));
        fila.put("diferenciaClass",
                diferencia != null && diferencia.compareTo(BigDecimal.ZERO) < 0
                        ? "has-text-danger has-text-weight-bold"
                        : "has-text-success has-text-weight-bold");
        boolean abierto = "abierto".equals(cc.getEstado());
        fila.put("estado", cc.getEstado());
        fila.put("estadoClass", abierto ? "tag is-success" : "tag is-danger");
        return fila;
    }

    /** dd/MM/yyyy HH:mm — legacy f:convertDateTime pattern; null → em dash. */
    @Nullable
    private static String formatDateTime(@Nullable Date date) {
        if (date == null) {
            return null;
        }
        return new SimpleDateFormat(DATE_PATTERN).format(date);
    }

    /** Colón money with US-style grouping (deterministic across locales). */
    @Nullable
    private static String formatMoney(@Nullable BigDecimal value) {
        if (value == null) {
            return null;
        }
        java.text.NumberFormat nf = java.text.NumberFormat.getNumberInstance(Locale.US);
        nf.setMinimumFractionDigits(2);
        nf.setMaximumFractionDigits(2);
        return MONEY_SYMBOL + nf.format(value);
    }

    @Nullable
    private static BigDecimal parseDecimal(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ── Request/response payloads ────────────────────────────────────────────

    /** POST /open payload. */
    public static class OpenRequest {
        @Nullable
        public BigDecimal montoApertura;
    }

    /** POST /close payload; null amounts close as ZERO (legacy behavior). */
    public static class CloseRequest {
        @Nullable
        public BigDecimal montoContadoEfectivo;
        @Nullable
        public BigDecimal montoContadoSinpe;
        @Nullable
        public BigDecimal montoContadoTarjeta;
        @Nullable
        public String notas;
    }

    /**
     * POST /close response: the persisted closed session plus the structured
     * open-difference warning ({@code advertenciaDiferencia=true} when the
     * computed difference is non-zero) and the legacy FacesMessage text.
     */
    public static class CloseResult {
        public CierreCajaDTO cierre;
        @Nullable
        public BigDecimal totalEsperado;
        @Nullable
        public BigDecimal totalContado;
        @Nullable
        public BigDecimal diferencia;
        public boolean advertenciaDiferencia;
        @Nonnull
        public String mensaje;

        public CloseResult(CierreCajaDTO cierre, @Nullable BigDecimal totalEsperado,
                           @Nullable BigDecimal totalContado, @Nullable BigDecimal diferencia,
                           boolean advertenciaDiferencia, @Nonnull String mensaje) {
            this.cierre = cierre;
            this.totalEsperado = totalEsperado;
            this.totalContado = totalContado;
            this.diferencia = diferencia;
            this.advertenciaDiferencia = advertenciaDiferencia;
            this.mensaje = mensaje;
        }
    }
}
