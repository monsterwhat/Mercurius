package Controllers.Api.App;

import static Controllers.Api.App.AppRecursoBase.*;

import Models.ConfiguracionAplicacion;
import Models.Clientes;
import Models.ComprobantesEmitidos;
import Models.Detalles.CodigoComercial;
import Models.Detalles.DetalleServicio;
import Models.Detalles.Descuento;
import Models.Detalles.Impuesto;
import Models.Detalles.LineaDetalle;
import Models.DTO.ApiResponse;
import Models.Encabezado.Encabezado;
import Models.Encabezado.MedioPago;
import Models.Inventario;
import Models.NotaCredito;
import Models.Referencias.InformacionReferencia;
import Models.Resumen.CodigoTipoMoneda;
import Models.Resumen.ResumenFactura;
import Models.Usuarios;
import Services.AppSettingsService;
import Services.ClientService;
import Services.ComprobanteService;
import Services.ComprobantesEmitidosService;
import Services.ConsecutivoEmitidoService;
import Services.InventarioService;
import Services.LoginService;
import Services.NotaCreditoService;
import Services.Strategies.DocumentoStrategy;
import Services.Strategies.DocumentoStrategyFactory;
import Services.SustitucionComprobanteService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jboss.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Devoluciones module for the NEW Qute/HTMX app surface (plan task T32):
 * JSON API + HTMX action endpoints replacing the legacy JSF bean
 * {@code Controllers.DevolucionesController} (@ViewScoped, deleted by this
 * task together with secured/pages/Devoluciones/index.xhtml).
 *
 * <p><b>Behavior parity contract</b> (1:1 port; receipts in
 * .omo/evidence/t32/flow-and-guards.md):</p>
 * <ul>
 *   <li>Search ({@link #facturas}): consecutivo mode filters
 *       {@link ComprobantesEmitidosService#listAll()} by
 *       {@code encabezado.numeroConsecutivo.contains(q)}; cliente mode keeps
 *       the facturas whose receptor name is CONTAINED IN the query (the
 *       legacy inverted-contains semantics are preserved verbatim).</li>
 *   <li>Initiate ({@link #initiate}): validates motivo/lines and computes
 *       {@code totalDevolucion = Σ precioUnitario × cantidadDevolver} — the
 *       exact legacy {@code recalcularTotal()} formula.</li>
 *   <li>Authorize ({@link #authorize}): credential verification delegates to
 *       {@link LoginService#findByUsername} + {@link LoginService#
 *       verifyPassword} EXACTLY like {@code AppAuthResource.supervisorAuthorize}
 *       (T13) — including the explicit disabled-user check and the same audit
 *       alert texts. SessionController is NOT injected (JSF-bound bean).
 *       ANY failure answers 401 with ZERO side effects: no NotaCredito row,
 *       no Inventario movement, no comprobante, no Hacienda send.</li>
 *   <li>Processing (success path): NotaCredito row + per-line Inventario
 *       movement ({@code articulo=null}, {@code cantidad=cantidadDevolver.negate()},
 *       {@code tipoMovimiento="Devolucion"}, processed=true, via
 *       {@link InventarioService#create} — NOT createWithStock, mirroring the
 *       legacy call exactly) + the Hacienda Nota de Crédito Electrónica built
 *       through {@link DocumentoStrategyFactory#forCode("03")} UNCHANGED:
 *       same consecutivo format, clave generation, line cloning with the
 *       6-dp factor, resumen buckets incl. desglose and totalIVADevuelto,
 *       InformacionReferencia tipoDocumento "01", PENDIENTE states,
 *       {@code enviarComprobanteAHacienda}. An NC-generation failure is caught
 *       and alerted ("Error NC") while the base devolucion still succeeds —
 *       legacy swallow semantics.</li>
 *   <li><b>Processing order (two phases, as the POS sale in 5dbcee8):</b>
 *       {@link #authorize} persists and CONFIRMS in one short transaction
 *       ({@link #procesarDevolucion}, the region that used to carry
 *       {@code @Transactional}) and only then talks to Hacienda
 *       ({@link #enviarNcAHacienda}), with no transaction open. Envelopes,
 *       status codes and Hacienda states (ACEPTADO / RECHAZADO / PENDIENTE) are
 *       unchanged; what changed is that the send no longer runs while the
 *       pessimistic lock on the credit-note consecutive ("02") is held.</li>
 *   <li>Double-devolucion guard: when an active NotaCredito already exists
 *       for the factura the endpoint answers 409 ALREADY_RETURNED (dispatch
 *       requirement; precedent TributacionResource idempotency via
 *       {@link NotaCreditoService#listPorComprobante}).</li>
 *   <li>DELIBERATE DEVIATION (no existía en el legacy): una factura
 *       RECHAZADA por Hacienda devuelve 409 DOCUMENTO_RECHAZADO y no genera
 *       nada. El Art. 19 del Reglamento de Comprobantes Electrónicos obliga a
 *       re-emitir el comprobante en vez de emitir la nota de crédito —"Para
 *       efectos tributarios no debe realizarse la respectiva nota de crédito"—,
 *       y la re-emisión vive en
 *       {@link Services.ComprobantesEmitidosCorrectionService} con el código
 *       16 de la nota 9. La nota de crédito de este recurso queda para las
 *       devoluciones reales sobre comprobantes aceptados.</li>
 * </ul>
 *
 * <p><b>Authorization:</b> {@code admin} or {@code facturacion}, mirroring the
 * legacy page's availability to the facturación area. The /api/app/* surface
 * additionally requires any authenticated user through the T13 permission
 * policy; mutating POSTs are CSRF-gated by quarkus-rest-csrf.</p>
 *
 * <p><b>NO real Hacienda sends from tests:</b> tests either replace
 * {@link ComprobanteService} with {@code @InjectMock}, or —where the real
 * service must run to prove the two-phase order— stub
 * {@link Services.HaciendaServiceFacade}, which is the boundary
 * {@code enviarComprobanteAHacienda} calls; production behavior is
 * untouched.</p>
 */
@Path("/api/app/devoluciones")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"admin", "facturacion"})
@Tag(name = "App - Devoluciones")
public class DevolucionesResource {

    private static final Logger LOG = Logger.getLogger(DevolucionesResource.class);

    /** Hacienda document code for Nota de Crédito Electrónica ("02"). */
    public static final String CODIGO_NC = "02";

    /**
     * {@code Situacion} con el que este módulo firma SIEMPRE la NC
     * (posición 42 de la clave): "1" = normal, envío inmediato.
     *
     * <p>No es parametrizable a propósito. La venta sí sondea conectividad y
     * puede salir con {@code situacion 3} (offline, Art. 21 párr. 3), pero acá
     * la firma siempre fue "1"; cambiarlo alteraría la clave de un documento ya
     * emitido. Se declara como constante para que la clave y el sobre de la
     * bandeja de reintento no puedan divergir: se usan las dos en el mismo
     * lugar.</p>
     */
    private static final String SITUACION_NC = "1";

    /** Legacy p:dataTable rows=10 on both search results and historial. */
    public static final int DEFAULT_PAGE_SIZE = 10;

    private static final int MAX_PAGE_SIZE = 100;

    @Nonnull
    @Inject
    ComprobantesEmitidosService comprobantesService;

    @Nonnull
    @Inject
    NotaCreditoService notaCreditoService;

    @Nonnull
    @Inject
    InventarioService inventarioService;

    @Nonnull
    @Inject
    ClientService clientService;

    @Nonnull
    @Inject
    AppSettingsService appSettingsService;

    @Nonnull
    @Inject
    DocumentoStrategyFactory strategyFactory;

    @Nonnull
    @Inject
    Services.HaciendaSigner haciendaSigner;

    @Nonnull
    @Inject
    ComprobanteService comprobanteService;

    @Nonnull
    @Inject
    ConsecutivoEmitidoService consecutivoEmitidoService;

    @Nonnull
    @Inject
    LoginService loginService;

    @Nonnull
    @Inject
    SustitucionComprobanteService sustitucionComprobanteService;

    /**
     * Bandeja de Art. 21 párr. 3. La usa SOLO la fase (b)
     * ({@link #enviarNcAHacienda}), y únicamente cuando el envío inmediato no
     * prosperó: el XML firmado de la NC queda con su vencimiento de dos días
     * hábiles para que el documento no se pierda ni salga del plazo legal.
     */
    @Nonnull
    @Inject
    Services.EnvioFueraLineaService envioFueraLineaService;

    @Inject
    @Nonnull
    SecurityIdentity identity;

    /** Request headers (quarkus-rest injectable) — source of HX-Request. */
    @Context
    @Nonnull
    HttpHeaders httpHeaders;

    /**
     * Same root-path the _kit fragments resolve via
     * {config:['quarkus.http.root-path']} in Qute; needed here because the
     * HTMX fragments are built in Java, where Qute expressions do not run.
     */
    @ConfigProperty(name = "quarkus.http.root-path", defaultValue = "/")
    String rootPath;

    // ════════════════════════════════════════════════════════════════════
    // Read side: returnable invoices + line detail
    // ════════════════════════════════════════════════════════════════════

    /**
     * Search of returnable invoices — legacy {@code buscarFactura()} parity.
     * With the {@code HX-Request} header returns ONLY the result-rows HTML
     * fragment; otherwise the paged JSON envelope.
     *
     * @param tipo "consecutivo" (default) or "cliente", mirroring the legacy
     *             radio buttons
     * @param q    search criterion (legacy WARNed on blank; here 400)
     */
    @GET
    @Path("/facturas")
    @Operation(summary = "Search returnable invoices (legacy buscarFactura parity)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Paged invoice rows (or HTML fragment twin)"),
        @APIResponse(responseCode = "400", description = "Blank search criterion"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response facturas(
            @QueryParam("tipo") @DefaultValue("consecutivo") String tipo,
            @QueryParam("q") @Nullable String q,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("10") int size) {
        try {
            if (q == null || q.trim().isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(ApiResponse.error("VALIDATION_ERROR",
                                "Ingrese un criterio de busqueda"))
                        .build();
            }

            List<Map<String, Object>> rows = buscarFacturas(tipo, q.trim());

            int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
            int totalPages = (int) Math.max(1L, ((long) rows.size() + safeSize - 1) / safeSize);
            int safePage = Math.min(Math.max(page, 1), totalPages);
            int from = Math.min((safePage - 1) * safeSize, rows.size());
            int to = Math.min(from + safeSize, rows.size());
            List<Map<String, Object>> pagina = new ArrayList<>(rows.subList(from, to));

            if (isHxRequest()) {
                return htmlOk(facturasFragment(pagina));
            }
            return Response.ok(ApiResponse.ok(new PagedFacturas(pagina, rows.size(),
                    safePage, safeSize, totalPages))).build();
        } catch (RuntimeException e) {
            LOG.warn("Error buscando facturas para devolucion", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error buscando las facturas"))
                    .build();
        }
    }

    /**
     * Line detail of one factura for the selection UX — legacy
     * {@code seleccionarFactura()} parity (one row per LineaDetalle with its
     * original quantity). Lines are identified by their POSITION in
     * {@code detalles.lineasDetalle}; that index rides back into initiate /
     * authorize as {@code lineaNumero}. With {@code HX-Request} returns the
     * selection form fragment; otherwise JSON.
     */
    @GET
    @Path("/{id}/lineas")
    @Operation(summary = "Line detail of a factura for the devolucion selection UX")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Lines (JSON or selection-form fragment)"),
        @APIResponse(responseCode = "404", description = "Unknown factura"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response lineas(@PathParam("id") long id) {
        try {
            ComprobantesEmitidos factura = comprobantesService.find(id);
            if (factura == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("NOT_FOUND", "No se encontró la factura solicitada"))
                        .build();
            }
            List<LineaRow> rows = lineaRows(factura);
            if (isHxRequest()) {
                return htmlOk(lineasFragment(factura, rows));
            }
            return Response.ok(ApiResponse.ok(new FacturaDetalle(
                    facturaHeader(factura), rows))).build();
        } catch (RuntimeException e) {
            LOG.warn("Error obteniendo las lineas de la factura " + id, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error obteniendo las líneas"))
                    .build();
        }
    }

    /**
     * Authorize-dialog body fragment (kit modal contract: the modal fetches
     * this with hx-get when it opens). Renders the credentials form whose
     * submit hx-posts to {@link #authorize} including the outer
     * {@code #devolucion-form} fields (selected quantities + motivo).
     */
    @GET
    @Path("/{id}/authform")
    @Operation(summary = "Authorize modal body fragment (credentials form)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Credentials form fragment"),
        @APIResponse(responseCode = "404", description = "Unknown factura"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response authform(@PathParam("id") long id) {
        try {
            ComprobantesEmitidos factura = comprobantesService.find(id);
            if (factura == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(ApiResponse.error("NOT_FOUND", "No se encontró la factura solicitada"))
                        .build();
            }
            return htmlOk(authformFragment(id));
        } catch (RuntimeException e) {
            LOG.warn("Error renderizando el formulario de autorización", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error renderizando el formulario"))
                    .build();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Mutations: initiate (validate + total) and authorize (process)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Validation preview of a devolucion — legacy guards +
     * {@code recalcularTotal()} without writing anything. Accepts the same
     * form encoding as authorize so the page can confirm totals live.
     */
    @POST
    @Path("/initiate")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Operation(summary = "Validate a devolucion selection and compute its total (no writes)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Selection valid; total computed"),
        @APIResponse(responseCode = "400", description = "Validation failure"),
        @APIResponse(responseCode = "404", description = "Unknown factura"),
        @APIResponse(responseCode = "409", description = "Factura rejected by Hacienda (Art. 19: re-issue, no credit note)"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response initiate(
            @FormParam("facturaId") @Nullable Long facturaId,
            @FormParam("motivo") @Nullable String motivo,
            @FormParam("lineaNumero") @Nullable List<String> lineaNumero,
            @FormParam("lineaCantidad") @Nullable List<String> lineaCantidad) {
        try {
            if (facturaId == null) {
                return badRequest("Seleccione una factura primero");
            }
            ComprobantesEmitidos factura = comprobantesService.find(facturaId);
            if (factura == null) {
                return notFound();
            }
            List<LineaSeleccion> seleccion;
            try {
                seleccion = parseSelecciones(factura, lineaNumero, lineaCantidad);
            } catch (IllegalArgumentException e) {
                return badRequest(e.getMessage());
            }
            Response guard = validarGuardias(factura, motivo, seleccion);
            if (guard != null) {
                return guard;
            }
            BigDecimal total = totalDevolucion(seleccion);
            InitiateResult resultado = new InitiateResult(facturaId, total, seleccion.size(),
                    "Selección válida");
            if (isHxRequest()) {
                return htmlOk("<span class=\"total-badge\">Total a devolver: ₡"
                        + total.setScale(2, RoundingMode.HALF_UP).toPlainString() + "</span>");
            }
            return Response.ok(ApiResponse.ok(resultado)).build();
        } catch (RuntimeException e) {
            LOG.warn("Error validando la devolucion", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error validando la devolución"))
                    .build();
        }
    }

    /**
     * Supervisor-authorized processing of a devolucion — the ported
     * {@code authorize() → procesarDevolucion()} pair. Credential failures
     * answer 401 with ZERO side effects; validation failures 400/404/409
     * also write nothing (except the legacy "Autorización Exitosa" alerta
     * which the legacy bean already wrote before its own guards ran).
     *
     * <p><b>Dos fases, como la venta del POS (5dbcee8).</b> La fase (a)
     * ({@link #procesarDevolucion}) corre dentro de UNA transacción —la región
     * que antes llevaba {@code @Transactional} sobre este método— y TERMINA
     * confirmando; la fase (b) ({@link #enviarNcAHacienda}) habla con Hacienda
     * después, sin ninguna transacción abierta.
     *
     * <p><b>Por qué el envío salió de esa transacción:</b> el consecutivo de la
     * NC se numera con {@code PESSIMISTIC_WRITE} sobre la fila de
     * {@code ConsecutivoEmitido} del tipo "02", así que el bloqueo dura lo que
     * dure la transacción que lo pide. Con el envío HTTPS y su sondeo de estado
     * adentro, cada devolución concurrente serializaba detrás de la E/S de red
     * de la anterior con esa fila bloqueada y un punto del pool tomado durante
     * todo el sondeo; con el pool en 20, veinte devoluciones simultáneas bastaban
     * para agotarlo. Ahora cada devolución libera el consecutivo al confirmarse
     * y habla con Hacienda por su cuenta —que además es el orden correcto: el
     * documento es un hecho local y la comunicación es un trámite posterior con
     * cola de reintento propia (Art. 21 párr. 3).</p>
     *
     * <p><b>Nada observable cambió:</b> mismos sobres y mismo orden de
     * campos, mismos 401/400/404/409/500 con sus mensajes, y los estados de
     * Hacienda (ACEPTADO / RECHAZADO / PENDIENTE) los sigue escribiendo
     * {@link ComprobanteService#enviarComprobanteAHacienda}, que abre sus
     * propias transacciones cortas por sello. Tampoco la regla del Art. 19: el
     * guard de {@link #validarGuardias} corre dentro de la fase (a), antes de
     * cualquier escritura, así que un comprobante rechazado por Hacienda
     * sigue contestando 409 DOCUMENTO_RECHAZADO sin generar nada.</p>
     *
     * <p>La demarcación es explícita
     * ({@link QuarkusTransaction#requiringNew()}) y no el interceptor de
     * {@code @Transactional} a propósito: si el método fuera transaccional, la
     * transacción seguiría viva durante el sondeo de Hacienda, que es
     * justamente lo que este cambio elimina.</p>
     */
    @POST
    @Path("/{id}/authorize")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Operation(summary = "Authorize (supervisor credentials) and process the devolucion")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Devolucion processed; NC summary returned"),
        @APIResponse(responseCode = "400", description = "Validation failure (motivo/lines/quantities)"),
        @APIResponse(responseCode = "401", description = "Invalid supervisor credentials — zero side effects"),
        @APIResponse(responseCode = "404", description = "Unknown factura"),
        @APIResponse(responseCode = "409", description = "A credit note already exists, or the factura is rejected by Hacienda"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response authorize(
            @PathParam("id") long id,
            @FormParam("username") @Nullable String username,
            @FormParam("password") @Nullable String password,
            @FormParam("motivo") @Nullable String motivo,
            @FormParam("lineaNumero") @Nullable List<String> lineaNumero,
            @FormParam("lineaCantidad") @Nullable List<String> lineaCantidad) {

        // ── Credentials FIRST, exactly like AppAuthResource.supervisorAuthorize
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return invalidCredentials();
        }
        Usuarios authUser;
        try {
            authUser = loginService.findByUsername(username);
            if (authUser == null) {
                                LOG.info("Intento con usuario inexistente: " + username + " | user=" + String.valueOf(currentUser()) + " | source=" + "DevolucionesResource.authorize()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
                return invalidCredentials();
            }
            if (!Boolean.TRUE.equals(authUser.getStatus())) {
                                LOG.info("Intento con usuario deshabilitado: " + username + " | user=" + String.valueOf(currentUser()) + " | source=" + "DevolucionesResource.authorize()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
                return invalidCredentials();
            }
            if (!loginService.verifyPassword(password, authUser.getPassword())) {
                                LOG.info("Contraseña incorrecta de: " + username + " | user=" + String.valueOf(currentUser()) + " | source=" + "DevolucionesResource.authorize()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
                return invalidCredentials();
            }
        } catch (RuntimeException e) {
            // SessionController.authorizeAction parity: an error inside the
            // credential check is alerted and blocks processing.
                        LOG.warn("Error en authorizeAction: " + e.getMessage() + " | user=" + String.valueOf(currentUser()) + " | source=" + "DevolucionesResource.authorize()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return invalidCredentials();
        }

        String authorizedBy = authUser.getUsername();

        // ── Legacy authorize(): the exitosa alerta fires BEFORE the
        //    procesarDevolucion guards, so failed validations still carry it.
                LOG.info("Devolución autorizada por: " + authorizedBy + " | user=" + String.valueOf(currentUser()) + " | source=" + "DevolucionesResource.authorize()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));

        // ── FASE (a): todo lo que escribe, en una transacción que CONFIRMA
        //    al terminar. Requiere-nueva, no el interceptor de
        //    @Transactional sobre este método: con el interceptor la
        //    transacción seguiría viva durante el sondeo de Hacienda de la
        //    fase (b), que es justo el bloqueo que este cambio libera.
        DevolucionProcesada procesada;
        try {
            procesada = QuarkusTransaction.requiringNew().call(() -> procesarDevolucion(
                    id, motivo, lineaNumero, lineaCantidad, authorizedBy));
        } catch (RuntimeException e) {
            // Legacy catch: alert + error message; the rollback undoes the
            // partial writes and the 500 envelope is the one it always was.
                        LOG.warn("Error al procesar devolucion: " + e.getMessage() + " | user=" + String.valueOf(currentUser()) + " | source=" + "DevolucionesResource.procesarDevolucion()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            LOG.warn("Error procesando la devolucion de la factura " + id, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR",
                            "Error al procesar devolucion: " + e.getMessage()))
                    .build();
        }

        // Un guard de la fase (a) cortó: 400/404/409, sin escrituras y sin
        // envío. Se devuelve tal cual, con el mismo sobre de siempre.
        if (procesada.rechazo() != null) {
            return procesada.rechazo();
        }

        // ── FASE (b): Hacienda, ya con la transacción de arriba confirmada.
        //    El consecutivo de la NC está liberado y la NC escrita; que el
        //    sondeo HTTPS tarde segundos ya no retiene la numeración de las
        //    demás devoluciones. No relanza (ver enviarNcAHacienda).
        NcElectronicaResultado nc = procesada.nc();
        enviarNcAHacienda(nc);

        NcSummary summary = new NcSummary(nc.facturaConsecutivo(), nc.clave(), nc.consecutivo(),
                procesada.totalDevolucion(), procesada.motivoFinal(), nc.generada(), nc.mensaje(),
                printUrl(nc.clave()));

        if (isHxRequest()) {
            return htmlOk(ncSummaryFragment(summary));
        }
        return Response.ok(ApiResponse.ok(summary)).build();
    }

    /**
     * FASE (a) — guarda y CONFIRMA la devolucion: los guards del legacy, la
     * fila de {@link NotaCredito}, los movimientos de inventario y el armado
     * de la NC electrónica. No habla con Hacienda.
     *
     * <p>Es exactamente la región que antes llevaba {@code @Transactional} sobre
     * {@link #authorize}, con el mismo orden, los mismos mensajes y el mismo
     * alcance: los guards siguen corriendo antes de cualquier escritura, el
     * guard del Art. 19 sigue cortando con 409 y el fallo de armado de la NC
     * sigue tragándose ("Error NC") con la devolución base confirmada.</p>
     *
     * <p>Devuelve o bien el {@code Response} de un guard que cortó, o bien la
     * NC con los datos que la fase (b) necesita. La transacción se confirma al
     * salir de este método, que es donde se libera el bloqueo pesimista del
     * consecutivo de la NC.</p>
     *
     * <p>La excepción no se captura acá: {@link QuarkusTransaction} marca el
     * rollback y la relanza, y {@link #authorize} la traduce al mismo 500 de
     * siempre.</p>
     */
    private @Nonnull DevolucionProcesada procesarDevolucion(
            long id,
            @Nullable String motivo,
            @Nullable List<String> lineaNumero,
            @Nullable List<String> lineaCantidad,
            @Nonnull String authorizedBy) {

        // ── procesarDevolucion guards (all BEFORE any domain write)
        ComprobantesEmitidos facturaSeleccionada = comprobantesService.find(id);
        if (facturaSeleccionada == null) {
            return DevolucionProcesada.rechazada(notFound());
        }
        List<LineaSeleccion> seleccion;
        try {
            seleccion = parseSelecciones(facturaSeleccionada, lineaNumero, lineaCantidad);
        } catch (IllegalArgumentException e) {
            return DevolucionProcesada.rechazada(badRequest(e.getMessage()));
        }
        Response guard = validarGuardias(facturaSeleccionada, motivo, seleccion);
        if (guard != null) {
            return DevolucionProcesada.rechazada(guard);
        }
        List<NotaCredito> previas = notaCreditoService.listPorComprobante(id);
        if (previas != null && !previas.isEmpty()) {
            return DevolucionProcesada.rechazada(Response.status(Response.Status.CONFLICT)
                    .entity(ApiResponse.error("ALREADY_RETURNED",
                            "La factura ya tiene una nota de credito registrada"))
                    .build());
        }

        String motivoFinal = motivo.trim();
        BigDecimal totalDevolucion = totalDevolucion(seleccion);
        Usuarios currentUser = currentUser();

        // ── NotaCredito row (legacy field-for-field)
        Clientes notaCliente = buscarClienteDeFactura(facturaSeleccionada);
        NotaCredito nota = new NotaCredito();
        nota.setComprobanteOriginal(facturaSeleccionada);
        nota.setFecha(new Date());
        nota.setMotivo(motivoFinal);
        nota.setMontoTotal(totalDevolucion);
        nota.setCliente(notaCliente);
        nota.setUsuario(currentUser != null ? currentUser.getUsername() : authorizedBy);
        nota.setStatus(true);
        nota.setHaciendaEstado("PENDIENTE");
        notaCreditoService.create(nota);

        // ── Inventory movements (legacy verbatim: articulo=null,
        //    cantidad negated, plain create() — see evidence notes for
        //    why createWithStock would change behavior).
        String consecutivoOriginal = facturaSeleccionada.getEncabezado() != null
                ? facturaSeleccionada.getEncabezado().getNumeroConsecutivo() : null;
        for (LineaSeleccion sel : seleccion) {
            Inventario inv = new Inventario();
            inv.setArticulo(null);
            inv.setCantidad(sel.cantidadDevolver().negate());
            inv.setTipoMovimiento("Devolucion");
            inv.setUsuario(currentUser);
            inv.setFechaMovimiento(new Date());
            inv.setNotas("Devolucion factura: "
                    + consecutivoOriginal + " - " + motivoFinal);
            inv.setStatus(true);
            inv.setProcessed(true);
            inventarioService.create(inv);
        }

        // ── Hacienda Nota de Credito Electronica (strategy UNCHANGED).
        //    Legacy swallows failures here with an "Error NC" alerta and
        //    the base devolucion still succeeds.
        NcElectronicaResultado nc = generarNcElectronica(
                facturaSeleccionada, seleccion, motivoFinal, totalDevolucion, authorizedBy);

                LOG.info("Nota de credito creada por " + totalDevolucion + " - " + motivoFinal + " | user=" + String.valueOf(currentUser) + " | source=" + "DevolucionesResource.procesarDevolucion()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));

        return DevolucionProcesada.procesada(motivoFinal, totalDevolucion, nc);
    }

    // ════════════════════════════════════════════════════════════════════
    // Search + validation helpers (legacy parity)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Legacy {@code buscarFactura()} filtering, verbatim: consecutivo mode =
     * contains on numeroConsecutivo over ALL comprobantes; cliente mode =
     * keep facturas whose receptor name is contained IN the criterion (the
     * legacy inverted contains). No status filter existed in legacy — none
     * added here.
     */
    private @Nonnull List<Map<String, Object>> buscarFacturas(@Nonnull String tipo, @Nonnull String criterio) {
        List<ComprobantesEmitidasRow> encontradas = new ArrayList<>();
        if ("cliente".equals(tipo)) {
            List<Clientes> clients = clientService.searchByName(criterio);
            if (clients != null && !clients.isEmpty()) {
                String needle = criterio.toLowerCase(Locale.ROOT);
                for (ComprobantesEmitidos f : orEmpty(comprobantesService.listAll())) {
                    Encabezado enc = f.getEncabezado();
                    if (enc == null || enc.getReceptor() == null || enc.getReceptor().getNombre() == null) {
                        continue; // legacy removeIf drops these
                    }
                    if (needle.contains(enc.getReceptor().getNombre().toLowerCase(Locale.ROOT))) {
                        encontradas.add(toRow(f));
                    }
                }
            }
        } else { // "consecutivo" (legacy default)
            for (ComprobantesEmitidos f : orEmpty(comprobantesService.listAll())) {
                Encabezado enc = f.getEncabezado();
                if (enc != null && enc.getNumeroConsecutivo() != null
                        && enc.getNumeroConsecutivo().contains(criterio)) {
                    encontradas.add(toRow(f));
                }
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>(encontradas.size());
        for (ComprobantesEmitidasRow r : encontradas) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", r.id());
            row.put("consecutivo", r.consecutivo());
            row.put("fechaEmision", r.fechaEmision());
            row.put("cliente", r.cliente());
            row.put("total", r.total());
            rows.add(row);
        }
        return rows;
    }

    private static ComprobantesEmitidasRow toRow(@Nonnull ComprobantesEmitidos f) {
        Encabezado enc = f.getEncabezado();
        return new ComprobantesEmitidasRow(
                f.getId(),
                enc != null ? enc.getNumeroConsecutivo() : null,
                enc != null ? enc.getFechaEmision() : null,
                enc != null && enc.getReceptor() != null ? enc.getReceptor().getNombre() : null,
                f.getResumen() != null ? f.getResumen().getTotalComprobante() : null);
    }

    /** One selectable line: position index + display data (legacy LineaDevolucion). */
    private static @Nonnull List<LineaRow> lineaRows(@Nonnull ComprobantesEmitidos factura) {
        List<LineaRow> rows = new ArrayList<>();
        DetalleServicio detalles = factura.getDetalles();
        if (detalles != null && detalles.getLineasDetalle() != null) {
            List<LineaDetalle> lineas = detalles.getLineasDetalle();
            for (int i = 0; i < lineas.size(); i++) {
                LineaDetalle linea = lineas.get(i);
                rows.add(new LineaRow(i,
                        linea.getNumeroLinea() != null ? linea.getNumeroLinea() : i,
                        linea.getDetalle(),
                        linea.getCantidad(),
                        linea.getPrecioUnitario()));
            }
        }
        return rows;
    }

    /**
     * Parses the parallel {@code lineaNumero}/{@code lineaCantidad} arrays
     * into selected lines ({@code cantidad > 0} IS the selection — the legacy
     * required BOTH checkbox and positive quantity, so quantity-only is
     * semantically equivalent). Unknown indices or non-numeric values are
     * rejected with IllegalArgumentException (→ 400).
     */
    private @Nonnull List<LineaSeleccion> parseSelecciones(
            @Nonnull ComprobantesEmitidos factura,
            @Nullable List<String> lineaNumero,
            @Nullable List<String> lineaCantidad) throws IllegalArgumentException {
        List<LineaSeleccion> seleccion = new ArrayList<>();
        if (lineaNumero == null || lineaCantidad == null) {
            return seleccion;
        }
        if (lineaNumero.size() != lineaCantidad.size()) {
            throw new IllegalArgumentException(
                    "Las cantidades no coinciden con las líneas de la factura");
        }
        DetalleServicio detalles = factura.getDetalles();
        List<LineaDetalle> lineas = detalles != null && detalles.getLineasDetalle() != null
                ? detalles.getLineasDetalle() : Collections.emptyList();
        for (int i = 0; i < lineaNumero.size(); i++) {
            BigDecimal cantidad;
            try {
                cantidad = new BigDecimal(lineaCantidad.get(i).trim());
            } catch (NumberFormatException | NullPointerException e) {
                throw new IllegalArgumentException(
                        "Cantidad inválida para la línea " + lineaNumero.get(i));
            }
            if (cantidad.compareTo(BigDecimal.ZERO) <= 0) {
                continue; // unselected row (default 0 inputs ride along)
            }
            int indice;
            try {
                indice = Integer.parseInt(lineaNumero.get(i).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Índice de línea inválido: " + lineaNumero.get(i));
            }
            if (indice < 0 || indice >= lineas.size()) {
                throw new IllegalArgumentException("La línea indicada no existe en la factura");
            }
            LineaDetalle original = lineas.get(indice);
            if (original.getCantidad() == null
                    || cantidad.compareTo(original.getCantidad()) > 0) {
                throw new IllegalArgumentException(String.format(Locale.ROOT,
                        "La cantidad a devolver (%s) no puede ser mayor a la original (%s)",
                        cantidad.toPlainString(),
                        original.getCantidad() == null ? "0" : original.getCantidad().toPlainString()));
            }
            seleccion.add(new LineaSeleccion(indice, original, cantidad));
        }
        return seleccion;
    }

    /** Legacy procesarDevolucion guards G5-G8 (message texts preserved). */
    private @Nullable Response validarGuardias(
            @Nonnull ComprobantesEmitidos factura,
            @Nullable String motivo,
            @Nonnull List<LineaSeleccion> seleccion) {
        // G0 — DELIBERATE DEVIATION del legacy (no existía). Art. 19 del
        // Reglamento de Comprobantes Electrónicos: un comprobante RECHAZADO por
        // Hacienda no tiene validez fiscal y no se corrige con nota de crédito
        // ("Para efectos tributarios no debe realizarse la respectiva nota de
        // crédito"); lo que corresponde es re-emitir el comprobante
        // (ComprobantesEmitidosCorrectionService, código 16 "Sustituye
        // comprobante electrónico rechazado"). Este módulo sigue siendo la vía
        // de las devoluciones reales, sólo que sobre comprobantes aceptados.
        if (sustitucionComprobanteService.fueRechazadoPorHacienda(factura)) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(ApiResponse.error("DOCUMENTO_RECHAZADO",
                            "La factura fue rechazada por Hacienda y no tiene validez fiscal, "
                            + "por lo que no admite nota de crédito. Debe reemitirse el comprobante "
                            + "que la sustituye (Art. 19)."))
                    .build();
        }
        if (motivo == null || motivo.trim().isEmpty()) {
            return badRequest("Ingrese el motivo de la devolucion");
        }
        boolean haySeleccion = false;
        for (LineaSeleccion sel : seleccion) {
            if (sel.cantidadDevolver() != null
                    && sel.cantidadDevolver().compareTo(BigDecimal.ZERO) > 0) {
                haySeleccion = true;
                break;
            }
        }
        if (!haySeleccion) {
            return badRequest("Seleccione al menos un articulo y especifique cantidad a devolver");
        }
        for (LineaSeleccion sel : seleccion) {
            if (sel.original().getPrecioUnitario() == null) {
                return badRequest("La línea " + sel.indice()
                        + " no tiene precio unitario y no puede devolverse");
            }
        }
        return null;
    }

    /** Legacy recalcularTotal(): Σ precioUnitario × cantidadDevolver. */
    private static @Nonnull BigDecimal totalDevolucion(@Nonnull List<LineaSeleccion> seleccion) {
        BigDecimal total = BigDecimal.ZERO;
        for (LineaSeleccion sel : seleccion) {
            total = total.add(sel.original().getPrecioUnitario().multiply(sel.cantidadDevolver()));
        }
        return total;
    }

    private @Nullable Clientes buscarClienteDeFactura(@Nonnull ComprobantesEmitidos factura) {
        if (factura.getEncabezado() != null
                && factura.getEncabezado().getReceptor() != null
                && factura.getEncabezado().getReceptor().getNombre() != null) {
            List<Clientes> found = clientService.searchByName(
                    factura.getEncabezado().getReceptor().getNombre());
            if (found != null && !found.isEmpty()) {
                return found.get(0);
            }
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════════
    // NC electrónica generation — verbatim port of the legacy block
    // ════════════════════════════════════════════════════════════════════

    /** Outcome carrier of the NC-electrónica block (legacy swallow semantics). */
    private record NcElectronicaResultado(boolean generada, @Nullable String clave,
                                          @Nullable String consecutivo, @Nullable String mensaje,
                                          @Nullable String facturaConsecutivo,
                                          @Nullable EnvioNc envio) {
        /**
         * La NC no llegó a persistirse: no hay clave, ni consecutivo, ni
         * documento que enviar, y {@code ncGenerada} es false.
         */
        static NcElectronicaResultado sinDocumento(@Nullable String mensaje,
                                                   @Nullable String facturaConsecutivo) {
            return new NcElectronicaResultado(false, null, null, mensaje,
                    facturaConsecutivo, null);
        }
    }

    /**
     * Contexto de la fase (b) de la NC, capturado en la fase (a).
     *
     * <p>Contenedor de datos, ni entidad ni efectos: la clave ya quedó escrita
     * con el {@code situacion} y el consecutivo ya quedó escrito con la
     * sucursal y la terminal, así que la fase (b) no puede recalcular nada de
     * eso — solo puede usarlo, y en ese mismo orden. Es null únicamente cuando
     * la NC no llegó a persistirse.</p>
     */
    private record EnvioNc(@Nonnull ComprobantesEmitidos comprobante,
                           @Nonnull String tipoDocumento,
                           @Nonnull String sucursal,
                           @Nonnull String terminal,
                           @Nonnull String situacion) {}

    // ════════════════════════════════════════════════════════════════════
    // FASE (b): envío a Hacienda, ya fuera de la transacción
    // ════════════════════════════════════════════════════════════════════

    /**
     * FASE (b) — comunica a Hacienda una NC YA CONFIRMADA, sin transacción
     * abierta.
     *
     * <p>Se llama después del commit de {@link #procesarDevolucion}, y esa es
     * toda la razón de existir: el envío (HTTPS + sondeo de estado) ocurre
     * fuera de la transacción que retiene el bloqueo pesimista del consecutivo,
     * así las devoluciones concurrentes no se serializan detrás de la E/S de
     * red de la anterior. Es el mismo diseño y la misma referencia que
     * {@link ComprobanteService#enviarComprobanteCreado} para la venta del POS
     * (commit 5dbcee8).</p>
     *
     * <p><b>Los tres finales se conservan tal cual</b>, porque los estados los
     * sigue escribiendo {@link ComprobanteService#enviarComprobanteAHacienda}
     * (que abre su propia transacción corta por sello, sin E/S de red):
     * ACEPTADO con su fecha de respuesta, RECHAZADO con su motivo de fondo, o
     * PENDIENTE si el transporte o el preflight fallaron. Lo que se agrega es el
     * reintento con cola: si el envío no prosperó, el XML firmado se encola en
     * {@link Services.EnvioFueraLineaService} con su vencimiento de Art. 21
     * párr. 3, igual que hace la venta. Sin eso, una NC rechazada o con fallo de
     * transporte se quedaba en PENDIENTE sin ningún plazo legal que la
     * persiguiera.</p>
     *
     * <p><b>Por qué no relanza nunca:</b> la devolución ya está registrada y la
     * NC ya está escrita y firmada —una excepción acá no puede deshacer ninguna
     * de las dos—, y convertirla en error HTTP haría que el supervisor creyera
     * que no se devolvió algo que sí se devolvió. Se registra y el documento
     * queda pendiente del lote de 48 h y de la bandeja de Art. 21 párr. 3. El
     * sobre de respuesta tampoco lo menciona: {@code ncGenerada} y
     * {@code mensaje} son exactamente los de siempre, porque el destino de la
     * NC no cambia —la venta tampoco lo cambió al partir la fase.</p>
     *
     * <p>El sondeo de conectividad NO se consulta acá, a diferencia de la venta:
     * esta NC se firma con {@code situacion 1} fijo desde siempre (posición 42
     * de la clave), así que el camino offline con {@code situacion 3} no existe
     * en este recurso y no se inventa.</p>
     */
    private void enviarNcAHacienda(@Nonnull NcElectronicaResultado nc) {
        EnvioNc envio = nc.envio();
        if (envio == null) {
            // La NC no llegó a persistirse (o no se generó): no hay documento
            // que comunicar y no es un error del operador.
            return;
        }
        ComprobantesEmitidos ncComprobante = envio.comprobante();
        try {
            if (comprobanteService.enviarComprobanteAHacienda(ncComprobante)) {
                return; // ACEPTADO, con sus fechas selladas
            }
            // Rechazo de fondo, fallo de transporte o preflight: el XML firmado
            // se encola igual para que la NC no se pierda ni salga del plazo
            // legal (Art. 21 párr. 3).
            registrarPendienteDeEnvioNc(envio);
        } catch (RuntimeException e) {
            // La devolución está confirmada y la NC escrita: no hay nada que
            // deshacer acá. Se deja constancia y el documento sigue PENDIENTE
            // para el lote de 48 h.
            LOG.error("Error en la fase de envío de la nota de crédito: " + e.getMessage()
                    + " | source=DevolucionesResource.enviarNcAHacienda()"
                    + " | despues=la devolución ya está confirmada; la NC queda pendiente de reintento",
                    e);
        }
    }

    /**
     * FIRMADO-AHORA y encolado del envío diferido de la NC.
     *
     * <p>Réplica mínima, en este recurso, de la fase (b) de
     * {@link ComprobanteService#enviarComprobanteCreado}: allí el
     * {@code registrarPendienteDeEnvio} equivalente es privado y su firma de
     * entrada es el resultado de la venta, así que la NC no puede pasar por ese
     * camino sin cambiar el contrato de ese servicio. Se replica entonces solo
     * lo imprescindible —firmar el XML y llamar
     * {@link Services.EnvioFueraLineaService#registrarDocumentoFirmado}— con la
     * misma tolerancia a fallos: se registra y se sigue, sin propagar.</p>
     *
     * <p>La firma se produce acá y no al reintentar porque la firma cubre los
     * bytes: un documento re-marshallado ya no sería el mismo que Hacienda debe
     * aceptar para esa clave.</p>
     */
    private void registrarPendienteDeEnvioNc(@Nonnull EnvioNc envio) {
        ComprobantesEmitidos ncComprobante = envio.comprobante();
        try {
            String xml = strategyFactory.forCode(envio.tipoDocumento()).buildXml(ncComprobante);
            if (xml == null || xml.isBlank()) {
                LOG.warn("No se generó XML para encolar el envío diferido de la NC"
                        + " | source=DevolucionesResource.registrarPendienteDeEnvioNc()"
                        + " | despues=la NC sigue PENDIENTE y el lote de 48h la reintentará");
                return;
            }
            Services.HaciendaSigner.SignResult firmado = haciendaSigner.signXml(xml);
            if (!firmado.success || firmado.signedXml == null || firmado.signedXml.isBlank()) {
                LOG.warn("No se pudo firmar el XML para encolar el envío diferido de la NC: "
                        + (firmado.errorMessage != null ? firmado.errorMessage : "sin detalle")
                        + " | source=DevolucionesResource.registrarPendienteDeEnvioNc()"
                        + " | despues=queda PENDIENTE; sin firma no hay documento que diferir");
                return;
            }
            Models.EnvioFueraLinea encolado = envioFueraLineaService.registrarDocumentoFirmado(
                    ncComprobante, envio.tipoDocumento(), envio.sucursal(), envio.terminal(),
                    envio.situacion(), firmado.signedXml,
                    Services.EnvioFueraLineaService.ORIGEN_FALLO_ENVIO_INMEDIATO);
            if (encolado == null) {
                LOG.warn("No se pudo encolar la NC para su envío diferido"
                        + " | source=DevolucionesResource.registrarPendienteDeEnvioNc()"
                        + " | despues=queda PENDIENTE y el lote de 48h la reintentará");
            }
        } catch (jakarta.xml.bind.JAXBException | RuntimeException e) {
            // Ni el XML ni la firma ni la fila: la NC sigue PENDIENTE, que es
            // justamente el estado del que parte el lote de 48 h. Se registra y
            // NO se propaga: la devolución ya está confirmada.
            LOG.warn("Error encolando el envío diferido de la NC: " + e.getMessage()
                    + " | source=DevolucionesResource.registrarPendienteDeEnvioNc()"
                    + " | despues=la NC queda PENDIENTE y el lote de 48h la reintentará");
        }
    }

    /**
     * Builds, persists and sends the Nota de Crédito Electrónica through the
     * UNCHANGED {@link DocumentoStrategyFactory} pipeline. Mirrors the legacy
     * INNER try/catch: any {@link RuntimeException} is alerted as "Error NC"
     * and swallowed here so the base devolucion still succeeds without the
     * electronic document.
     *
     * <p><b>Solo la fase (a):</b> arma, firma y CONFIRMA la NC, y devuelve el
     * contexto que necesita la fase (b). El envío a Hacienda es de
     * {@link #enviarNcAHacienda}, que corre después del commit — la firma de la
     * clave va con {@code situacion 1} fijo (posición 42), que es lo que este
     * módulo siempre emitió, así que no hay decisión de conectividad que
     * tomar acá.</p>
     */
    private @Nonnull NcElectronicaResultado generarNcElectronica(
            @Nonnull ComprobantesEmitidos facturaSeleccionada,
            @Nonnull List<LineaSeleccion> seleccion,
            @Nonnull String motivo,
            @Nonnull BigDecimal totalDevolucion,
            @Nonnull String authorizedBy) {
        String consecutivoFactura = facturaSeleccionada.getEncabezado() != null
                ? facturaSeleccionada.getEncabezado().getNumeroConsecutivo() : null;
        try {
            ConfiguracionAplicacion appSettings = appSettingsService.returnCurrent();
            if (appSettings == null || facturaSeleccionada.getEncabezado() == null) {
                return NcElectronicaResultado.sinDocumento(
                        "NC electrónica omitida: configuración o encabezado no disponible",
                        consecutivoFactura);
            }
            Clientes client = buscarClienteDeFactura(facturaSeleccionada);

            DocumentoStrategy ncStrategy = strategyFactory.forCode(CODIGO_NC);
            String sucursal = appSettings.getCodigoSucursal() != null ? appSettings.getCodigoSucursal() : "001";
            String terminal = appSettings.getCodigoTerminal() != null ? appSettings.getCodigoTerminal() : "001";
            long consecutivo = consecutivoEmitidoService.getNextSequential(sucursal, terminal, ncStrategy.getCodigoDocumento());
            String numeroConsecutivo = String.format("%s%s%s%010d",
                    sucursal, terminal,
                    ncStrategy.getCodigoDocumento(), consecutivo);

            Encabezado ncEncabezado = ncStrategy.buildEncabezado(appSettings, client);
            ncEncabezado.setNumeroConsecutivo(numeroConsecutivo);

            List<MedioPago> medioPagoList = new ArrayList<>();
            MedioPago medio = new MedioPago();
            medio.setMedioPago("01");
            medio.setComprobante(ncEncabezado);
            medioPagoList.add(medio);
            ncEncabezado.setMedioPago(medioPagoList);

            String clave = haciendaSigner.generateInvoiceKey(
                    appSettings.getIdentificacion(), numeroConsecutivo, SITUACION_NC,
                    ncEncabezado.getFechaEmision().toLocalDate());
            ncEncabezado.setClave(clave);

            DetalleServicio ncDetalles = new DetalleServicio();
            List<LineaDetalle> ncLineas = new ArrayList<>();
            int lineNum = 0;
            for (LineaSeleccion sel : seleccion) {
                LineaDetalle ol = sel.original();
                LineaDetalle nl = new LineaDetalle();
                nl.setNumeroLinea(lineNum++);
                nl.setCodigoCabys(ol.getCodigoCabys());
                if (ol.getCodigosComerciales() != null) {
                    List<CodigoComercial> ccs = new ArrayList<>();
                    for (CodigoComercial cc : ol.getCodigosComerciales()) {
                        CodigoComercial ncc = new CodigoComercial();
                        ncc.setTipo(cc.getTipo());
                        ncc.setCodigo(cc.getCodigo());
                        ncc.setLineaDetalle(nl);
                        ccs.add(ncc);
                    }
                    nl.setCodigosComerciales(ccs);
                }
                nl.setCantidad(sel.cantidadDevolver());
                nl.setUnidadMedida(ol.getUnidadMedida());
                nl.setUnidadMedidaComercial(ol.getUnidadMedidaComercial());
                nl.setDetalle(ol.getDetalle());
                nl.setPrecioUnitario(ol.getPrecioUnitario());
                BigDecimal montoTotal = ol.getPrecioUnitario().multiply(sel.cantidadDevolver());
                nl.setMontoTotal(montoTotal);
                nl.setSubTotal(montoTotal);
                nl.setMontoTotalLinea(montoTotal);

                BigDecimal factor = sel.cantidadDevolver().divide(ol.getCantidad(), 6, RoundingMode.HALF_UP);
                if (ol.getImpuestos() != null) {
                    List<Impuesto> imps = new ArrayList<>();
                    for (Impuesto imp : ol.getImpuestos()) {
                        Impuesto ni = new Impuesto();
                        ni.setCodigo(imp.getCodigo());
                        ni.setCodigoTarifaIVA(imp.getCodigoTarifaIVA());
                        ni.setTarifa(imp.getTarifa());
                        ni.setMonto(imp.getMonto() != null
                                ? imp.getMonto().multiply(factor).setScale(5, RoundingMode.HALF_UP)
                                : BigDecimal.ZERO);
                        ni.setLineaDetalle(nl);
                        if (imp.getExoneracion() != null) {
                            Models.Detalles.Exoneracion origExo = imp.getExoneracion();
                            Models.Detalles.Exoneracion newExo = new Models.Detalles.Exoneracion();
                            newExo.setTipoDocumentoEX1(origExo.getTipoDocumentoEX1());
                            newExo.setTipoDocumentoOTRO(origExo.getTipoDocumentoOTRO());
                            newExo.setNumeroDocumento(origExo.getNumeroDocumento());
                            newExo.setArticulo(origExo.getArticulo());
                            newExo.setInciso(origExo.getInciso());
                            newExo.setNombreInstitucion(origExo.getNombreInstitucion());
                            newExo.setNombreInstitucionOtros(origExo.getNombreInstitucionOtros());
                            newExo.setFechaEmisionEX(origExo.getFechaEmisionEX());
                            newExo.setTarifaExonerada(origExo.getTarifaExonerada());
                            newExo.setMontoExoneracion(origExo.getMontoExoneracion());
                            newExo.setImpuesto(ni);
                            ni.setExoneracion(newExo);
                        }
                        imps.add(ni);
                    }
                    nl.setImpuestos(imps);
                }
                if (ol.getDescuentos() != null) {
                    List<Descuento> descs = new ArrayList<>();
                    for (Descuento d : ol.getDescuentos()) {
                        Descuento nd = new Descuento();
                        nd.setCodigoDescuento(d.getCodigoDescuento());
                        nd.setNaturalezaDescuento(d.getNaturalezaDescuento());
                        nd.setMontoDescuento(d.getMontoDescuento().multiply(factor).setScale(5, RoundingMode.HALF_UP));
                        nd.setLineaDetalle(nl);
                        descs.add(nd);
                    }
                    nl.setDescuentos(descs);
                }
                nl.setDetalleServicio(ncDetalles);
                ncLineas.add(nl);
            }
            ncDetalles.setLineasDetalle(ncLineas);
            ncDetalles.setStatus(true);

            ResumenFactura ncResumen = new ResumenFactura();
            CodigoTipoMoneda moneda = new CodigoTipoMoneda();
            moneda.setCodigoMoneda("CRC");
            ncResumen.setCodigoMoneda(moneda);
            BigDecimal totalGravado = BigDecimal.ZERO;
            BigDecimal totalExento = BigDecimal.ZERO;
            BigDecimal totalExonerado = BigDecimal.ZERO;
            BigDecimal totalServGravados = BigDecimal.ZERO;
            BigDecimal totalMercGravadas = BigDecimal.ZERO;
            BigDecimal totalServExentos = BigDecimal.ZERO;
            BigDecimal totalMercExentas = BigDecimal.ZERO;
            BigDecimal totalServExonerado = BigDecimal.ZERO;
            BigDecimal totalMercExonerada = BigDecimal.ZERO;
            BigDecimal totalVenta = BigDecimal.ZERO;
            BigDecimal totalDescuento = BigDecimal.ZERO;
            BigDecimal totalImpuesto = BigDecimal.ZERO;
            Map<BigDecimal, BigDecimal> taxByRate = new HashMap<>();
            BigDecimal totalIVADevuelto = BigDecimal.ZERO;
            for (LineaDetalle linea : ncLineas) {
                totalVenta = totalVenta.add(linea.getMontoTotal());
                if (linea.getDescuentos() != null) {
                    totalDescuento = totalDescuento.add(linea.getDescuentos().stream()
                            .map(Descuento::getMontoDescuento).reduce(BigDecimal.ZERO, BigDecimal::add));
                }
                boolean hasTax = linea.getImpuestos() != null && !linea.getImpuestos().isEmpty();
                boolean hasExoneracion = hasTax && linea.getImpuestos().stream()
                        .anyMatch(i -> i.getExoneracion() != null);
                if (hasExoneracion) {
                    totalExonerado = totalExonerado.add(linea.getMontoTotal());
                    totalServExonerado = totalServExonerado.add(linea.getMontoTotal());
                    totalMercExonerada = totalMercExonerada.add(linea.getMontoTotal());
                } else if (hasTax) {
                    totalGravado = totalGravado.add(linea.getMontoTotal());
                    totalMercGravadas = totalMercGravadas.add(linea.getMontoTotal());
                    for (Impuesto i : linea.getImpuestos()) {
                        if (i.getMonto() != null) {
                            totalImpuesto = totalImpuesto.add(i.getMonto());
                        }
                        if (i.getTarifa() != null) {
                            taxByRate.merge(i.getTarifa(), i.getMonto() != null ? i.getMonto() : BigDecimal.ZERO, BigDecimal::add);
                            if ("04".equals(i.getTarifa().toPlainString())) {
                                totalIVADevuelto = totalIVADevuelto.add(i.getMonto() != null ? i.getMonto() : BigDecimal.ZERO);
                            }
                        }
                    }
                } else {
                    totalExento = totalExento.add(linea.getMontoTotal());
                    totalServExentos = totalServExentos.add(linea.getMontoTotal());
                    totalMercExentas = totalMercExentas.add(linea.getMontoTotal());
                }
            }
            BigDecimal totalVentaNeta = totalVenta.subtract(totalDescuento);
            BigDecimal totalOtrosCargos = ComprobanteService.calcularTotalOtrosCargos(ncDetalles);
            BigDecimal totalComprobante = totalVentaNeta.add(totalImpuesto)
                    .add(totalOtrosCargos).subtract(totalIVADevuelto);
            ncResumen.setTotalServGravados(totalServGravados);
            ncResumen.setTotalServExentos(totalServExentos);
            ncResumen.setTotalServExonerado(totalServExonerado);
            ncResumen.setTotalMercanciasGravadas(totalMercGravadas);
            ncResumen.setTotalMercanciasExentas(totalMercExentas);
            ncResumen.setTotalMercExonerada(totalMercExonerada);
            ncResumen.setTotalGravado(totalGravado);
            ncResumen.setTotalExento(totalExento);
            ncResumen.setTotalExonerado(totalExonerado);
            ncResumen.setTotalVenta(totalVenta);
            ncResumen.setTotalDescuentos(totalDescuento);
            ncResumen.setTotalVentaNeta(totalVentaNeta);
            ncResumen.setTotalImpuesto(totalImpuesto);
            ncResumen.setTotalIVADevuelto(totalIVADevuelto);
            ncResumen.setTotalOtrosCargos(totalOtrosCargos);
            ncResumen.setTotalComprobante(totalComprobante);

            if (!taxByRate.isEmpty()) {
                List<Models.Resumen.TotalDesgloseImpuesto> desgloseList = new ArrayList<>();
                for (Map.Entry<BigDecimal, BigDecimal> entry : taxByRate.entrySet()) {
                    try {
                        Models.Enums.Tipo_TarifaIVA tarifa = Models.Enums.Tipo_TarifaIVA.getTarifa(entry.getKey().stripTrailingZeros().toPlainString());
                        Models.Resumen.TotalDesgloseImpuesto item = new Models.Resumen.TotalDesgloseImpuesto();
                        item.setCodigo("01");
                        item.setCodigoTarifaIVA(tarifa.getCodigo());
                        item.setTotalMontoImpuesto(entry.getValue().setScale(5, java.math.RoundingMode.HALF_UP));
                        item.setResumenFactura(ncResumen);
                        desgloseList.add(item);
                    } catch (IllegalArgumentException e) {
                        // Legacy parity: unknown rates are skipped from the
                        // desglose (documented latent gap, do NOT fix here).
                        LOG.debug("Tarifa fuera del enum Tipo_TarifaIVA: " + entry.getKey());
                    }
                }
                ncResumen.setTotalDesgloseImpuestos(desgloseList);
            }

            // Devolución real sobre comprobante aceptado: el código 01 "Anula
            // documento de referencia" de la nota 9 es el correcto aquí. Para un
            // rechazo de Hacienda la vía es la re-emisión con código 16
            // (Art. 19), y el guard de validarGuardias impide llegar hasta aquí
            // con un documento rechazado.
            InformacionReferencia ref = InformacionReferencia.from(facturaSeleccionada,
                    Models.Enums.Tipo_CodigosReferencia.ANULA_DOCUMENTO_REFERENCIA,
                    motivo, ncStrategy.getCodigoDocumento());
            List<InformacionReferencia> referencias = new ArrayList<>();
            referencias.add(ref);

            ComprobantesEmitidos ncComprobante = new ComprobantesEmitidos();
            ncComprobante.setEncabezado(ncEncabezado);
            ncComprobante.setDetalles(ncDetalles);
            ncComprobante.setResumen(ncResumen);
            ncComprobante.setInformacionReferencia(referencias);
            Models.Usuarios currentUser = currentUser();
            ncComprobante.setUser(currentUser != null ? currentUser.getUsername() : authorizedBy);
            ncComprobante.setStatus(true);
            ncComprobante.setHaciendaClave(clave);
            ncComprobante.setHaciendaEstado("PENDIENTE");
            ncEncabezado.setEstado("PENDIENTE");

            comprobantesService.createAndReturn(ncComprobante);

            // ── Fin de la fase (a): la NC está escrita. El envío per CR 2176
            //    §5.6 lo pide la fase (b), ya con el commit hecho (ver
            //    enviarNcAHacienda); acá solo se deja capturado lo que esa
            //    fase necesita para decidir y para reencolar. Los mismos
            //    valores de sucursal, terminal y tipo que se imprimieron en el
            //    consecutivo, porque son los que quedaron grabados en la clave.
                        LOG.info("Nota de Credito electronica " + numeroConsecutivo + " generada para devolucion" + " | user=" + String.valueOf(currentUser) + " | source=" + "DevolucionesResource.procesarDevolucion()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));

            return new NcElectronicaResultado(true, clave, numeroConsecutivo,
                    "Nota de Credito electronica generada", consecutivoFactura,
                    new EnvioNc(ncComprobante, ncStrategy.getCodigoDocumento(),
                            sucursal, terminal, SITUACION_NC));
        } catch (RuntimeException eNC) {
                        LOG.warn("Error al generar Nota de Credito electronica: " + eNC.getMessage() + " | user=" + String.valueOf(currentUser()) + " | source=" + "DevolucionesResource.procesarDevolucion()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(eNC.getMessage()));
            return NcElectronicaResultado.sinDocumento(
                    "Error al generar Nota de Credito electronica: " + eNC.getMessage(),
                    consecutivoFactura);
        }
    }

    /** Print URL served by the pre-existing PdfFileServlet (/facturas/*). */
    private @Nonnull String printUrl(@Nullable String clave) {
        String nombre = clave == null ? "" : "factura_" + clave + ".pdf";
        String base = rootPath == null || rootPath.isBlank() || "/".equals(rootPath)
                ? "" : rootPath;
        return base + "/facturas/" + nombre;
    }

    // ════════════════════════════════════════════════════════════════════
    // Shared plumbing
    // ════════════════════════════════════════════════════════════════════

    /**
     * Resolves the authenticated {@link Usuarios} row through the T12 identity
     * provider's principal (SessionController.getCurrentUser parity); null
     * for anonymous/system contexts (alertas accepts null).
     */
    private @Nullable Usuarios currentUser() {
        try {
            if (identity.isAnonymous() || identity.getPrincipal() == null) {
                return null;
            }
            return loginService.findByUsername(identity.getPrincipal().getName());
        } catch (RuntimeException e) {
            LOG.debug("No current user resolvable", e);
            return null;
        }
    }

    private boolean isHxRequest() {
        String header = httpHeaders.getHeaderString("HX-Request");
        return header != null && !"false".equalsIgnoreCase(header);
    }

    private static Response badRequest(@Nonnull String mensaje) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(ApiResponse.error("VALIDATION_ERROR", mensaje))
                .build();
    }

    private static Response notFound() {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(ApiResponse.error("NOT_FOUND", "No se encontró la factura solicitada"))
                .build();
    }

    /** Mirrors AppAuthResource.invalidCredentials() exactly. */
    private static Response invalidCredentials() {
        return Response.status(Response.Status.UNAUTHORIZED)
                .entity(ApiResponse.error("INVALID_CREDENTIALS",
                        "Usuario o contraseña incorrectos"))
                .build();
    }

    private @Nonnull Map<String, Object> facturaHeader(@Nonnull ComprobantesEmitidos factura) {
        Encabezado enc = factura.getEncabezado();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("id", factura.getId());
        header.put("consecutivo", enc != null ? enc.getNumeroConsecutivo() : null);
        header.put("fechaEmision", enc != null ? enc.getFechaEmision() : null);
        header.put("cliente", enc != null && enc.getReceptor() != null ? enc.getReceptor().getNombre() : null);
        header.put("total", factura.getResumen() != null ? factura.getResumen().getTotalComprobante() : null);
        return header;
    }

    // ════════════════════════════════════════════════════════════════════
    // HTMX fragments (Java-built; Qute expressions unavailable here)
    // ════════════════════════════════════════════════════════════════════

    private @Nonnull String facturasFragment(@Nonnull List<Map<String, Object>> pagina) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"table-container\" id=\"devoluciones-busqueda\" data-kit-table>");
        sb.append("<table class=\"table is-striped is-hoverable is-fullwidth\"><thead><tr>")
                .append("<th>Número Consecutivo</th><th>Fecha</th><th>Cliente</th><th>Total</th><th>Acción</th>")
                .append("</tr></thead><tbody>");
        if (pagina.isEmpty()) {
            sb.append("<tr><td colspan=\"5\" class=\"has-text-centered has-text-grey\">")
                    .append("No se encontraron facturas</td></tr>");
        }
        for (Map<String, Object> fila : pagina) {
            long fid = ((Number) fila.get("id")).longValue();
            sb.append("<tr>")
                    .append("<td class=\"is-family-monospace\">").append(escape(str(fila.get("consecutivo")))).append("</td>")
                    .append("<td>").append(escape(str(fila.get("fechaEmision")))).append("</td>")
                    .append("<td>").append(escape(str(fila.get("cliente")))).append("</td>")
                    .append("<td class=\"has-text-right\">").append(escape(str(fila.get("total")))).append("</td>")
                    .append("<td><button type=\"button\" class=\"button is-warning is-small\"")
                    .append(" hx-get=\"").append(rootPath).append("/api/app/devoluciones/").append(fid).append("/lineas\"")
                    .append(" hx-target=\"#devolucion-panel\" hx-swap=\"innerHTML\">Seleccionar</button></td>")
                    .append("</tr>");
        }
        sb.append("</tbody></table></div>");
        return sb.toString();
    }

    /**
     * Selection form: one qty input PER line (parallel arrays stay aligned
     * because every row submits both halves; qty &gt; 0 means selected), the
     * motivo textarea, a live server-computed total (POST /initiate on
     * change) and the kit modal whose trigger opens the authorize dialog.
     */
    private @Nonnull String lineasFragment(@Nonnull ComprobantesEmitidos factura,
                                           @Nonnull List<LineaRow> rows) {
        long id = factura.getId();
        Map<String, Object> header = facturaHeader(factura);
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"box\">");
        sb.append("<h3 class=\"title is-5\">Devolución - Factura ")
                .append(escape(str(header.get("consecutivo")))).append("</h3>");
        sb.append("<form id=\"devolucion-form\"")
                .append(" hx-post=\"").append(rootPath).append("/api/app/devoluciones/initiate\"")
                .append(" hx-trigger=\"change from:#devolucion-form input[name='lineaCantidad']\"")
                .append(" hx-target=\"#devolucion-total-server\" hx-swap=\"innerHTML\">");
        sb.append("<input type=\"hidden\" name=\"facturaId\" value=\"").append(id).append("\"/>");
        sb.append("<div class=\"table-container\"><table class=\"table is-striped is-hoverable is-fullwidth\">");
        sb.append("<thead><tr><th>Artículo</th><th>Cantidad Original</th>")
                .append("<th>Cantidad a Devolver</th><th>Precio Unitario</th></tr></thead><tbody>");
        if (rows.isEmpty()) {
            sb.append("<tr><td colspan=\"4\" class=\"has-text-centered has-text-grey\">")
                    .append("No hay lineas en esta factura</td></tr>");
        }
        for (LineaRow row : rows) {
            sb.append("<tr>")
                    .append("<td>").append(escape(str(row.detalle()))).append("</td>")
                    .append("<td class=\"has-text-right\">").append(escape(str(row.cantidadOriginal()))).append("</td>")
                    .append("<td><input type=\"hidden\" name=\"lineaNumero\" value=\"")
                    .append(row.indice()).append("\"/>")
                    // visible qty input paired with its hidden index half:
                    .append("<input class=\"input is-small\" type=\"number\" min=\"0\" max=\"")
                    .append(escape(str(row.cantidadOriginal()))).append("\" step=\"any\" value=\"0\"")
                    .append(" name=\"lineaCantidad\" aria-label=\"Cantidad a devolver\"/></td>")
                    .append("<td class=\"has-text-right\">").append(escape(str(row.precioUnitario()))).append("</td>")
                    .append("</tr>");
        }
        sb.append("</tbody></table></div>");
        sb.append("</form>");

        // Motivo lives OUTSIDE the qty form so the live-total POST does not
        // carry it; hx-include pulls BOTH forms into the authorize POST.
        sb.append("<div class=\"field mt-3\"><label class=\"label\" for=\"devolucion-motivo\">Motivo de la Devolucion</label>");
        sb.append("<textarea id=\"devolucion-motivo\" name=\"motivo\" form=\"devolucion-form\"")
                .append(" class=\"textarea\" rows=\"3\" required></textarea></div>");

        sb.append("<div class=\"level mt-3\"><div class=\"level-left\">")
                .append("<span id=\"devolucion-total-server\"></span></div>")
                .append("<div class=\"level-right\">");
        sb.append(includeKitModal(id));
        sb.append("</div></div>");
        sb.append("</div>");
        return sb.toString();
    }

    /**
     * The _kit/modal shell rendered inline (same markup contract as
     * templates/_kit/modal.html) because its bodyUrl depends on the selected
     * factura id, which only exists once the lineas fragment has been
     * rendered for a concrete selection.
     */
    private @Nonnull String includeKitModal(long facturaId) {
        String bodyUrl = rootPath + "/api/app/devoluciones/" + facturaId + "/authform";
        return "<div class=\"kit-modal-root\" style=\"display:inline-block\" x-data=\"{ open:false }\">"
                + "<button type=\"button\" class=\"button is-danger\" aria-haspopup=\"dialog\""
                + " @click=\"open = true; $nextTick(() => $refs.card.focus())\""
                + " hx-get=\"" + escape(bodyUrl) + "\""
                + " hx-target=\"#auth-devolucion-modal-body\" hx-swap=\"innerHTML\">Procesar Devolución</button>"
                + "<div class=\"modal\" id=\"auth-devolucion-modal\" :class=\"{ 'is-active': open }\""
                + " @keydown.escape.window=\"open = false\" role=\"dialog\" aria-modal=\"true\""
                + " aria-labelledby=\"auth-devolucion-modal-title\" data-kit-modal>"
                + "<div class=\"modal-background\" @click=\"open = false\"></div>"
                + "<div class=\"modal-card\" x-ref=\"card\" tabindex=\"-1\" @keydown.tab=\"kitTrapTab($event, $el)\">"
                + "<header class=\"modal-card-head\">"
                + "<p class=\"modal-card-title\" id=\"auth-devolucion-modal-title\">Autorización Requerida</p>"
                + "<button class=\"delete\" type=\"button\" aria-label=\"Cerrar\" @click=\"open = false\"></button>"
                + "</header>"
                + "<section class=\"modal-card-body\" id=\"auth-devolucion-modal-body\">"
                + "<p class=\"has-text-centered\">Se requiere autorización para procesar la devolución.</p>"
                + "</section>"
                + "<footer class=\"modal-card-foot\">"
                + "<button type=\"button\" class=\"button\" @click=\"open = false\">Cerrar</button>"
                + "</footer>"
                + "</div></div></div>";
    }

    private @Nonnull String authformFragment(long facturaId) {
        String action = rootPath + "/api/app/devoluciones/" + facturaId + "/authorize";
        return "<form id=\"auth-devolucion-form\" method=\"post\" action=\"" + escape(action) + "\""
                + " hx-post=\"" + escape(action) + "\""
                + " hx-include=\"#devolucion-form, #devolucion-motivo\""
                + " hx-target=\"#auth-devolucion-modal-body\" hx-swap=\"innerHTML\""
                + " hx-on::response-error=\"var b=document.getElementById('auth-error-banner');"
                + " b.textContent='Autorización Fallida: ' + (event.detail.xhr.responseText || 'Usuario o contraseña incorrectos');"
                + " b.classList.remove('is-hidden')\">"
                + "<p class=\"has-text-centered mb-3\">Se requiere autorización para procesar la devolución.</p>"
                + "<div class=\"field\"><label class=\"label\" for=\"auth-username\">Usuario:</label>"
                + "<input class=\"input\" type=\"text\" id=\"auth-username\" name=\"username\" required autocomplete=\"off\"/></div>"
                + "<div class=\"field\"><label class=\"label\" for=\"auth-password\">Contraseña:</label>"
                + "<input class=\"input\" type=\"password\" id=\"auth-password\" name=\"password\" required/></div>"
                + "<div id=\"auth-error-banner\" class=\"notification is-danger is-hidden\" role=\"alert\"></div>"
                + "<button type=\"submit\" class=\"button is-danger is-fullwidth\">Autorizar</button>"
                + "</form>";
    }

    /** Success swap: NC summary + print link (+ OOB historial refresh). */
    private @Nonnull String ncSummaryFragment(@Nonnull NcSummary s) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div id=\"nc-summary\" class=\"content\">");
        if (s.ncGenerada()) {
            sb.append("<p class=\"has-text-success has-text-weight-semibold\">")
                    .append("✓ Devolución procesada correctamente</p>");
        } else {
            sb.append("<p class=\"has-text-warning has-text-weight-semibold\">")
                    .append("Devolución procesada; la NC electrónica no pudo generarse.</p>")
                    .append("<p class=\"is-size-7 has-text-grey\">").append(escape(str(s.mensaje()))).append("</p>");
        }
        sb.append("<table class=\"table is-fullwidth is-narrow\"><tbody>")
                .append("<tr><th>Factura original</th><td class=\"is-family-monospace\">")
                .append(escape(str(s.facturaConsecutivo()))).append("</td></tr>")
                .append("<tr><th>Clave NC</th><td class=\"is-family-monospace\">")
                .append(escape(str(s.clave()))).append("</td></tr>")
                .append("<tr><th>Consecutivo NC</th><td class=\"is-family-monospace\">")
                .append(escape(str(s.consecutivo()))).append("</td></tr>")
                .append("<tr><th>Monto devuelto</th><td>₡")
                .append(s.montoTotal() == null ? "0.00"
                        : s.montoTotal().setScale(2, RoundingMode.HALF_UP).toPlainString())
                .append("</td></tr>")
                .append("<tr><th>Motivo</th><td>").append(escape(str(s.motivo()))).append("</td></tr>")
                .append("</tbody></table>");
        if (s.ncGenerada() && s.clave() != null) {
            sb.append("<button type=\"button\" class=\"button is-link\"")
                    .append(" onclick=\"printPDF('").append(escape(s.printUrl())).append("')\">")
                    .append("Imprimir NC</button>");
        }
        sb.append("</div>");
        sb.append(historialOobFragment());
        return sb.toString();
    }

    /** Out-of-band refresh of the page historial tbody after a success. */
    private @Nonnull String historialOobFragment() {
        StringBuilder sb = new StringBuilder();
        sb.append("<tbody hx-swap-oob=\"true\" id=\"historial-tbody\">");
        List<NotaCredito> notas = orEmpty(notaCreditoService.listAll());
        if (notas.isEmpty()) {
            sb.append("<tr><td colspan=\"5\" class=\"has-text-centered has-text-grey\">")
                    .append("No hay notas de credito registradas</td></tr>");
        }
        for (NotaCredito nota : notas) {
            sb.append("<tr>")
                    .append("<td>").append(nota.getFecha() == null ? "-" : nota.getFecha().toString()).append("</td>")
                    .append("<td class=\"is-family-monospace\">")
                    .append(escape(nota.getComprobanteOriginal() != null
                            && nota.getComprobanteOriginal().getEncabezado() != null
                            ? str(nota.getComprobanteOriginal().getEncabezado().getNumeroConsecutivo())
                            : "-")).append("</td>")
                    .append("<td>").append(escape(str(nota.getMotivo()))).append("</td>")
                    .append("<td class=\"has-text-right\">").append(escape(str(nota.getMontoTotal()))).append("</td>")
                    .append("<td class=\"has-text-centered\">").append(escape(str(nota.getHaciendaEstado()))).append("</td>")
                    .append("</tr>");
        }
        sb.append("</tbody>");
        return sb.toString();
    }

    private static String str(@Nullable Object value) {
        return value == null ? "-" : String.valueOf(value);
    }

    private static String escape(@Nullable String value) {
        return value == null ? "" : value.replace("&", "&amp;")
                .replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ── Small value carriers ────────────────────────────────────────────

    /**
     * Lo que devuelve la fase (a) a {@link #authorize}: o un guard que cortó
     * (400/404/409, sin escrituras y sin envío), o la devolución ya
     * confirmada con la NC y los datos que el sobre necesita.
     *
     * <p>Es un carrier y nada más: no decide, no habla con Hacienda y no abre
     * transacciones. Existe porque la fase (a) y la fase (b) no pueden
     * devolverse un {@code Response}: el sobre se construye DESPUÉS del envío,
     * y el envío necesita salir de la transacción que la fase (a) confirma.</p>
     */
    private record DevolucionProcesada(@Nullable Response rechazo,
                                       @Nullable String motivoFinal,
                                       @Nullable BigDecimal totalDevolucion,
                                       @Nullable NcElectronicaResultado nc) {
        static DevolucionProcesada rechazada(Response rechazo) {
            return new DevolucionProcesada(rechazo, null, null, null);
        }

        static DevolucionProcesada procesada(String motivoFinal, BigDecimal totalDevolucion,
                                             NcElectronicaResultado nc) {
            return new DevolucionProcesada(null, motivoFinal, totalDevolucion, nc);
        }
    }

    /** One searchable invoice row (legacy resultados table). */
    public record ComprobantesEmitidasRow(Long id, String consecutivo, Object fechaEmision,
                                          String cliente, BigDecimal total) {}

    /** Payload of GET /facturas (paged envelope). */
    public record PagedFacturas(List<Map<String, Object>> data, long total, int page,
                                int size, int totalPages) {}

    /** One selectable line of GET /{id}/lineas. */
    public record LineaRow(int indice, Integer numeroLinea, String detalle,
                           BigDecimal cantidadOriginal, BigDecimal precioUnitario) {}

    /** Payload of GET /{id}/lineas (JSON mode). */
    public record FacturaDetalle(Map<String, Object> factura, List<LineaRow> lineas) {}

    /** A parsed selected line: position + original + requested quantity. */
    public record LineaSeleccion(int indice, LineaDetalle original, BigDecimal cantidadDevolver) {}

    /** Payload of POST /initiate. */
    public record InitiateResult(Long facturaId, BigDecimal totalDevolucion,
                                 int lineasSeleccionadas, String mensaje) {}

    /** Payload of POST /{id}/authorize — the NC summary swapped on success. */
    public record NcSummary(String facturaConsecutivo, String clave, String consecutivo,
                            BigDecimal montoTotal, String motivo, boolean ncGenerada,
                            String mensaje, String printUrl) {}
}
