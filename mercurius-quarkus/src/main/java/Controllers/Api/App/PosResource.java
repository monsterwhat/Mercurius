package Controllers.Api.App;

import Models.AppSettings;
import Models.Articulos.Articulos;
import Models.Articulos.Carrito.ArticuloCarrito;
import Models.Articulos.Carrito.CartOperationResult;
import Models.Articulos.Carrito.CartSessionContext;
import Models.Clients;
import Models.ComprobantesEmitidos;
import Models.DTO.ApiResponse;
import Models.PagoEntry;
import Models.TipoCambio;
import Models.Users;
import Services.AlertasService;
import Services.AppSettingsService;
import Services.ArticulosService;
import Services.CarritoService;
import Services.ClientService;
import Services.ComprobanteService;
import Services.DirectoryService;
import Services.LoyaltyService;
import Services.LoginService;
import Services.Strategies.DocumentoStrategy;
import Services.Strategies.DocumentoStrategyFactory;
import Services.TipoCambioService;
import Services.cart.CartSessionStore;
import Utils.PDFGenerator;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import jakarta.ws.rs.core.UriInfo;
import java.io.File;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * T37-prep POS backend core (plan mercurius-jsf-to-api-migration): working
 * REST surface for the future POS app, WITHOUT templates. One cart per
 * authenticated cashier, resolved from the {@link SecurityIdentity} principal
 * and stored in {@link CartSessionStore}.
 *
 * <p><b>Delegation contract:</b> every cart mutation delegates to the
 * {@link CarritoService} ctx-methods EXACTLY like
 * {@code CrearTiqueteController} does today (same call sequence, same
 * {@link CartOperationResult} translation — FacesMessage becomes a
 * {@code {severity,summary,detail}} JSON message instead).</p>
 *
 * <p><b>Facturar pipeline</b> mirrors {@code CrearTiqueteController.facturar()}
 * step by step (override gate → settings gate → strategy → inventory adjust →
 * {@code ComprobanteService.crearComprobante} → loyalty redemption → PDF →
 * conditional client email → clear). Documented deltas and their written
 * justifications live in .omo/evidence/t37prep/baseline-characterization.md
 * (PDF FacesContext NPE tolerance, no auto-printing, settings-null envelope,
 * unused tipoCambio argument, single client reference, fresh fallback total).</p>
 */
@Path("/api/app/pos")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "App - POS")
public class PosResource {

    private static final Logger LOG = Logger.getLogger(PosResource.class.getName());

    /** Only these invoice PDFs are servable — blocks path traversal by shape. */
    private static final String FACTURA_FILE_PATTERN = "tiqueteElectronico_\\d+\\.pdf";

    /** Typeahead/picker result cap (keyboard-first POS pickers never page). */
    private static final int TYPEAHEAD_LIMIT = 10;

    /** Payment-method options for the dialog select (Hacienda codes). */
    private static final List<Map<String, Object>> METODOS_PAGO = buildMetodosPago();

    private static List<Map<String, Object>> buildMetodosPago() {
        List<Map<String, Object>> metodos = new ArrayList<>();
        for (String code : List.of("01", "02", "03", "04", "05", "06", "07", "08", "10", "99")) {
            Map<String, Object> metodo = new LinkedHashMap<>();
            metodo.put("codigo", code);
            metodo.put("etiqueta", PagoEntry.metodoPagoLabel(code));
            metodos.add(metodo);
        }
        return metodos;
    }

    @Inject
    @Nonnull
    CarritoService carritoService;

    @Inject
    @Nonnull
    CartSessionStore cartSessionStore;

    @Inject
    @Nonnull
    SecurityIdentity securityIdentity;

    @Inject
    @Nonnull
    LoginService loginService;

    @Inject
    @Nonnull
    ArticulosService articulosService;

    @Inject
    @Nonnull
    ClientService clientService;

    @Inject
    @Nonnull
    AppSettingsService appSettingsService;

    @Inject
    @Nonnull
    ComprobanteService comprobanteService;

    @Inject
    @Nonnull
    DocumentoStrategyFactory strategyFactory;

    @Inject
    @Nonnull
    PDFGenerator pdfGenerator;

    @Inject
    @Nonnull
    LoyaltyService loyaltyService;

    @Inject
    @Nonnull
    AlertasService alertasService;

    @Inject
    @Nonnull
    DirectoryService dirService;

    // ── T37 template-phase additions ────────────────────────────────────

    /**
     * Tipo de cambio for the POS badge. Read-only consumption of
     * {@link TipoCambioService#getNewestTipoCambio()} (service NOT modified).
     */
    @Inject
    @Nonnull
    TipoCambioService tipoCambioService;

    /**
     * Qute templates rendered to String: this stack ships quarkus-qute but
     * NOT quarkus-rest-qute, so no TemplateInstance message-body writer exists
     * (same approach as LoginPageResource/ClientsResource).
     */
    @Inject
    @Nonnull
    @Location("pages/facturas/cart-panel")
    Template cartPanelTemplate;

    @Inject
    @Nonnull
    @Location("pages/facturas/payment-dialog")
    Template paymentDialogTemplate;

    @Inject
    @Nonnull
    @Location("pages/facturas/client-picker")
    Template clientPickerTemplate;

    @Inject
    @Nonnull
    @Location("pages/facturas/facturar-resultado")
    Template facturarResultadoTemplate;

    @Inject
    @Nonnull
    @Location("pages/facturas/auth-modal-body")
    Template authModalBodyTemplate;

    @Context
    @Nonnull
    UriInfo uriInfo;

    // ── Scan / add / remove ─────────────────────────────────────────────

    /**
     * Barcode capture: sets ctx.codigoBarra/cantidadArticulo then delegates to
     * {@link CarritoService#processCodigoBarra(CartSessionContext)} exactly like
     * CrearTiqueteController.processCodigoBarra().
     */
    @POST
    @Path("/scan")
    @Operation(summary = "Scan a barcode into the caller's cart (delegates to CarritoService.processCodigoBarra)")
    public Response scan(@Nullable ScanRequest request) {
        String username = currentUsername();
        if (username == null) {
            return unauthenticated();
        }
        CartSessionContext ctx = cartSessionStore.getOrCreate(username).getCartContext();

        // Mirrors the JSF bindings: page writes both fields onto cartCtx before
        // invoking processCodigoBarra(); the service validates and clears them.
        ctx.setCodigoBarra(request == null ? null : request.codigoBarra);
        ctx.setCantidadArticulo(request == null || request.cantidad == null
                ? BigDecimal.ONE : request.cantidad);

        CartOperationResult result = carritoService.processCodigoBarra(ctx);
        return Response.ok(ApiResponse.ok(toMessage(result))).build();
    }

    /**
     * Adds an article by primary key (name-search flow), delegating to
     * {@link CarritoService#addArticulo(CartSessionContext, Articulos, java.math.BigDecimal)}
     * which merges quantities for repeated articles. Optional
     * {@code precioPersonalizado} reproduces the supervisor price override the
     * JSF page binds onto the cart row (gated at facturar time).
     */
    @POST
    @Path("/add")
    @Operation(summary = "Add an article by id to the caller's cart (delegates to CarritoService.addArticulo)")
    public Response add(@Nullable AddRequest request) {
        String username = currentUsername();
        if (username == null) {
            return unauthenticated();
        }
        if (request == null || request.articuloId == null) {
            return badRequest("VALIDATION_ERROR", "articuloId es requerido");
        }
        BigDecimal cantidad = request.cantidad == null ? BigDecimal.ONE : request.cantidad;
        if (cantidad.compareTo(BigDecimal.ZERO) <= 0) {
            return badRequest("VALIDATION_ERROR", "La cantidad debe ser mayor a cero");
        }

        Articulos articulo = articulosService.find(request.articuloId);
        if (articulo == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ApiResponse.error("ARTICULO_NO_ENCONTRADO",
                            "No existe un artículo con ese código"))
                    .build();
        }

        CartSessionStore.Entry entry = cartSessionStore.getOrCreate(username);
        CartSessionContext ctx = entry.getCartContext();
        carritoService.addArticulo(ctx, articulo, cantidad);

        if (request.precioPersonalizado != null) {
            // Parity with the JSF price-override binding: the override lives on
            // the cart ROW (ArticuloCarrito.precioPersonalizado), consumed by
            // getPrecioEfectivo() and gated by the facturar override check.
            for (ArticuloCarrito item : ctx.getCarrito()) {
                if (!item.isPromo()
                        && Objects.equals(item.getArticulo().getCodigo(), articulo.getCodigo())) {
                    item.setPrecioPersonalizado(request.precioPersonalizado);
                    break;
                }
            }
        }

        OperationMessage message = new OperationMessage();
        message.status = "ARTICULO_AGREGADO";
        message.severity = "info";
        message.summary = "Artículo agregado";
        message.detail = articulo.getNombre();
        return Response.ok(ApiResponse.ok(message)).build();
    }

    /**
     * Removes the first cart line matching the article code, delegating to
     * {@link CarritoService#removeArticulo(CartSessionContext, ArticuloCarrito, Users)}
     * (which also re-processes promotions), like CrearTiqueteController.removeArticulo.
     */
    @DELETE
    @Path("/item/{codigo}")
    @Operation(summary = "Remove a cart line by article code (delegates to CarritoService.removeArticulo)")
    public Response removeItem(@PathParam("codigo") long codigo) {
        String username = currentUsername();
        if (username == null) {
            return unauthenticated();
        }
        Users currentUser = loginService.findByUsername(username);
        if (currentUser == null) {
            return userNotProvisioned(username);
        }

        CartSessionContext ctx = cartSessionStore.getOrCreate(username).getCartContext();
        ArticuloCarrito target = findLineByArticuloCodigo(ctx, codigo);
        if (target == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ApiResponse.error("LINEA_NO_ENCONTRADA",
                            "El artículo no está en el carrito"))
                    .build();
        }

        carritoService.removeArticulo(ctx, target, currentUser);
        OperationMessage message = new OperationMessage();
        message.status = "ARTICULO_ELIMINADO";
        message.severity = "info";
        message.summary = "Artículo eliminado";
        message.detail = null;
        return Response.ok(ApiResponse.ok(message)).build();
    }

    // ── Client / payments ───────────────────────────────────────────────

    /**
     * Selects the sale client onto the shared context, mirroring
     * CrearTiqueteController.selectCliente (including the points-discount reset).
     */
    @POST
    @Path("/client")
    @Operation(summary = "Select the client for the caller's cart (mirrors selectCliente)")
    public Response selectClient(@Nullable ClientRequest request) {
        String username = currentUsername();
        if (username == null) {
            return unauthenticated();
        }
        if (request == null || request.clientCode == null) {
            return badRequest("VALIDATION_ERROR", "clientCode es requerido");
        }
        Clients cliente = clientService.find(request.clientCode);
        if (cliente == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ApiResponse.error("CLIENTE_NO_ENCONTRADO",
                            "No existe un cliente con ese código"))
                    .build();
        }

        CartSessionContext ctx = cartSessionStore.getOrCreate(username).getCartContext();
        ctx.setSelectedClient(cliente);
        // selectCliente(): changing client resets any staged points discount.
        ctx.setDescuentoPuntos(BigDecimal.ZERO);

        ClientSummary summary = new ClientSummary();
        summary.code = cliente.getCode();
        summary.name = cliente.getName();
        summary.email = cliente.getEmail();
        summary.idNumber = cliente.getIdNumber();
        summary.puntosAcumulados = cliente.getPuntosAcumulados();
        return Response.ok(ApiResponse.ok(summary)).build();
    }

    /**
     * Stages the split-payment entries for the caller's session (the REST twin
     * of the JSF pagos list) and immediately computes change via
     * {@link CarritoService#calcularVuelto(CartSessionContext, BigDecimal)},
     * mirroring CrearTiqueteController.calcularVuelto()'s prelude.
     */
    @POST
    @Path("/payment-entries")
    @Operation(summary = "Stage payment entries and compute change (delegates to CarritoService.calcularVuelto)")
    public Response paymentEntries(@Nullable List<PagoEntry> pagos) {
        String username = currentUsername();
        if (username == null) {
            return unauthenticated();
        }
        if (pagos == null || pagos.isEmpty()) {
            return badRequest("VALIDATION_ERROR", "Debe enviar al menos una entrada de pago");
        }

        CartSessionStore.Entry entry = cartSessionStore.getOrCreate(username);
        CartSessionContext ctx = entry.getCartContext();
        entry.setPagos(pagos);

        // calcularVuelto() prelude: total paid across entries lands on the ctx.
        BigDecimal total = BigDecimal.ZERO;
        for (PagoEntry pago : pagos) {
            if (pago.getMonto() != null) {
                total = total.add(pago.getMonto());
            }
        }
        ctx.setColones(total);
        ctx.setDolares(BigDecimal.ZERO);
        ctx.setTotalPagado(total);
        // Second parameter is ignored by CarritoService.calcularVuelto (verified
        // in source); BigDecimal.ZERO avoids injecting the JSF TipoCambioController.
        carritoService.calcularVuelto(ctx, BigDecimal.ZERO);

        PaymentSummary summary = new PaymentSummary();
        summary.totalPagado = ctx.getTotalPagado();
        summary.vuelto = ctx.getVuelto();
        summary.vueltoString = carritoService.getVueltoString(ctx);
        return Response.ok(ApiResponse.ok(summary)).build();
    }

    // ── Facturar (same creation pipeline as CrearTiqueteController) ─────

    /**
     * Mirrors {@code CrearTiqueteController.verificarPago()} + {@code facturar()}:
     * payment sufficiency gate, then the SAME creation pipeline (override gate,
     * settings gate, strategy resolution, inventory adjustment,
     * {@code ComprobanteService.crearComprobante}, loyalty redemption, PDF
     * generation, Hacienda-gated client email, cart cleanup).
     *
     * <p>Returns {@code {pdfUrl}} pointing at GET /api/app/pos/facturas/{file},
     * which streams the generated PDF bytes as application/octet-stream.</p>
     */
    @POST
    @Path("/facturar")
    @Operation(summary = "Invoice the caller's cart through the same pipeline as CrearTiqueteController.facturar")
    public Response facturar(@Nullable FacturarRequest request) {
        String tipoDocumento = request == null || request.tipoDocumento == null
                || request.tipoDocumento.isBlank() ? "04" : request.tipoDocumento.trim();
        // Null puntos means "use the staged ctx discount" inside doFacturar;
        // explicit JSON values keep their original semantics verbatim.
        BigDecimal puntos = request == null ? null : request.puntosARedimir;
        return doFacturar(tipoDocumento, request == null ? null : request.pagos, puntos);
    }

    /**
     * The facturar pipeline shared verbatim by the JSON endpoint and the
     * HTMX form twin ({@code POST /facturar-form}). Mirrors
     * {@code CrearTiqueteController.verificarPago()} + {@code facturar()}:
     * payment sufficiency gate, then the SAME creation pipeline (override gate,
     * settings gate, strategy resolution, inventory adjustment,
     * {@code ComprobanteService.crearComprobante}, loyalty redemption, PDF
     * generation, Hacienda-gated client email, cart cleanup).
     *
     * <p>{@code pagos} null/empty falls back to the staged /payment-entries
     * entries, then to a single efectivo entry for the fresh cart total.
     * {@code puntosARedimir} null falls back to the value staged via
     * POST /puntos-form (additive T37 semantics; an explicit JSON zero still
     * forces zero).</p>
     *
     * <p>Returns {@code {pdfUrl}} pointing at GET /api/app/pos/facturas/{file},
     * which streams the generated PDF bytes as application/octet-stream.</p>
     */
    private Response doFacturar(@Nonnull String tipoDocumento,
            @Nullable List<PagoEntry> pagosParam, @Nullable BigDecimal puntosParam) {
        String username = currentUsername();
        if (username == null) {
            return unauthenticated();
        }
        Users currentUser = loginService.findByUsername(username);
        if (currentUser == null) {
            return userNotProvisioned(username);
        }

        CartSessionStore.Entry entry = cartSessionStore.getOrCreate(username);
        CartSessionContext ctx = entry.getCartContext();

        // 1. Price-override gate (controller lines 438-442): overrides need a
        //    supervisor authorization recorded via POST /override-authorize.
        if (hasOverridesInCarrito(ctx) && entry.getAuthorizedBy() == null) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(ApiResponse.error("SUPERVISOR_REQUIRED",
                            "El carrito tiene precios personalizados; se requiere autorización de supervisor"))
                    .build();
        }

        // 2. Settings gate (controller lines 443-446). Delta vs the controller's
        //    NPE-on-null: an explicit envelope instead (see evidence notes).
        AppSettings settings = appSettingsService.returnCurrent();
        if (settings == null) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(ApiResponse.error("NO_SETTINGS",
                            "No hay configuración de la aplicación; configure los datos de emisión"))
                    .build();
        }
        if (Objects.equals(settings.getEstatus(), Boolean.FALSE)) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(ApiResponse.error("FACTURACION_DESHABILITADA",
                            "La facturación está deshabilitada en la configuración"))
                    .build();
        }

        // 3. Cart gate — revisarCarrito() parity (controller.revisarCarrito).
        CartOperationResult revision = carritoService.revisarCarrito(ctx);
        if (revision.status == CartOperationResult.Status.CARRITO_VACIO) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(ApiResponse.error("CARRITO_VACIO",
                            revision.detail != null ? revision.detail
                                    : "Agregue artículos al carrito antes de continuar"))
                    .build();
        }

        // 4. Strategy + receptor requirement (controller lines 447-455).
        DocumentoStrategy strategy = strategyFactory.forCode(tipoDocumento);
        Clients cliente = ctx.getSelectedClient();
        boolean clienteValido = cliente != null && cliente.getCode() != 0;
        if (strategy.requiresReceptor() && !clienteValido) {
            return badRequest("CLIENTE_REQUERIDO",
                    "Para emitir una Factura Electrónica debe seleccionar un cliente.");
        }

        // 5. Points redemption guards (calcularDescuentoPuntos parity: rate
        //    1 punto = ₡1, clamped to the available balance). Null falls back
        //    to the value staged via POST /puntos-form.
        BigDecimal requestedPuntos = puntosParam != null
                ? puntosParam.max(BigDecimal.ZERO) : ctx.getDescuentoPuntos();
        BigDecimal puntosARedimir = requestedPuntos == null
                ? BigDecimal.ZERO : requestedPuntos.max(BigDecimal.ZERO);
        BigDecimal descuentoPuntos = BigDecimal.ZERO;
        if (puntosARedimir.compareTo(BigDecimal.ZERO) > 0) {
            if (!clienteValido) {
                return badRequest("PUNTOS_SIN_CLIENTE",
                        "Debe seleccionar un cliente para usar puntos.");
            }
            BigDecimal available = cliente.getPuntosAcumulados() == null
                    ? BigDecimal.ZERO : cliente.getPuntosAcumulados();
            if (puntosARedimir.compareTo(available) > 0) {
                puntosARedimir = available;
            }
            descuentoPuntos = puntosARedimir;
            ctx.setDescuentoPuntos(descuentoPuntos);
        }

        // 6. Payments: parameter wins over staged entries; empty falls back to
        //    a single efectivo entry for the fresh cart total (controller lines
        //    459-465, with the documented fresh-total delta).
        List<PagoEntry> pagos = pagosParam != null && !pagosParam.isEmpty()
                ? pagosParam : entry.getPagos();
        if (pagos == null || pagos.isEmpty()) {
            PagoEntry fallback = new PagoEntry();
            fallback.setMetodoPago("01");
            fallback.setMonto(carritoService.calculateTotalCarrito(ctx));
            pagos = List.of(fallback);
        }

        // 7. Payment sufficiency (verificarPago parity): compute change first.
        BigDecimal totalPagado = BigDecimal.ZERO;
        for (PagoEntry pago : pagos) {
            if (pago.getMonto() != null) {
                totalPagado = totalPagado.add(pago.getMonto());
            }
        }
        ctx.setColones(totalPagado);
        ctx.setDolares(BigDecimal.ZERO);
        ctx.setTotalPagado(totalPagado);
        carritoService.calcularVuelto(ctx, BigDecimal.ZERO);
        if (ctx.getVuelto() == null || ctx.getVuelto().compareTo(BigDecimal.ZERO) < 0) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(ApiResponse.error("FALTANTE_DE_PAGO",
                            "NO SE HA CANCELADO EL TOTAL DE LA FACTURA"))
                    .build();
        }
        // Captured BEFORE cleanup so the payload reports the real change (the
        // prep-lane version read the field after resetting it to zero).
        BigDecimal vueltoFinal = ctx.getVuelto();

        // 8. THE creation pipeline (controller lines 456-474).
        for (var ac : ctx.getCarrito()) {
            if (ac != null && ac.getArticulo() != null && ac.getArticulo().getCodigoCabys() != null) {
                String imp = ac.getArticulo().getCodigoCabys().getImpuesto();
                if (imp != null) {
                    String norm = imp.trim().replace("%", "").trim();
                    try {
                        BigDecimal bd = new BigDecimal(norm);
                        bd = bd.stripTrailingZeros();
                        norm = bd.toPlainString();
                    } catch (NumberFormatException ignored) {
                        norm = "0";
                    }
                    if (norm.isBlank()) norm = "0";
                    ac.getArticulo().getCodigoCabys().setImpuesto(norm);
                }
            }
        }
        carritoService.ajustarInventario(ctx, currentUser);
        ComprobanteService.CrearComprobanteResult result = comprobanteService.crearComprobante(
                settings,
                ctx.getCarrito(),
                cliente,
                cliente,
                currentUser,
                strategy,
                pagos
        );
        if (result == null || result.comprobante == null) {
            return Response.serverError()
                    .entity(ApiResponse.error("COMPROBANTE_ERROR",
                            "No se pudo crear el comprobante; revise la bitácora de alertas"))
                    .build();
        }
        ComprobantesEmitidos comprobante = result.comprobante;

        // 9. Loyalty redemption (controller lines 480-491).
        if (cliente != null && cliente.getCode() > 0
                && descuentoPuntos.compareTo(BigDecimal.ZERO) > 0
                && puntosARedimir.compareTo(BigDecimal.ZERO) > 0) {
            try {
                loyaltyService.redeemPoints(cliente, puntosARedimir);
            } catch (RuntimeException e) {
                alertasService.registrarAlerta("Error Puntos",
                        "Error al canjear puntos: " + e.getMessage(),
                        currentUser, 0, "PosResource.facturar()", null, e.getMessage());
            }
        }

        // 10. PDF generation (controller lines 502-514). generarPDFTiqueteElectronico
        //     writes the file BEFORE reading FacesContext for an absolute base URL,
        //     which throws outside a JSF request; the controller already tolerates
        //     RuntimeException here, and we serve the bytes ourselves below.
        try {
            pdfGenerator.generarPDFTiqueteElectronico(
                    comprobante,
                    settings,
                    ctx.getCarrito(),
                    cliente != null ? cliente : new Clients(),
                    currentUser,
                    ctx.getPago(),
                    ctx.getVuelto(),
                    pagos
            );
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error Facturación",
                    "Error during PDF generation: " + e.getMessage(),
                    currentUser, 0, "PosResource.facturar", null, e.getMessage());
            LOG.log(Level.WARNING, "PDF generation warning for comprobante "
                    + comprobante.getId(), e);
        }
        String fileName = "tiqueteElectronico_" + comprobante.getId() + ".pdf";
        File pdfFile = new File(dirService.getFacturasDirPath(), fileName);
        if (!pdfFile.isFile()) {
            return Response.serverError()
                    .entity(ApiResponse.error("PDF_NO_GENERADO",
                            "El comprobante fue creado pero el PDF no pudo generarse"))
                    .build();
        }

        // 11. Client email only when Hacienda accepted (controller lines 516-525).
        if (result.haciendaEnviado) {
            try {
                comprobanteService.enviarFacturaACliente(
                        comprobante,
                        cliente,
                        currentUser,
                        ctx.getPago(),
                        ctx.getVuelto(),
                        pagos
                );
            } catch (RuntimeException e) {
                alertasService.registrarAlerta("Error Facturación",
                        "Error enviando factura al cliente: " + e.getMessage(),
                        currentUser, 0, "PosResource.facturar", null, e.getMessage());
            }
        }

        // 12. Cleanup: drop the whole session entry (staged payments, supervisor
        //     authorization and cart die together) — the REST twin of throwing
        //     the JSF view away. The next getOrCreate starts a fresh cart.
        cartSessionStore.remove(username);

        FacturarResult out = new FacturarResult();
        out.pdfUrl = uriBasePath() + "api/app/pos/facturas/" + fileName;
        out.comprobanteId = comprobante.getId();
        out.consecutivo = comprobante.getEncabezado() != null
                ? comprobante.getEncabezado().getNumeroConsecutivo() : null;
        out.haciendaEstado = comprobante.getHaciendaEstado();
        out.haciendaMensaje = result.haciendaMensaje;
        out.totalPagado = totalPagado;
        out.vuelto = vueltoFinal;
        return Response.ok(ApiResponse.ok(out)).build();
    }

    // ── Cancel / debug summary ──────────────────────────────────────────

    /**
     * Cancels the whole sale, delegating to
     * {@link CarritoService#cancel(CartSessionContext, Users)} exactly like
     * CrearTiqueteController.cancel(). As REST session hygiene it additionally
     * drops staged payments, any supervisor authorization and the staged
     * payment/change fields (the JSF world got this for free from the view
     * being thrown away; here the session survives across requests).
     */
    @POST
    @Path("/cancel")
    @Operation(summary = "Cancel the caller's cart (delegates to CarritoService.cancel)")
    public Response cancel() {
        String username = currentUsername();
        if (username == null) {
            return unauthenticated();
        }
        Users currentUser = loginService.findByUsername(username);
        if (currentUser == null) {
            return userNotProvisioned(username);
        }

        CartSessionStore.Entry entry = cartSessionStore.getOrCreate(username);
        CartSessionContext ctx = entry.getCartContext();
        CartOperationResult result = carritoService.cancel(ctx, currentUser);
        entry.setPagos(new ArrayList<>());
        entry.setAuthorizedBy(null);
        ctx.setTotalPagado(BigDecimal.ZERO);
        ctx.setVuelto(BigDecimal.ZERO);
        ctx.setColones(BigDecimal.ZERO);
        ctx.setDolares(BigDecimal.ZERO);
        ctx.setDescuentoPuntos(BigDecimal.ZERO);
        return Response.ok(ApiResponse.ok(toMessage(result))).build();
    }

    /**
     * Debug summary of the caller's cart: lines, totals (via the SAME
     * CarritoCalculations helpers the JSF view binds), client, payments and
     * override state.
     */
    @GET
    @Path("/cart")
    @Operation(summary = "Debug summary of the caller's cart state")
    public Response cart() {
        String username = currentUsername();
        if (username == null) {
            return unauthenticated();
        }
        return Response.ok(ApiResponse.ok(toCartSummary(cartSessionStore.getOrCreate(username))))
                .build();
    }

    // ── Supervisor price-override authorization ─────────────────────────

    /**
     * Supervisor re-authorization for price overrides, mirroring
     * {@code AppAuthResource.supervisorAuthorize} (LoginService lookup + BCrypt
     * verify + role derivation). On success the supervisor username is recorded
     * on the CALLER's cart session so POST /facturar may proceed past the
     * override gate (the REST twin of CrearTiqueteController.authorize()).
     */
    @POST
    @Path("/override-authorize")
    @Operation(summary = "Re-authorize a supervisor for price overrides on the caller's session")
    public Response overrideAuthorize(@Nullable OverrideAuthRequest request) {
        String username = currentUsername();
        if (username == null) {
            return unauthenticated();
        }
        String supervisor = request == null ? null : request.username;
        String password = request == null ? null : request.password;
        if (supervisor == null || supervisor.isBlank() || password == null || password.isBlank()) {
            return invalidCredentials();
        }

        try {
            // Same delegation chain as AppAuthResource.supervisorAuthorize():
            // findByUsername filters status=true; explicit checks keep the
            // disabled-user contract obvious and null-safe.
            Users authUser = loginService.findByUsername(supervisor);
            if (authUser == null) {
                alertasService.registrarAlerta("Autorización Fallida",
                        "Intento con usuario inexistente: " + supervisor,
                        null, 0, "PosResource.overrideAuthorize()", null, null);
                return invalidCredentials();
            }
            if (!Boolean.TRUE.equals(authUser.getStatus())) {
                alertasService.registrarAlerta("Autorización Fallida",
                        "Intento con usuario deshabilitado: " + supervisor,
                        null, 0, "PosResource.overrideAuthorize()", null, null);
                return invalidCredentials();
            }
            if (!loginService.verifyPassword(password, authUser.getPassword())) {
                alertasService.registrarAlerta("Autorización Fallida",
                        "Contraseña incorrecta de: " + supervisor,
                        null, 0, "PosResource.overrideAuthorize()", null, null);
                return invalidCredentials();
            }

            cartSessionStore.getOrCreate(username).setAuthorizedBy(authUser.getUsername());
            return Response.ok(ApiResponse.ok(
                    new AppAuthResource.SupervisorAuthorizationDTO(
                            authUser.getUsername(), deriveRoles(authUser)))).build();
        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error",
                    "Error en overrideAuthorize: " + e.getMessage(),
                    null, 0, "PosResource.overrideAuthorize()", null, e.getMessage());
            LOG.log(Level.WARNING, "Error during POS supervisor authorization", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error durante la autorización"))
                    .build();
        }
    }

    // ── Generated invoice PDF streaming ─────────────────────────────────

    /**
     * Streams a previously generated tiquete PDF as application/octet-stream.
     * File names must match {@code tiqueteElectronico_<id>.pdf} (regex guard
     * against path traversal); missing files yield a 404 envelope.
     */
    @GET
    @Path("/facturas/{fileName:.+}")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Operation(summary = "Stream a generated invoice PDF by file name")
    public Response facturaPdf(@PathParam("fileName") String fileName) {
        String username = currentUsername();
        if (username == null) {
            return unauthenticated();
        }
        if (fileName == null || !fileName.matches(FACTURA_FILE_PATTERN)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ApiResponse.error("ARCHIVO_INVALIDO",
                            "Nombre de archivo no válido"))
                    .build();
        }
        File pdfFile = new File(dirService.getFacturasDirPath(), fileName);
        if (!pdfFile.isFile()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(ApiResponse.error("ARCHIVO_NO_ENCONTRADO",
                            "No existe el PDF solicitado"))
                    .build();
        }
        StreamingOutput stream = (OutputStream out) -> Files.copy(pdfFile.toPath(), out);
        return Response.ok(stream, MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                .build();
    }

    // ── T37: Qute POS page fragments & form twins ───────────────────────

    /**
     * Cart panel fragment ({@code #cart-panel}) rendered from the caller's
     * {@link CartSessionContext} snapshot. HTMX swap target of every
     * {@code -form} twin below.
     */
    @GET
    @Path("/cart-panel")
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Cart panel HTML fragment for the caller's session")
    public Response cartPanel() {
        String username = currentUsername();
        if (username == null) {
            return unauthenticated();
        }
        return htmlOk(cartPanelTemplate.data("panel", panelModel(username, null)).render());
    }

    /**
     * Form twin of {@link #scan} for the barcode input: same delegation to
     * {@link CarritoService#processCodigoBarra(CartSessionContext)}, but the
     * response is the refreshed cart panel fragment so one hx-post both adds
     * the item and redraws the sale.
     */
    @POST
    @Path("/scan-form")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Scan a barcode (form twin) and return the refreshed cart panel")
    public Response scanForm(
            @FormParam("codigoBarra") @Nullable String codigoBarra,
            @FormParam("cantidad") @Nullable String cantidad) {
        String username = currentUsername();
        if (username == null) {
            return unauthenticated();
        }
        CartSessionContext ctx = cartSessionStore.getOrCreate(username).getCartContext();
        ctx.setCodigoBarra(codigoBarra);
        ctx.setCantidadArticulo(parsePositiveOrDefault(cantidad));

        CartOperationResult result = carritoService.processCodigoBarra(ctx);
        return htmlOk(cartPanelTemplate
                .data("panel", panelModel(username, toMessage(result))).render());
    }

    /**
     * Quantity +/- control. Positive deltas grow the line; a delta that
     * reaches zero or below delegates to
     * {@link CarritoService#removeArticulo(CartSessionContext, ArticuloCarrito, Users)}
     * (full removal parity). Otherwise the row cantidad is adjusted in place
     * and {@link CarritoService#verificarPromocionesCarrito(CartSessionContext)}
     * re-processes promotions over the mutated cart — controller-level
     * orchestration only, no service edits.
     */
    @POST
    @Path("/item/{codigo}/qty-form")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Adjust a cart line quantity (+/-) and return the refreshed cart panel")
    public Response qtyForm(@PathParam("codigo") long codigo,
            @FormParam("delta") @Nullable Integer delta) {
        String username = currentUsername();
        if (username == null) {
            return unauthenticated();
        }
        int step = delta == null ? 0 : delta;
        if (step == 0) {
            return htmlOk(cartPanelTemplate.data("panel",
                    panelModel(username, message("VALIDATION_ERROR", "error",
                            "Delta inválido", null))).render());
        }
        Users currentUser = loginService.findByUsername(username);
        if (currentUser == null) {
            return userNotProvisioned(username);
        }
        CartSessionContext ctx = cartSessionStore.getOrCreate(username).getCartContext();
        ArticuloCarrito target = findLineByArticuloCodigo(ctx, codigo);
        if (target == null) {
            return htmlOk(cartPanelTemplate.data("panel",
                    panelModel(username, message("LINEA_NO_ENCONTRADA", "error",
                            "El artículo no está en el carrito", null))).render());
        }

        OperationMessage mensaje;
        BigDecimal actual = target.getCantidad() == null ? BigDecimal.ZERO : target.getCantidad();
        BigDecimal nuevaCantidad = actual.add(BigDecimal.valueOf(step));
        if (nuevaCantidad.compareTo(BigDecimal.ZERO) <= 0) {
            carritoService.removeArticulo(ctx, target, currentUser);
            mensaje = message("ARTICULO_ELIMINADO", "info", "Artículo eliminado", null);
        } else {
            target.setCantidad(nuevaCantidad);
            carritoService.verificarPromocionesCarrito(ctx);
            mensaje = message("CANTIDAD_ACTUALIZADA", "info", "Cantidad actualizada", null);
        }
        return htmlOk(cartPanelTemplate
                .data("panel", panelModel(username, mensaje)).render());
    }

    /**
     * Form twin of {@link #removeItem}: identical delegation to
     * {@link CarritoService#removeArticulo}, answered with the refreshed panel.
     */
    @POST
    @Path("/item/{codigo}/remove-form")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Remove a cart line (form twin) and return the refreshed cart panel")
    public Response removeForm(@PathParam("codigo") long codigo) {
        String username = currentUsername();
        if (username == null) {
            return unauthenticated();
        }
        Users currentUser = loginService.findByUsername(username);
        if (currentUser == null) {
            return userNotProvisioned(username);
        }
        CartSessionContext ctx = cartSessionStore.getOrCreate(username).getCartContext();
        ArticuloCarrito target = findLineByArticuloCodigo(ctx, codigo);
        if (target == null) {
            return htmlOk(cartPanelTemplate.data("panel",
                    panelModel(username, message("LINEA_NO_ENCONTRADA", "error",
                            "El artículo no está en el carrito", null))).render());
        }
        carritoService.removeArticulo(ctx, target, currentUser);
        return htmlOk(cartPanelTemplate.data("panel",
                panelModel(username, message("ARTICULO_ELIMINADO", "info",
                        "Artículo eliminado", null))).render());
    }

    /**
     * Compact typeahead feed for the POS client picker (autocomplete
     * replacement). Blank {@code q} lists the first page like the legacy
     * client dialog; otherwise {@link ClientService#searchByName(String)}.
     * Capped at 10 hits — keyboard-first pickers never scroll further.
     */
    @GET
    @Path("/client-search")
    @Operation(summary = "Typeahead search of clients (code/name/puntos, max 10)")
    public Response clientSearch(@QueryParam("q") @Nullable String q) {
        String username = currentUsername();
        if (username == null) {
            return unauthenticated();
        }
        List<Clients> matches;
        if (q == null || q.isBlank()) {
            List<Clients> firstPage = clientService.listPage(0, TYPEAHEAD_LIMIT);
            matches = firstPage != null ? firstPage : List.of();
        } else {
            List<Clients> found = clientService.searchByName(q.trim());
            matches = found == null ? List.of()
                    : found.subList(0, Math.min(found.size(), TYPEAHEAD_LIMIT));
        }
        List<ClientSummary> hits = new ArrayList<>();
        for (Clients cliente : matches) {
            ClientSummary summary = new ClientSummary();
            summary.code = cliente.getCode();
            summary.name = cliente.getName();
            summary.email = cliente.getEmail();
            summary.idNumber = cliente.getIdNumber();
            summary.puntosAcumulados = cliente.getPuntosAcumulados();
            hits.add(summary);
        }
        return Response.ok(ApiResponse.ok(hits)).build();
    }

    /**
     * Client picker fragment (reusable include
     * {@code pages/facturas/client-picker.html}) following the T34
     * selector-articulos pattern: search-as-you-type hx-get plus Seleccionar
     * rows that post {@code /client-select-form}.
     */
    @GET
    @Path("/client-picker")
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Client picker HTML fragment (search + select rows)")
    public Response clientPicker(@QueryParam("q") @Nullable String q) {
        String username = currentUsername();
        if (username == null) {
            return unauthenticated();
        }
        Map<String, Object> picker = new LinkedHashMap<>();
        picker.put("q", q == null ? "" : q);
        picker.put("resultados", typeaheadHits(q));
        return htmlOk(clientPickerTemplate.data("picker", picker).render());
    }

    /**
     * Form twin of {@link #selectClient}: same delegation (including the
     * points-discount reset), answered with the refreshed cart panel.
     */
    @POST
    @Path("/client-select-form")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Select the sale client (form twin) and return the refreshed cart panel")
    public Response clientSelectForm(@FormParam("clientCode") @Nullable Integer clientCode) {
        String username = currentUsername();
        if (username == null) {
            return unauthenticated();
        }
        if (clientCode == null) {
            return htmlOk(cartPanelTemplate.data("panel",
                    panelModel(username, message("VALIDATION_ERROR", "error",
                            "clientCode es requerido", null))).render());
        }
        Clients cliente = clientService.find(clientCode);
        if (cliente == null) {
            return htmlOk(cartPanelTemplate.data("panel",
                    panelModel(username, message("CLIENTE_NO_ENCONTRADO", "error",
                            "No existe un cliente con ese código", null))).render());
        }
        CartSessionContext ctx = cartSessionStore.getOrCreate(username).getCartContext();
        ctx.setSelectedClient(cliente);
        ctx.setDescuentoPuntos(BigDecimal.ZERO);
        return htmlOk(cartPanelTemplate.data("panel",
                panelModel(username, message("CLIENTE_SELECCIONADO", "info",
                        "Cliente asignado", cliente.getName()))).render());
    }

    /**
     * Pure points-redemption preview (no mutation): mirrors the facturar clamp
     * rule (1 punto = ₡1, capped at the client balance) so the UI can show the
     * discount before committing.
     */
    @GET
    @Path("/puntos-preview")
    @Operation(summary = "Preview the ₡ discount for N points without mutating the cart")
    public Response puntosPreview(@QueryParam("puntos") @Nullable BigDecimal puntos) {
        String username = currentUsername();
        if (username == null) {
            return unauthenticated();
        }
        CartSessionContext ctx = cartSessionStore.getOrCreate(username).getCartContext();
        Clients cliente = ctx.getSelectedClient();
        if (cliente == null || cliente.getCode() == 0) {
            return badRequest("PUNTOS_SIN_CLIENTE",
                    "Debe seleccionar un cliente para usar puntos.");
        }
        BigDecimal solicitados = puntos == null ? BigDecimal.ZERO : puntos.max(BigDecimal.ZERO);
        BigDecimal balance = cliente.getPuntosAcumulados() == null
                ? BigDecimal.ZERO : cliente.getPuntosAcumulados();
        PuntosPreview preview = new PuntosPreview();
        preview.solicitados = solicitados;
        preview.aplicado = solicitados.min(balance);
        preview.balance = balance;
        return Response.ok(ApiResponse.ok(preview)).build();
    }

    /**
     * Stages the points discount onto the caller's context (display state for
     * the panel; doFacturar consumes it when no explicit value arrives).
     * Zero/negative input clears the staged discount (legacy "Quitar").
     */
    @POST
    @Path("/puntos-form")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Stage (or clear) the points discount and return the refreshed cart panel")
    public Response puntosForm(@FormParam("puntosARedimir") @Nullable String puntos) {
        String username = currentUsername();
        if (username == null) {
            return unauthenticated();
        }
        CartSessionContext ctx = cartSessionStore.getOrCreate(username).getCartContext();
        Clients cliente = ctx.getSelectedClient();
        if (cliente == null || cliente.getCode() == 0) {
            return htmlOk(cartPanelTemplate.data("panel",
                    panelModel(username, message("PUNTOS_SIN_CLIENTE", "error",
                            "Debe seleccionar un cliente para usar puntos.", null))).render());
        }
        BigDecimal solicitados = parseDecimal(puntos);
        OperationMessage mensaje;
        if (solicitados.compareTo(BigDecimal.ZERO) <= 0) {
            ctx.setDescuentoPuntos(BigDecimal.ZERO);
            mensaje = message("PUNTOS_RETIRADOS", "info", "Descuento por puntos retirado", null);
        } else {
            BigDecimal balance = cliente.getPuntosAcumulados() == null
                    ? BigDecimal.ZERO : cliente.getPuntosAcumulados();
            BigDecimal aplicado = solicitados.min(balance);
            ctx.setDescuentoPuntos(aplicado);
            mensaje = message("PUNTOS_APLICADOS", "info", "Descuento por puntos aplicado",
                    aplicado.toPlainString() + " colones");
        }
        return htmlOk(cartPanelTemplate
                .data("panel", panelModel(username, mensaje)).render());
    }

    /**
     * Badge data for the tipo-cambio chip. Read-only consumption of
     * {@link TipoCambioService#getNewestTipoCambio()}; when neither the API nor
     * the DB can answer, the badge reports {@code disponible=false} instead of
     * failing the page.
     */
    @GET
    @Path("/tipo-cambio")
    @Operation(summary = "Current dollar exchange rate for the POS badge")
    public Response tipoCambio() {
        String username = currentUsername();
        if (username == null) {
            return unauthenticated();
        }
        return Response.ok(ApiResponse.ok(tipoCambioBadge())).build();
    }

    /**
     * Payment dialog body (modal fragment): staged payment rows per
     * metodoPago, server-computed split totals and vuelto. Also the response
     * body of {@link #paymentEntriesForm} — single source of truth.
     */
    @GET
    @Path("/payment-dialog")
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Payment dialog HTML fragment (rows + split totals)")
    public Response paymentDialog() {
        String username = currentUsername();
        if (username == null) {
            return unauthenticated();
        }
        return htmlOk(paymentDialogTemplate
                .data("dialog", dialogModel(username, null)).render());
    }

    /**
     * Form twin of {@link #paymentEntries}: zips the metodoPago[]/monto[]
     * arrays into {@link PagoEntry} rows, stages them via the SAME logic
     * (entry.setPagos + totals onto ctx + calcularVuelto) and re-renders the
     * payment dialog with the server-computed split totals.
     */
    @POST
    @Path("/payment-entries-form")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Stage split payments (form twin) and re-render the payment dialog")
    public Response paymentEntriesForm(
            @FormParam("metodoPago") @Nullable List<String> metodos,
            @FormParam("monto") @Nullable List<String> montos) {
        String username = currentUsername();
        if (username == null) {
            return unauthenticated();
        }
        List<PagoEntry> pagos = zipPagos(metodos, montos);
        if (pagos.isEmpty()) {
            return htmlOk(paymentDialogTemplate.data("dialog",
                    dialogModel(username, message("VALIDATION_ERROR", "error",
                            "Debe enviar al menos una entrada de pago", null))).render());
        }
        stagePagos(username, pagos);
        return htmlOk(paymentDialogTemplate.data("dialog",
                dialogModel(username, message("PAGOS_ACTUALIZADOS", "info",
                        "Pagos registrados", null))).render());
    }

    /**
     * Form twin of {@link #overrideAuthorize}: same BCrypt-backed supervisor
     * check and alertas trail; success answers a toast plus an out-of-band
     * refresh of {@code #cart-panel} (the authorization badge lives there),
     * failure re-renders the form with the error.
     */
    @POST
    @Path("/override-authorize-form")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Supervisor authorization (form twin) for price overrides")
    public Response overrideAuthorizeForm(
            @FormParam("username") @Nullable String supervisor,
            @FormParam("password") @Nullable String password) {
        String username = currentUsername();
        if (username == null) {
            return unauthenticated();
        }
        boolean exito = false;
        String errorGeneral = null;
        if (supervisor == null || supervisor.isBlank() || password == null || password.isBlank()) {
            errorGeneral = "Usuario o contraseña incorrectos";
        } else {
            try {
                Users authUser = loginService.findByUsername(supervisor);
                if (authUser == null || !Boolean.TRUE.equals(authUser.getStatus())
                        || !loginService.verifyPassword(password, authUser.getPassword())) {
                    alertasService.registrarAlerta("Autorización Fallida",
                            "Intento fallido de autorización de: " + supervisor,
                            null, 0, "PosResource.overrideAuthorizeForm()", null, null);
                    errorGeneral = "Usuario o contraseña incorrectos";
                } else {
                    cartSessionStore.getOrCreate(username).setAuthorizedBy(authUser.getUsername());
                    exito = true;
                }
            } catch (RuntimeException e) {
                alertasService.registrarAlerta("Error",
                        "Error en overrideAuthorizeForm: " + e.getMessage(),
                        null, 0, "PosResource.overrideAuthorizeForm()", null, e.getMessage());
                LOG.log(Level.WARNING, "Error during POS supervisor authorization", e);
                errorGeneral = "Error durante la autorización";
            }
        }

        Map<String, Object> auth = new LinkedHashMap<>();
        auth.put("exito", exito);
        auth.put("errorGeneral", errorGeneral);
        if (exito) {
            Map<String, Object> oobPanel = panelModel(username,
                    message("SUPERVISOR_AUTORIZADO", "success",
                            "Autorización de supervisor registrada", supervisor));
            oobPanel.put("oob", true);
            auth.put("panelHtml", cartPanelTemplate.data("panel", oobPanel).render());
        } else {
            auth.put("panelHtml", null);
        }
        return htmlOk(authModalBodyTemplate.data("auth", auth).render());
    }

    /**
     * Form twin of {@link #facturar}: accepts tipoDocumento, optional
     * puntosARedimir and the metodoPago[]/monto[] arrays from the payment
     * dialog, runs the SAME {@link #doFacturar} pipeline and renders
     * {@code pages/facturas/facturar-resultado.html}: success swaps the PDF
     * link in and out-of-band clears {@code #cart-panel}; any guard failure
     * renders the error envelope next to the button with the cart intact.
     */
    @POST
    @Path("/facturar-form")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Invoice the cart (form twin) rendering the result fragment")
    public Response facturarForm(
            @FormParam("tipoDocumento") @Nullable String tipoDocumento,
            @FormParam("puntosARedimir") @Nullable String puntos,
            @FormParam("metodoPago") @Nullable List<String> metodos,
            @FormParam("monto") @Nullable List<String> montos) {
        String username = currentUsername();
        if (username == null) {
            return unauthenticated();
        }
        String documento = tipoDocumento == null || tipoDocumento.isBlank()
                ? "04" : tipoDocumento.trim();
        List<PagoEntry> pagos = zipPagos(metodos, montos);
        BigDecimal puntosParam = puntos == null || puntos.isBlank() ? null : parseDecimal(puntos);

        Response result = doFacturar(documento, pagos.isEmpty() ? null : pagos, puntosParam);
        return htmlOk(facturarResultadoTemplate
                .data("resultado", resultadoModel(username, result)).render());
    }

    /**
     * Form twin of {@link #cancel}: same delegation to
     * {@link CarritoService#cancel} plus the REST session hygiene, answered
     * with the fresh empty panel.
     */
    @POST
    @Path("/cancel-form")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Cancel the sale (form twin) and return the fresh cart panel")
    public Response cancelForm() {
        String username = currentUsername();
        if (username == null) {
            return unauthenticated();
        }
        Users currentUser = loginService.findByUsername(username);
        if (currentUser == null) {
            return userNotProvisioned(username);
        }
        CartSessionStore.Entry entry = cartSessionStore.getOrCreate(username);
        CartSessionContext ctx = entry.getCartContext();
        CartOperationResult result = carritoService.cancel(ctx, currentUser);
        entry.setPagos(new ArrayList<>());
        entry.setAuthorizedBy(null);
        ctx.setTotalPagado(BigDecimal.ZERO);
        ctx.setVuelto(BigDecimal.ZERO);
        ctx.setColones(BigDecimal.ZERO);
        ctx.setDolares(BigDecimal.ZERO);
        ctx.setDescuentoPuntos(BigDecimal.ZERO);
        return htmlOk(cartPanelTemplate
                .data("panel", panelModel(username, toMessage(result))).render());
    }

    // ── T37 fragment model builders (package-private: PosPageResource reuses) ──

    /**
     * Cart-panel view model straight off the caller's session. Every number
     * comes from {@link CarritoService} / the row helpers — no local math.
     */
    @Nonnull
    Map<String, Object> panelModel(@Nonnull String username, @Nullable OperationMessage mensaje) {
        CartSessionStore.Entry entry = cartSessionStore.getOrCreate(username);
        CartSessionContext ctx = entry.getCartContext();

        List<Map<String, Object>> items = new ArrayList<>();
        for (ArticuloCarrito item : ctx.getCarrito()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("codigo", item.getArticulo() != null ? item.getArticulo().getCodigo() : null);
            row.put("nombre", item.getArticulo() != null ? item.getArticulo().getNombre() : null);
            row.put("cantidad", item.getCantidad());
            row.put("precioEfectivo", item.getPrecioEfectivo());
            row.put("totalLinea", item.getTotalArticulo());
            row.put("promo", item.isPromo());
            row.put("precioPersonalizado", item.getPrecioPersonalizado());
            items.add(row);
        }

        Map<String, Object> panel = new LinkedHashMap<>();
        panel.put("oob", false);
        panel.put("items", items);
        panel.put("vacio", items.isEmpty());
        panel.put("totalCarrito", carritoService.calculateTotalCarrito(ctx));
        panel.put("totalDescuento", carritoService.calculateTotalCarritoDescuento(ctx));
        panel.put("totalImpuesto", carritoService.calculateTotalCarritoImpuesto(ctx));
        panel.put("descuentoPuntos", ctx.getDescuentoPuntos());
        panel.put("totalPagado", ctx.getTotalPagado());
        panel.put("vuelto", ctx.getVuelto());
        panel.put("vueltoString", carritoService.getVueltoString(ctx));
        panel.put("hasOverrides", hasOverridesInCarrito(ctx));
        panel.put("authorizedBy", entry.getAuthorizedBy());

        Clients cliente = ctx.getSelectedClient();
        boolean clienteValido = cliente != null && cliente.getCode() != 0;
        panel.put("clienteCode", clienteValido ? cliente.getCode() : null);
        panel.put("clienteName", clienteValido ? cliente.getName() : null);
        panel.put("clientePuntos", clienteValido ? cliente.getPuntosAcumulados() : null);
        panel.put("mensaje", mensajeToMap(mensaje));
        return panel;
    }

    /** Payment-dialog view model: staged rows + server-computed totals. */
    @Nonnull
    Map<String, Object> dialogModel(@Nonnull String username, @Nullable OperationMessage mensaje) {
        CartSessionStore.Entry entry = cartSessionStore.getOrCreate(username);
        CartSessionContext ctx = entry.getCartContext();

        List<Map<String, Object>> filas = new ArrayList<>();
        for (PagoEntry pago : entry.getPagos()) {
            Map<String, Object> fila = new LinkedHashMap<>();
            fila.put("metodoPago", pago.getMetodoPago());
            fila.put("monto", pago.getMonto());
            filas.add(fila);
        }

        Map<String, Object> dialog = new LinkedHashMap<>();
        dialog.put("filas", filas);
        dialog.put("metodos", METODOS_PAGO);
        dialog.put("totalCarrito", carritoService.calculateTotalCarrito(ctx));
        dialog.put("descuentoPuntos", ctx.getDescuentoPuntos());
        dialog.put("totalPagado", ctx.getTotalPagado());
        dialog.put("vuelto", ctx.getVuelto());
        dialog.put("vueltoString", carritoService.getVueltoString(ctx));
        dialog.put("mensaje", mensajeToMap(mensaje));
        return dialog;
    }

    /** Facturar-result view model derived from the shared pipeline Response. */
    @Nonnull
    private Map<String, Object> resultadoModel(@Nonnull String username, @Nonnull Response response) {
        Map<String, Object> resultado = new LinkedHashMap<>();
        Object entity = response.getEntity();
        boolean exito = response.getStatus() == 200 && entity instanceof ApiResponse<?> envelope
                && envelope.getData() instanceof FacturarResult;
        resultado.put("exito", exito);
        if (exito) {
            FacturarResult data = (FacturarResult) ((ApiResponse<?>) entity).getData();
            resultado.put("pdfUrl", data.pdfUrl);
            resultado.put("consecutivo", data.consecutivo);
            resultado.put("comprobanteId", data.comprobanteId);
            resultado.put("haciendaEstado", data.haciendaEstado);
            resultado.put("haciendaMensaje", data.haciendaMensaje);
            resultado.put("totalPagado", data.totalPagado);
            resultado.put("vuelto", data.vuelto);
        } else if (entity instanceof ApiResponse<?> envelope && envelope.getError() != null) {
            resultado.put("errorCode", envelope.getError().getCode());
            resultado.put("errorMessage", envelope.getError().getMessage());
        } else {
            resultado.put("errorCode", "INTERNAL_ERROR");
            resultado.put("errorMessage", "No se pudo procesar la factura");
        }
        // Out-of-band refresh in BOTH branches: success shows the cleared
        // panel, failure heals any staleness without losing the sale.
        Map<String, Object> oobPanel = panelModel(username, null);
        oobPanel.put("oob", true);
        resultado.put("panelHtml", cartPanelTemplate.data("panel", oobPanel).render());
        return resultado;
    }

    /** Badge view model; nullable fields when no rate is available yet. */
    @Nonnull
    Map<String, Object> tipoCambioBadge() {
        TipoCambio tc = null;
        try {
            tc = tipoCambioService.getNewestTipoCambio();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "tipo-cambio badge unavailable", e);
        }
        Map<String, Object> badge = new LinkedHashMap<>();
        badge.put("disponible", tc != null);
        badge.put("venta", tc != null ? tc.getValorVenta() : null);
        badge.put("compra", tc != null ? tc.getValorCompra() : null);
        badge.put("fecha", tc != null && tc.getFecha() != null ? tc.getFecha().toString() : null);
        return badge;
    }

    /** Typeahead hits shared by the JSON feed and the picker fragment. */
    @Nonnull
    private List<Map<String, Object>> typeaheadHits(@Nullable String q) {
        List<Clients> matches;
        if (q == null || q.isBlank()) {
            List<Clients> firstPage = clientService.listPage(0, TYPEAHEAD_LIMIT);
            matches = firstPage != null ? firstPage : List.of();
        } else {
            List<Clients> found = clientService.searchByName(q.trim());
            matches = found == null ? List.of()
                    : found.subList(0, Math.min(found.size(), TYPEAHEAD_LIMIT));
        }
        List<Map<String, Object>> hits = new ArrayList<>();
        for (Clients cliente : matches) {
            Map<String, Object> hit = new LinkedHashMap<>();
            hit.put("code", cliente.getCode());
            hit.put("name", cliente.getName());
            hit.put("idNumber", cliente.getIdNumber());
            hit.put("puntosAcumulados", cliente.getPuntosAcumulados());
            hits.add(hit);
        }
        return hits;
    }

    /** Shared staging of payment entries (JSON + form twins). */
    private void stagePagos(@Nonnull String username, @Nonnull List<PagoEntry> pagos) {
        CartSessionStore.Entry entry = cartSessionStore.getOrCreate(username);
        CartSessionContext ctx = entry.getCartContext();
        entry.setPagos(pagos);

        BigDecimal total = BigDecimal.ZERO;
        for (PagoEntry pago : pagos) {
            if (pago.getMonto() != null) {
                total = total.add(pago.getMonto());
            }
        }
        ctx.setColones(total);
        ctx.setDolares(BigDecimal.ZERO);
        ctx.setTotalPagado(total);
        carritoService.calcularVuelto(ctx, BigDecimal.ZERO);
    }

    private static @Nonnull List<PagoEntry> zipPagos(
            @Nullable List<String> metodos, @Nullable List<String> montos) {
        if (metodos == null || metodos.isEmpty()) {
            return List.of();
        }
        List<PagoEntry> pagos = new ArrayList<>();
        for (int i = 0; i < metodos.size(); i++) {
            String metodo = metodos.get(i);
            if (metodo == null || metodo.isBlank()) {
                continue;
            }
            PagoEntry pago = new PagoEntry();
            pago.setMetodoPago(metodo.trim());
            pago.setMonto(montos != null && i < montos.size()
                    ? parseDecimal(montos.get(i)) : BigDecimal.ZERO);
            pagos.add(pago);
        }
        return pagos;
    }

    private static @Nonnull BigDecimal parsePositiveOrDefault(@Nullable String raw) {
        BigDecimal parsed = parseDecimal(raw);
        return parsed.compareTo(BigDecimal.ZERO) <= 0 ? BigDecimal.ONE : parsed;
    }

    private static @Nonnull BigDecimal parseDecimal(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(raw.trim().replace(",", ""));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private static @Nonnull OperationMessage message(
            @Nonnull String status, @Nonnull String severity,
            @Nullable String summary, @Nullable String detail) {
        OperationMessage mensaje = new OperationMessage();
        mensaje.status = status;
        mensaje.severity = severity;
        mensaje.summary = summary;
        mensaje.detail = detail;
        return mensaje;
    }

    private static @Nullable Map<String, Object> mensajeToMap(@Nullable OperationMessage mensaje) {
        if (mensaje == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", mensaje.status);
        map.put("severity", mensaje.severity);
        map.put("summary", mensaje.summary);
        map.put("detail", mensaje.detail);
        return map;
    }

    private static Response htmlOk(@Nonnull String html) {
        return Response.ok(html)
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).build();
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** Authenticated principal name (= username) or null when anonymous. */
    private @Nullable String currentUsername() {
        if (securityIdentity == null || securityIdentity.isAnonymous()
                || securityIdentity.getPrincipal() == null) {
            return null;
        }
        return securityIdentity.getPrincipal().getName();
    }

    private @Nullable ArticuloCarrito findLineByArticuloCodigo(
            @Nonnull CartSessionContext ctx, long articuloCodigo) {
        for (ArticuloCarrito item : ctx.getCarrito()) {
            if (item.getArticulo() != null
                    && item.getArticulo().getCodigo() != null
                    && item.getArticulo().getCodigo() == articuloCodigo) {
                return item;
            }
        }
        return null;
    }

    /** Controller parity: any row with a custom price requires supervisor auth. */
    private boolean hasOverridesInCarrito(@Nonnull CartSessionContext ctx) {
        for (ArticuloCarrito item : ctx.getCarrito()) {
            if (item.getPrecioPersonalizado() != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Translation twin of CrearTiqueteController.applyCartOperationResult():
     * FacesMessage becomes {severity,summary,detail}; the PrimeFaces script
     * channel has no REST equivalent, so only the logical status travels.
     */
    private static @Nonnull OperationMessage toMessage(@Nullable CartOperationResult result) {
        OperationMessage message = new OperationMessage();
        if (result == null) {
            message.status = "FALLA_INTERNA";
            return message;
        }
        message.status = result.status.name();
        message.severity = result.severity == null
                ? null : result.severity.name().toLowerCase(java.util.Locale.ROOT);
        message.summary = result.summary;
        message.detail = result.detail;
        return message;
    }

    /**
     * Debug snapshot builder. Every numeric value still comes from
     * {@link CarritoService} (same CarritoCalculations the JSF view binds) —
     * no local re-implementations of the math.
     */
    private @Nonnull CartSummary toCartSummary(@Nonnull CartSessionStore.Entry entry) {
        CartSessionContext ctx = entry.getCartContext();
        CartSummary summary = new CartSummary();
        summary.items = new ArrayList<>();
        for (ArticuloCarrito item : ctx.getCarrito()) {
            CartItem line = new CartItem();
            line.articuloCodigo = item.getArticulo() != null ? item.getArticulo().getCodigo() : null;
            line.nombre = item.getArticulo() != null ? item.getArticulo().getNombre() : null;
            line.cantidad = item.getCantidad();
            line.precioEfectivo = item.getPrecioEfectivo();
            line.descuento = item.getDescuento();
            line.precioPersonalizado = item.getPrecioPersonalizado();
            line.promo = item.isPromo();
            summary.items.add(line);
        }
        summary.totalCarrito = carritoService.calculateTotalCarrito(ctx);
        summary.totalDescuento = carritoService.calculateTotalCarritoDescuento(ctx);
        summary.totalImpuesto = carritoService.calculateTotalCarritoImpuesto(ctx);
        summary.descuentoPuntos = ctx.getDescuentoPuntos();
        summary.totalPagado = ctx.getTotalPagado();
        summary.vuelto = ctx.getVuelto();
        summary.vueltoString = carritoService.getVueltoString(ctx);
        summary.hasOverrides = hasOverridesInCarrito(ctx);
        summary.authorizedBy = entry.getAuthorizedBy();
        if (ctx.getSelectedClient() != null && ctx.getSelectedClient().getCode() != 0) {
            summary.clienteCode = ctx.getSelectedClient().getCode();
            summary.clienteName = ctx.getSelectedClient().getName();
        }
        return summary;
    }

    private Response unauthenticated() {
        return Response.status(Response.Status.UNAUTHORIZED)
                .entity(ApiResponse.error("UNAUTHENTICATED",
                        "No hay una sesión autenticada activa"))
                .build();
    }

    private Response userNotProvisioned(String username) {
        return Response.status(Response.Status.FORBIDDEN)
                .entity(ApiResponse.error("USER_NOT_PROVISIONED",
                        "El usuario autenticado '" + username + "' no existe en el sistema"))
                .build();
    }

    private Response badRequest(String code, String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(ApiResponse.error(code, message))
                .build();
    }

    private Response invalidCredentials() {
        return Response.status(Response.Status.UNAUTHORIZED)
                .entity(ApiResponse.error("INVALID_CREDENTIALS",
                        "Usuario o contraseña incorrectos"))
                .build();
    }

    /** Context-path-relative base ("/Mercurius/") for building stable URLs. */
    private @Nonnull String uriBasePath() {
        String path = uriInfo.getBaseUri().getPath();
        return path != null ? path : "/";
    }

    /**
     * Role derivation copied verbatim from AppAuthResource.deriveRoles() (it is
     * private there and this task forbids editing existing files): groupName
     * substring tokens, admin implies every other role.
     */
    private static @Nonnull List<String> deriveRoles(@Nonnull Users user) {
        String groupName = user.getGroupName() == null ? "" : user.getGroupName().toLowerCase();
        boolean isAdmin = groupName.contains("admin");
        List<String> roles = new ArrayList<>(ROLE_TOKENS.size());
        for (String token : ROLE_TOKENS) {
            if (groupName.contains(token) || (isAdmin && !"admin".equals(token))) {
                roles.add(token);
            }
        }
        return roles;
    }

    private static final List<String> ROLE_TOKENS =
            List.of("admin", "facturacion", "inventario", "usuario", "tributacion", "registro");

    // ── Request/response payloads (public-field DTOs, AppAuthResource style) ──

    /** POST /scan body. */
    public static class ScanRequest {
        public @Nullable String codigoBarra;
        public @Nullable BigDecimal cantidad;
    }

    /** POST /add body. */
    public static class AddRequest {
        public @Nullable Long articuloId;
        public @Nullable BigDecimal cantidad;
        /** Optional supervisor price override bound onto the cart row. */
        public @Nullable BigDecimal precioPersonalizado;
    }

    /** POST /client body. */
    public static class ClientRequest {
        public @Nullable Integer clientCode;
    }

    /** POST /facturar body. */
    public static class FacturarRequest {
        /** Hacienda document code; defaults to TE ("04"). */
        public @Nullable String tipoDocumento;
        /** Optional inline payments; otherwise the staged /payment-entries win. */
        public @Nullable List<PagoEntry> pagos;
        /** Points to redeem (1 punto = ₡1), clamped to the client balance. */
        public @Nullable BigDecimal puntosARedimir;
    }

    /** POST /override-authorize body. */
    public static class OverrideAuthRequest {
        public @Nullable String username;
        public @Nullable String password;
    }

    /** {severity,summary,detail} translation of a CartOperationResult. */
    public static class OperationMessage {
        public @Nullable String status;
        public @Nullable String severity;
        public @Nullable String summary;
        public @Nullable String detail;
    }

    /** Selected-client echo. */
    public static class ClientSummary {
        public int code;
        public @Nullable String name;
        public @Nullable String email;
        public @Nullable String idNumber;
        public @Nullable BigDecimal puntosAcumulados;
    }

    /** POST /payment-entries result. */
    public static class PaymentSummary {
        public @Nullable BigDecimal totalPagado;
        public @Nullable BigDecimal vuelto;
        public @Nullable String vueltoString;
    }

    /** POST /facturar result. */
    public static class FacturarResult {
        public @Nullable String pdfUrl;
        public @Nullable Long comprobanteId;
        public @Nullable String consecutivo;
        public @Nullable String haciendaEstado;
        public @Nullable String haciendaMensaje;
        public @Nullable BigDecimal totalPagado;
        public @Nullable BigDecimal vuelto;
    }

    /** GET /cart debug payload. */
    public static class CartSummary {
        public @Nullable List<CartItem> items;
        public @Nullable BigDecimal totalCarrito;
        public @Nullable BigDecimal totalDescuento;
        public @Nullable BigDecimal totalImpuesto;
        public @Nullable BigDecimal descuentoPuntos;
        public @Nullable BigDecimal totalPagado;
        public @Nullable BigDecimal vuelto;
        public @Nullable String vueltoString;
        public boolean hasOverrides;
        public @Nullable String authorizedBy;
        public @Nullable Integer clienteCode;
        public @Nullable String clienteName;
    }

    /** One cart line in the GET /cart payload. */
    public static class CartItem {
        public @Nullable Long articuloCodigo;
        public @Nullable String nombre;
        public @Nullable BigDecimal cantidad;
        public @Nullable BigDecimal precioEfectivo;
        public @Nullable BigDecimal descuento;
        public @Nullable BigDecimal precioPersonalizado;
        public boolean promo;
    }

    /** GET /puntos-preview payload (pure preview, no cart mutation). */
    public static class PuntosPreview {
        public @Nullable BigDecimal solicitados;
        public @Nullable BigDecimal aplicado;
        public @Nullable BigDecimal balance;
    }
}
