package Controllers.Api.App;

import Models.AppSettings;
import Models.Clients;
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
import Models.Users;
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
import io.quarkus.security.identity.SecurityIdentity;
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
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
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
import java.util.logging.Level;
import java.util.logging.Logger;
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
 *   <li>Double-devolucion guard: when an active NotaCredito already exists
 *       for the factura the endpoint answers 409 ALREADY_RETURNED (dispatch
 *       requirement; precedent TributacionResource idempotency via
 *       {@link NotaCreditoService#listPorComprobante}).</li>
 * </ul>
 *
 * <p><b>Authorization:</b> {@code admin} or {@code facturacion}, mirroring the
 * legacy page's availability to the facturación area. The /api/app/* surface
 * additionally requires any authenticated user through the T13 permission
 * policy; mutating POSTs are CSRF-gated by quarkus-rest-csrf.</p>
 *
 * <p><b>NO real Hacienda sends from tests:</b> tests replace
 * {@link ComprobanteService} with {@code @InjectMock}; production behavior is
 * untouched.</p>
 */
@Path("/api/app/devoluciones")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"admin", "facturacion"})
@Tag(name = "App - Devoluciones")
public class DevolucionesResource {

    private static final Logger LOG = Logger.getLogger(DevolucionesResource.class.getName());

    /** Hacienda document code for Nota de Crédito Electrónica (legacy "03"). */
    public static final String CODIGO_NC = "03";

    /** Legacy p:dataTable rows=10 on both search results and historial. */
    public static final int DEFAULT_PAGE_SIZE = 10;

    private static final int MAX_PAGE_SIZE = 100;

    @Nonnull
    ComprobantesEmitidosService comprobantesService;

    @Nonnull
    NotaCreditoService notaCreditoService;

    @Nonnull
    InventarioService inventarioService;

    @Nonnull
    ClientService clientService;

    
    @Nonnull
    AppSettingsService appSettingsService;

    @Nonnull
    DocumentoStrategyFactory strategyFactory;

    @Nonnull
    Services.HaciendaSigner haciendaSigner;

    @Nonnull
    ComprobanteService comprobanteService;

    @Nonnull
    ConsecutivoEmitidoService consecutivoEmitidoService;

    @Nonnull
    LoginService loginService;

    @Nonnull
    SecurityIdentity identity;

    /** Request headers (quarkus-rest injectable) — source of HX-Request. */
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
            LOG.log(Level.WARNING, "Error buscando facturas para devolucion", e);
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
            LOG.log(Level.WARNING, "Error obteniendo las lineas de la factura " + id, e);
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
            LOG.log(Level.WARNING, "Error renderizando el formulario de autorización", e);
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
            LOG.log(Level.WARNING, "Error validando la devolucion", e);
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
     * <p>Transactional note: unlike the legacy ViewScoped bean (whose service
     * calls each committed independently), the NEW world wraps the whole
     * processing in ONE transaction — the house pattern of the migrated
     * resources (ClientsResource et al.) and the only way the pessimistic
     * consecutive generator can run. On success the observable state is
     * identical to legacy.</p>
     */
    @POST
    @Path("/{id}/authorize")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    @Operation(summary = "Authorize (supervisor credentials) and process the devolucion")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Devolucion processed; NC summary returned"),
        @APIResponse(responseCode = "400", description = "Validation failure (motivo/lines/quantities)"),
        @APIResponse(responseCode = "401", description = "Invalid supervisor credentials — zero side effects"),
        @APIResponse(responseCode = "404", description = "Unknown factura"),
        @APIResponse(responseCode = "409", description = "A credit note already exists for this factura"),
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
        Users authUser;
        try {
            authUser = loginService.findByUsername(username);
            if (authUser == null) {
                                LOG.info("Intento con usuario inexistente: " + username + " | user=" + String.valueOf(currentUser()) + " | source=" + "DevolucionesResource.authorize()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
                return invalidCredentials();
            }
            if (!Boolean.TRUE.equals(authUser.getStatus())) {
                                LOG.info("Intento con usuario deshabilitado: " + username + " | user=" + String.valueOf(currentUser()) + " | source=" + "DevolucionesResource.authorize()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
                return invalidCredentials();
            }
            if (!loginService.verifyPassword(password, authUser.getPassword())) {
                                LOG.info("Contraseña incorrecta de: " + username + " | user=" + String.valueOf(currentUser()) + " | source=" + "DevolucionesResource.authorize()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));
                return invalidCredentials();
            }
        } catch (RuntimeException e) {
            // SessionController.authorizeAction parity: an error inside the
            // credential check is alerted and blocks processing.
                        LOG.log(java.util.logging.Level.WARNING, "Error en authorizeAction: " + e.getMessage() + " | user=" + String.valueOf(currentUser()) + " | source=" + "DevolucionesResource.authorize()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
            return invalidCredentials();
        }

        String authorizedBy = authUser.getUsername();

        // ── Legacy authorize(): the exitosa alerta fires BEFORE the
        //    procesarDevolucion guards, so failed validations still carry it.
                LOG.info("Devolución autorizada por: " + authorizedBy + " | user=" + String.valueOf(currentUser()) + " | source=" + "DevolucionesResource.authorize()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));

        // ── procesarDevolucion guards (all BEFORE any domain write)
        ComprobantesEmitidos facturaSeleccionada = comprobantesService.find(id);
        if (facturaSeleccionada == null) {
            return notFound();
        }
        List<LineaSeleccion> seleccion;
        try {
            seleccion = parseSelecciones(facturaSeleccionada, lineaNumero, lineaCantidad);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        Response guard = validarGuardias(facturaSeleccionada, motivo, seleccion);
        if (guard != null) {
            return guard;
        }
        List<NotaCredito> previas = notaCreditoService.listPorComprobante(id);
        if (previas != null && !previas.isEmpty()) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(ApiResponse.error("ALREADY_RETURNED",
                            "La factura ya tiene una nota de credito registrada"))
                    .build();
        }

        String motivoFinal = motivo.trim();
        BigDecimal totalDevolucion = totalDevolucion(seleccion);
        Users currentUser = currentUser();

        try {
            // ── NotaCredito row (legacy field-for-field)
            Clients notaCliente = buscarClienteDeFactura(facturaSeleccionada);
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

                        LOG.info("Nota de credito creada por " + totalDevolucion + " - " + motivoFinal + " | user=" + String.valueOf(currentUser) + " | source=" + "DevolucionesResource.procesarDevolucion()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));

            NcSummary summary = new NcSummary(consecutivoOriginal, nc.clave(), nc.consecutivo(),
                    totalDevolucion, motivoFinal, nc.generada(), nc.mensaje(), printUrl(nc.clave()));

            if (isHxRequest()) {
                return htmlOk(ncSummaryFragment(summary));
            }
            return Response.ok(ApiResponse.ok(summary)).build();

        } catch (RuntimeException e) {
            // Legacy catch: alert + error message; partial state stays.
                        LOG.log(java.util.logging.Level.WARNING, "Error al procesar devolucion: " + e.getMessage() + " | user=" + String.valueOf(currentUser) + " | source=" + "DevolucionesResource.procesarDevolucion()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
            LOG.log(Level.WARNING, "Error procesando la devolucion de la factura " + id, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR",
                            "Error al procesar devolucion: " + e.getMessage()))
                    .build();
        }
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
            List<Clients> clients = clientService.searchByName(criterio);
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

    private @Nullable Clients buscarClienteDeFactura(@Nonnull ComprobantesEmitidos factura) {
        if (factura.getEncabezado() != null
                && factura.getEncabezado().getReceptor() != null
                && factura.getEncabezado().getReceptor().getNombre() != null) {
            List<Clients> found = clientService.searchByName(
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
                                          @Nullable String consecutivo, @Nullable String mensaje) {}

    /**
     * Builds, persists and sends the Nota de Crédito Electrónica through the
     * UNCHANGED {@link DocumentoStrategyFactory} pipeline. Mirrors the legacy
     * INNER try/catch: any {@link RuntimeException} is alerted as "Error NC"
     * and swallowed here so the base devolucion still succeeds without the
     * electronic document.
     */
    private @Nonnull NcElectronicaResultado generarNcElectronica(
            @Nonnull ComprobantesEmitidos facturaSeleccionada,
            @Nonnull List<LineaSeleccion> seleccion,
            @Nonnull String motivo,
            @Nonnull BigDecimal totalDevolucion,
            @Nonnull String authorizedBy) {
        try {
            AppSettings appSettings = appSettingsService.returnCurrent();
            if (appSettings == null || facturaSeleccionada.getEncabezado() == null) {
                return new NcElectronicaResultado(false, null, null,
                        "NC electrónica omitida: configuración o encabezado no disponible");
            }
            Clients client = buscarClienteDeFactura(facturaSeleccionada);

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
                    appSettings.getIdentificacion(), numeroConsecutivo, "1",
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
                        LOG.fine("Tarifa fuera del enum Tipo_TarifaIVA: " + entry.getKey());
                    }
                }
                ncResumen.setTotalDesgloseImpuestos(desgloseList);
            }

            InformacionReferencia ref = InformacionReferencia.from(facturaSeleccionada, "01", motivo);
            List<InformacionReferencia> referencias = new ArrayList<>();
            referencias.add(ref);

            ComprobantesEmitidos ncComprobante = new ComprobantesEmitidos();
            ncComprobante.setEncabezado(ncEncabezado);
            ncComprobante.setDetalles(ncDetalles);
            ncComprobante.setResumen(ncResumen);
            ncComprobante.setInformacionReferencia(referencias);
            Models.Users currentUser = currentUser();
            ncComprobante.setUser(currentUser != null ? currentUser.getUsername() : authorizedBy);
            ncComprobante.setStatus(true);
            ncComprobante.setHaciendaClave(clave);
            ncComprobante.setHaciendaEstado("PENDIENTE");
            ncEncabezado.setEstado("PENDIENTE");

            comprobantesService.createAndReturn(ncComprobante);

            // Send NC immediately to Hacienda per CR 2176 §5.6 (tests stub
            // ComprobanteService — never a real network call).
            comprobanteService.enviarComprobanteAHacienda(ncComprobante);

                        LOG.info("Nota de Credito electronica " + numeroConsecutivo + " generada para devolucion" + " | user=" + String.valueOf(currentUser) + " | source=" + "DevolucionesResource.procesarDevolucion()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(null));

            return new NcElectronicaResultado(true, clave, numeroConsecutivo,
                    "Nota de Credito electronica generada");
        } catch (RuntimeException eNC) {
                        LOG.log(java.util.logging.Level.WARNING, "Error al generar Nota de Credito electronica: " + eNC.getMessage() + " | user=" + String.valueOf(currentUser()) + " | source=" + "DevolucionesResource.procesarDevolucion()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(eNC.getMessage()));
            return new NcElectronicaResultado(false, null, null,
                    "Error al generar Nota de Credito electronica: " + eNC.getMessage());
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
     * Resolves the authenticated {@link Users} row through the T12 identity
     * provider's principal (SessionController.getCurrentUser parity); null
     * for anonymous/system contexts (alertas accepts null).
     */
    private @Nullable Users currentUser() {
        try {
            if (identity.isAnonymous() || identity.getPrincipal() == null) {
                return null;
            }
            return loginService.findByUsername(identity.getPrincipal().getName());
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "No current user resolvable", e);
            return null;
        }
    }

    private boolean isHxRequest() {
        String header = httpHeaders.getHeaderString("HX-Request");
        return header != null && !"false".equalsIgnoreCase(header);
    }

    private static <T> List<T> orEmpty(@Nullable List<T> list) {
        return list == null ? Collections.emptyList() : list;
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

    private static Response htmlOk(@Nonnull String html) {
        return Response.ok(html)
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).build();
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
