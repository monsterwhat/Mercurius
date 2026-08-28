package Controllers.Api.App;

import Models.Articulos.Articulos;
import Models.DTO.ApiResponse;
import Models.DTO.PagedResponse;
import Models.Inventario;
import Models.Users;
import Services.ArticulosService;
import Services.InventarioService;
import Services.LoginService;
import Utils.AsyncUserContext;
import Utils.DiffUtils;
import Utils.Parsers.Parser;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import io.vertx.ext.web.RoutingContext;
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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.parsers.DocumentBuilderFactory;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Inventario (ajustes) module for the NEW Qute/HTMX app surface (plan task
 * T35): JSON + fragment endpoints replacing the legacy JSF pair
 * {@code Controllers.InventarioController} (4-tab adjustments page,
 * revision wizards, quick-process flow) and the upload dialog of
 * {@code fragments/Inventario/Upload/subirFacturas.xhtml}
 * ({@code uploadController} queue feeding {@link Parser}).
 *
 * <p><b>Behavior parity contract</b> (ported 1:1, receipts in
 * .omo/evidence/t35/parity-matrix.md):</p>
 * <ul>
 *   <li>Tab listings delegate ONLY to existing
 *       {@link InventarioService} queries: activos =
 *       {@code ListAllEnabled()}, inactivos = {@code listAllInactivos()},
 *       procesados = {@code listAllActivosYProcesados()}, pendientes =
 *       {@code listAllSinProcesar()}.</li>
 *   <li>Create adjustment (legacy {@code createInventarioDialog()}):
 *       articulo required ("Articulo Invalido"), usuario = current user,
 *       {@code processed=true}/{@code status=true}, fechaMovimiento = now,
 *       persisted through {@link InventarioService#createWithStock} so the
 *       ArticuloStock accumulator updates exactly as before.</li>
 *   <li>Approve revision (legacy {@code updateInventarioRevisionDialog()} /
 *       {@code updateInventariosRevisionDialog()} /
 *       {@code procesarMovimientoYSiguiente()}): usuario refreshed,
 *       {@code processed=true}, fechaMovimiento = today,
 *       {@code tipoMovimiento="Stock por Factura"}, notas =
 *       "Procesado mediante el sistema por: {user}" (+ ". Notas: ..." in the
 *       quick-process variant), stock updated via
 *       {@link InventarioService#markAsProcessed}. Legacy warn "Articulo
 *       Invalido" preserved when the movement lost its article.</li>
 *   <li>Reject (mapped onto the legacy delete path
 *       {@code deleteInventario()}): soft-delete via
 *       {@link InventarioService#softDelete} + the exact audit alerta
 *       "Inventario eliminado".</li>
 *   <li>Skip (legacy {@code skipCurrentMovement()}): audit-only alerta
 *       "Movimiento omitido", no mutation, next pending returned.</li>
 *   <li>Reopen (legacy {@code unprocess()}):
 *       {@code processed=false} through {@link InventarioService#update}
 *       (which re-runs updateStock — preserved quirk).</li>
 *   <li>Upload (legacy {@code uploadController.processXMLDirectly()}): each
 *       multipart part is pre-validated, then fed to the SAME
 *       {@link Parser#parseXML(InputStream)} inside the SAME
 *       {@link AsyncUserContext#setCurrentUser(String)}/{@link AsyncUserContext#clear()
 *       clear()} ThreadLocal bracket, with the username captured from the
 *       {@link SecurityIdentity} principal BEFORE parsing (the parser thread
 *       pattern required by the plan). The legacy fire-and-forget virtual
 *       thread existed to dodge ViewScoped JSF lifetimes; the REST surface
 *       processes sequentially on the request thread so callers get a
 *       deterministic per-file result while keeping byte-identical parser
 *       invocation semantics.</li>
 *   <li>Stock badges: {@code countActivos()}/{@code countPendientes()}/
 *       {@code countInactivos()} plus the legacy Procesados tab counter
 *       expression {@code activos - pendientes}; polled every 30s from the
 *       page.</li>
 * </ul>
 *
 * <p><b>Paging/sorting contract</b> follows docs/ui-kit.md §3.1: 1-based
 * {@code page} (default 1), {@code size} default 20, whitelisted
 * {@code sort} keys, {@code dir} asc|desc, reserved keys never treated as
 * filters. Filtering/sorting/paging is computed in memory over the existing
 * service queries because the Services layer is frozen for this task.</p>
 *
 * <p><b>Fragment dual-mode:</b> endpoints backing a UI surface check the
 * {@code HX-Request} header — present ⇒ only the requested fragment (data
 * table, wizard body, upload result + out-of-band toast); absent ⇒ plain
 * JSON following the {@link ApiResponse}/{@link PagedResponse} envelopes
 * (GET /table renders the FULL page instead, mirroring T18).</p>
 *
 * <p><b>Authorization:</b> {@code admin} or {@code inventario} (the module's
 * managing roles, matching the legacy {@code SessionController.inventarios}
 * gate); export buttons stay gated to the {@code registro} role in the
 * template exactly like the legacy {@code SessionController.registros}
 * rendered checks. Downloads reuse the T17 dataset key {@code inventario}.</p>
 */
@Path("/api/app/inventario")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"admin", "inventario"})
@Tag(name = "App - Inventario")
public class InventarioResource {

    private static final Logger LOG = Logger.getLogger(InventarioResource.class.getName());

    /** Tab keys (URL-facing); legacy tab titles kept in templates. */
    public static final String TAB_ACTIVOS = "activos";
    public static final String TAB_INACTIVOS = "inactivos";
    public static final String TAB_PROCESADOS = "procesados";
    public static final String TAB_PENDIENTES = "pendientes";

    // ── Legacy FacesMessage / alerta message parity ─────────────────────
    /** Legacy createInventarioDialog/updateInventarioRevisionDialog warn. */
    private static final String MSG_ARTICULO_INVALIDO = "Articulo Invalido";
    /** Revision/quick-process dialogs' required-cantidad message. */
    private static final String MSG_CANTIDAD_REQUERIDA = "La cantidad no puede estar vacía";
    /** Legacy info after processing an adjustment. */
    private static final String MSG_SE_PROCESO = "Se proceso el ajuste";
    /** Legacy loadNextAjuste empty-queue info. */
    private static final String MSG_SIN_PENDIENTES = "No hay más artículos para revisar";
    /** Legacy skipCurrentMovement info. */
    private static final String MSG_OMITIDO = "Se omitió el movimiento";
    /** Legacy procesadoRapido empty-queue info. */
    private static final String MSG_NO_HAY_PENDIENTES = "No hay movimientos pendientes para procesar";
    /** Notas prefix written on every approval (both dialog variants). */
    private static final String NOTAS_PROCESADO_PREFIJO = "Procesado mediante el sistema por: ";
    /** tipoMovimiento stamped by every approval path. */
    private static final String TIPO_MOVIMIENTO_APROBACION = "Stock por Factura";
    /** Parser's own missing-consecutivo message (surfaced verbatim). */
    private static final String MSG_FALTA_CONSECUTIVO = "XML inválido: falta el número consecutivo";

    @Nonnull
    @Inject
    InventarioService inventarioService;

    @Nonnull
    @Inject
    ArticulosService articulosService;

    @Nonnull
    @Inject
    LoginService loginService;

    @Nonnull
    Parser parser;

    @Inject
    @Nonnull
    SecurityIdentity identity;

    /** Request context (quarkus-rest injectable) — source of HX-Request. */
    @Nonnull
    @Inject
    RoutingContext routing;

    // Templates (rendered to String: no quarkus-rest-qute MessageBodyWriter
    // on this stack — same approach as LoginPageResource/CategoriaResource).
    @Nonnull
    @Location("pages/inventario/index.html")
    @Inject
    Template pageIndex;

    @Nonnull
    @Location("pages/inventario/tabla-activos.html")
    @Inject
    Template tablaActivos;

    @Nonnull
    @Location("pages/inventario/tabla-inactivos.html")
    @Inject
    Template tablaInactivos;

    @Nonnull
    @Location("pages/inventario/tabla-procesados.html")
    @Inject
    Template tablaProcesados;

    @Nonnull
    @Location("pages/inventario/tabla-pendientes.html")
    @Inject
    Template tablaPendientes;

    @Nonnull
    @Location("pages/inventario/badges.html")
    @Inject
    Template badges;

    @Nonnull
    @Location("pages/inventario/form-ajuste.html")
    @Inject
    Template formAjuste;

    @Nonnull
    @Location("pages/inventario/form-revision.html")
    @Inject
    Template formRevision;

    @Nonnull
    @Location("pages/inventario/form-rapido.html")
    @Inject
    Template formRapido;

    @Nonnull
    @Location("pages/inventario/detalles-ajuste.html")
    @Inject
    Template detallesAjuste;

    @Nonnull
    @Location("pages/inventario/upload-resultado.html")
    @Inject
    Template uploadResultado;

    // ════════════════════════════════════════════════════════════════════
    // Tab lists (kit contract params page/size/sort/dir)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Paginated adjustment list for one tab. {@code q} reproduces the legacy
     * {@code globalFilterFunction} fields (codigo, artículo nombre/código de
     * barra, fecha, notas, tipo movimiento, cantidad, usuario).
     */
    @GET
    @Path("/ajustes")
    @Operation(summary = "List adjustments of one tab with pagination, sorting and global filter")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Paginated adjustments"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Role not allowed"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response listAjustes(
            @QueryParam("tab") @DefaultValue(TAB_ACTIVOS) @Parameter(description = "activos|inactivos|procesados|pendientes") String tab,
            @QueryParam("page") @DefaultValue("1") @Parameter(description = "Page number (1-based)") int page,
            @QueryParam("size") @DefaultValue("20") @Parameter(description = "Page size") int size,
            @QueryParam("sort") @Nullable @Parameter(description = "Sort key: codigo|articulo|cantidad|tipoMovimiento|fechaMovimiento|usuario") String sort,
            @QueryParam("dir") @DefaultValue("asc") @Parameter(description = "Sort direction: asc|desc") String dir,
            @QueryParam("q") @Nullable @Parameter(description = "Global filter text") String q) {
        try {
            List<Inventario> filtered = filteredOf(tab, q);
            sortEntities(filtered, sort, dir);
            long total = filtered.size();
            Window w = windowOf(total, page, size);
            List<InventarioDTO> data = filtered.subList(w.from(), w.to()).stream()
                    .map(InventarioResource::toDTO).toList();
            return Response.ok(new PagedResponse<>(data, total, w.page(), w.size())).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error listing ajustes de inventario", e);
            return serverError("Error listando los ajustes de inventario");
        }
    }

    /** Adjustment detail (legacy row-selection detail dialog payload). */
    @GET
    @Path("/ajustes/{codigo}")
    @Operation(summary = "Adjustment detail")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Adjustment"),
        @APIResponse(responseCode = "404", description = "Unknown codigo"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response detalle(@PathParam("codigo") int codigo) {
        try {
            Inventario inventario = inventarioService.find(codigo);
            if (inventario == null) {
                return notFound("No se encontró el movimiento solicitado");
            }
            return Response.ok(ApiResponse.ok(toDTO(inventario))).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error leyendo el ajuste " + codigo, e);
            return serverError("Error leyendo el ajuste");
        }
    }

    /**
     * Stock levels for a barcode — wraps the existing service math without
     * touching it: {@link InventarioService#getStock(String)} (ArticuloStock
     * accumulator) and {@link InventarioService#calculateTotalStockForItemByBarcode(String)}
     * (sum of active+processed movements).
     */
    @GET
    @Path("/stock")
    @Operation(summary = "Stock level for a barcode (getStock + calculateTotalStockForItemByBarcode)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Stock levels"),
        @APIResponse(responseCode = "400", description = "Missing codigoBarra"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response stock(@QueryParam("codigoBarra") @Nullable String codigoBarra) {
        if (codigoBarra == null || codigoBarra.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(ApiResponse.error("VALIDATION_ERROR", "Falta el parámetro 'codigoBarra'"))
                    .build();
        }
        try {
            String barcode = codigoBarra.trim();
            return Response.ok(ApiResponse.ok(new StockResponse(
                    barcode,
                    inventarioService.getStock(barcode),
                    inventarioService.calculateTotalStockForItemByBarcode(barcode)))).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error consultando stock para " + codigoBarra, e);
            return serverError("Error consultando el stock");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Create adjustment (legacy createInventarioDialog)
    // ════════════════════════════════════════════════════════════════════

    /** Creates an adjustment — legacy {@code createInventarioDialog()} parity. */
    @POST
    @Path("/ajustes")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Create an adjustment (legacy createInventarioDialog parity)")
    @APIResponses({
        @APIResponse(responseCode = "201", description = "Created (stock updated)"),
        @APIResponse(responseCode = "400", description = "Missing articulo or cantidad"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response crearAjuste(@Nullable AjusteRequest body) {
        return doCrearAjuste(body == null ? null : body.articuloId(),
                body == null ? null : body.cantidad(),
                body == null ? null : body.tipoMovimiento(),
                body == null ? null : body.notas());
    }

    /** Form-urlencoded twin of {@link #crearAjuste} for the HTMX dialog. */
    @POST
    @Path("/ajustes")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Operation(summary = "Create an adjustment from an HTMX form", hidden = true)
    public Response crearAjusteForm(
            @FormParam("articuloId") @Nullable String articuloId,
            @FormParam("cantidad") @Nullable String cantidad,
            @FormParam("tipoMovimiento") @Nullable String tipoMovimiento,
            @FormParam("notas") @Nullable String notas) {
        return doCrearAjuste(parseIntOrNull(articuloId), parseDecimalOrNull(cantidad),
                emptyToNull(tipoMovimiento), emptyToNull(notas));
    }

    private Response doCrearAjuste(@Nullable Integer articuloId, @Nullable BigDecimal cantidad,
                                   @Nullable String tipoMovimiento, @Nullable String notas) {
        // Legacy guard chain: session (framework-guaranteed here) → bean →
        // ArticuloID == 0 → "Articulo Invalido".
        if (articuloId == null || articuloId == 0) {
            return failureWithToast(formAjusteAjusteVacio(), "warn", MSG_ARTICULO_INVALIDO,
                    Response.Status.BAD_REQUEST, "VALIDATION_ERROR");
        }
        if (cantidad == null) {
            return failureWithToast(formAjusteAjusteVacio(), "warn", MSG_CANTIDAD_REQUERIDA,
                    Response.Status.BAD_REQUEST, "VALIDATION_ERROR");
        }
        try {
            Articulos articulo = articulosService.findById(articuloId);
            if (articulo == null) {
                return failureWithToast(formAjusteAjusteVacio(), "warn", MSG_ARTICULO_INVALIDO,
                        Response.Status.NOT_FOUND, "NOT_FOUND");
            }
            Inventario nuevo = new Inventario();
            nuevo.setArticulo(articulo);
            nuevo.setUsuario(currentUser());
            nuevo.setProcessed(true);          // legacy: newInventario.setProcessed(true)
            nuevo.setStatus(true);             // legacy: newInventario.setStatus(true)
            nuevo.setCantidad(cantidad);
            nuevo.setTipoMovimiento(emptyToNull(tipoMovimiento) != null ? tipoMovimiento : "Ajuste manual");
            nuevo.setNotas(notas);
            nuevo.setFechaMovimiento(new Date()); // legacy: explicit today
            inventarioService.createWithStock(nuevo);
            LOG.log(Level.INFO, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s",
                    "Inventario creado", "Se ha creado el inventario: " + articulo.getNombre(),
                    currentUser() != null ? currentUser().getUsername() : "Sistema",
                    0, "InventarioResource.createInventarioDialog", null, nuevo.toString()));
            if (isHxRequest()) {
                return hxRedirect("/api/app/inventario/table?tab=" + TAB_ACTIVOS);
            }
            return Response.status(Response.Status.CREATED)
                    .entity(ApiResponse.ok(toDTO(nuevo))).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error creando el ajuste", e);
            return serverError("Error creando el ajuste");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Revision workflow (pendiente → procesado / reject / skip / reopen)
    // ════════════════════════════════════════════════════════════════════

    /**
     * GET /revision/siguiente — legacy {@code procesadoRapido()} +
     * {@code loadNextAjuste()} parity: loads the first unprocessed movement.
     * HX-Request renders the quick-process wizard body; JSON clients get
     * {@code hasNext} + the pending adjustment.
     */
    @GET
    @Path("/revision/siguiente")
    @Operation(summary = "Load the next pending movement (procesadoRapido/loadNextAjuste parity)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Next pending movement (or wizard fragment when HX-Request)"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response revisionSiguiente() {
        try {
            List<Inventario> pendientes = orEmpty(inventarioService.listAllSinProcesar());
            boolean hasNext = !pendientes.isEmpty();
            Inventario siguiente = hasNext ? pendientes.get(0) : null;
            if (isHxRequest()) {
                return htmlOk(rapidoFragment(siguiente, null,
                        hasNext ? null : MSG_NO_HAY_PENDIENTES));
            }
            return Response.ok(ApiResponse.ok(new RevisionNextDTO(hasNext,
                    siguiente == null ? null : toDTO(siguiente)))).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error cargando el siguiente movimiento pendiente", e);
            return serverError("Error cargando el siguiente movimiento");
        }
    }

    /**
     * POST /ajustes/{codigo}/aprobar — legacy
     * {@code updateInventarioRevisionDialog()} (plain revision dialog) and
     * {@code procesarMovimientoYSiguiente()} (quick-process wizard,
     * {@code modo=rapido}) parity: stamps usuario/fecha/tipo/notas and flips
     * the movement to processed THROUGH
     * {@link InventarioService#markAsProcessed}, which applies the quantity
     * to the article stock. With {@code modo=rapido} the HTMX response
     * continues the wizard with the next pending movement.
     */
    @POST
    @Path("/ajustes/{codigo}/aprobar")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Operation(summary = "Approve a pending movement (revision-dialogs parity)", hidden = true)
    public Response aprobarForm(@PathParam("codigo") int codigo,
                                @FormParam("cantidad") @Nullable String cantidad,
                                @FormParam("notas") @Nullable String notas,
                                @FormParam("modo") @Nullable String modo) {
        return doAprobar(codigo, parseDecimalOrNull(cantidad), emptyToNull(notas), modo);
    }

    /** JSON twin of {@link #aprobarForm} for API clients. */
    @POST
    @Path("/ajustes/{codigo}/aprobar")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Approve a pending movement (revision-dialogs parity)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Approved (stock updated)"),
        @APIResponse(responseCode = "400", description = "Blank cantidad or invalid article"),
        @APIResponse(responseCode = "404", description = "Unknown codigo"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response aprobar(@PathParam("codigo") int codigo, @Nullable AprobacionRequest body) {
        return doAprobar(codigo,
                body == null ? null : body.cantidad(),
                body == null ? null : emptyToNull(body.notas()),
                body == null ? null : body.modo());
    }

    private Response doAprobar(int codigo, @Nullable BigDecimal cantidad,
                               @Nullable String notas, @Nullable String modo) {
        try {
            Inventario seleccionado = inventarioService.find(codigo);
            if (seleccionado == null) {
                return notFound("No se encontró el movimiento solicitado");
            }
            // Legacy guard order: session (framework-guaranteed) → selected →
            // articulo != null → usuario != null.
            if (seleccionado.getArticulo() == null) {
                return approvalFailure(seleccionado, modo, "warn", MSG_ARTICULO_INVALIDO);
            }
            Users usuario = currentUser();
            if (usuario == null) {
                return approvalFailure(seleccionado, modo, "error",
                        "Sesion invalida");
            }
            boolean rapido = "rapido".equals(modo);
            String antes = DiffUtils.snapshotEntity(seleccionado);
            seleccionado.setUsuario(usuario);
            seleccionado.setProcessed(true);
            if (cantidad != null) {
                seleccionado.setCantidad(cantidad);
            }
            seleccionado.setFechaMovimiento(new Date());
            seleccionado.setTipoMovimiento(TIPO_MOVIMIENTO_APROBACION);
            if (rapido && notas != null && !notas.trim().isEmpty()) {
                // procesarMovimientoYSiguiente(): user notes appended.
                seleccionado.setNotas(NOTAS_PROCESADO_PREFIJO + usuario.getUsername()
                        + ". Notas: " + notas.trim());
            } else {
                // updateInventarioRevisionDialog(): fixed system note.
                seleccionado.setNotas(NOTAS_PROCESADO_PREFIJO + usuario.getUsername());
            }
            inventarioService.markAsProcessed(seleccionado);
            LOG.log(Level.INFO, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s",
                    "Inventario actualizado", "Se ha actualizado el inventario: " + seleccionado.getArticulo().getNombre(),
                    usuario != null ? usuario.getUsername() : "Sistema",
                    0, rapido
                            ? "InventarioResource.procesarMovimientoYSiguiente()"
                            : "InventarioResource.updateInventarioRevisionDialog",
                    antes, DiffUtils.snapshotEntity(seleccionado)));

            if (isHxRequest() && rapido) {
                // Wizard continuation: load the NEXT pending movement
                // (loadNextAjuste parity) and re-render the wizard body.
                List<Inventario> pendientes = orEmpty(inventarioService.listAllSinProcesar());
                Inventario siguiente = pendientes.isEmpty() ? null : pendientes.get(0);
                return htmlOk(rapidoFragment(siguiente, "success",
                        siguiente == null ? MSG_SIN_PENDIENTES : MSG_SE_PROCESO));
            }
            if (isHxRequest()) {
                return hxRedirect("/api/app/inventario/table?tab=" + TAB_PENDIENTES);
            }
            long restantes = orEmpty(inventarioService.listAllSinProcesar()).size();
            return Response.ok(ApiResponse.ok(new AprobacionResult(true, MSG_SE_PROCESO, restantes))).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error aprobando el movimiento " + codigo, e);
            return serverError("Error aprobando el movimiento");
        }
    }

    /**
     * POST /ajustes/{codigo}/rechazar — mapped onto the legacy delete path
     * {@code deleteInventario()}: soft-deletes the movement through
     * {@link InventarioService#softDelete} with the exact audit alerta.
     */
    @POST
    @Path("/ajustes/{codigo}/rechazar")
    @Operation(summary = "Reject a movement (deleteInventario soft-delete parity)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Rejected (or refreshed fragment when HX-Request)"),
        @APIResponse(responseCode = "404", description = "Unknown codigo"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response rechazar(@PathParam("codigo") int codigo) {
        try {
            Inventario seleccionado = inventarioService.find(codigo);
            if (seleccionado == null) {
                return notFound("No se encontró el movimiento solicitado");
            }
            String antes = DiffUtils.snapshotEntity(seleccionado);
            inventarioService.softDelete(seleccionado);
            LOG.log(Level.INFO, String.format("ALERT [%s] %s | user=%s | codigo=%d | source=%s | antes=%s | despues=%s",
                    "Inventario eliminado", "Se ha eliminado el inventario: " + nombreArticuloDe(seleccionado),
                    currentUser() != null ? currentUser().getUsername() : "Sistema",
                    0, "InventarioResource.deleteInventario",
                    antes, DiffUtils.snapshotEntity(seleccionado)));
            if (isHxRequest()) {
                return tableFragment(TAB_PENDIENTES, 1, 20, null, "asc", null,
                        "warn", "Se rechazó el movimiento");
            }
            return Response.ok(ApiResponse.ok(Map.of("mensaje", "Se rechazó el movimiento"))).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error rechazando el movimiento " + codigo, e);
            return serverError("Error rechazando el movimiento");
        }
    }

    /**
     * POST /ajustes/{codigo}/omitir — legacy {@code skipCurrentMovement()}
     * parity: audit-only alerta, NO mutation, next pending returned.
     */
    @POST
    @Path("/ajustes/{codigo}/omitir")
    @Operation(summary = "Skip a pending movement without processing (skipCurrentMovement parity)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Skipped (or next wizard fragment when HX-Request)"),
        @APIResponse(responseCode = "404", description = "Unknown codigo"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response omitir(@PathParam("codigo") int codigo) {
        try {
            Inventario seleccionado = inventarioService.find(codigo);
            if (seleccionado == null) {
                return notFound("No se encontró el movimiento solicitado");
            }
            String antes = DiffUtils.snapshotEntity(seleccionado);
                        LOG.info("Se ha omitido el movimiento: " + nombreArticuloDe(seleccionado) + " | user=" + String.valueOf(currentUser()) + " | source=" + "InventarioResource.skipCurrentMovement()" + " | antes=" + String.valueOf(antes) + " | despues=" + String.valueOf(DiffUtils.snapshotEntity(seleccionado)));
            List<Inventario> pendientes = new ArrayList<>(orEmpty(inventarioService.listAllSinProcesar()));
            pendientes.removeIf(m -> m.getCodigo() == codigo);
            Inventario siguiente = pendientes.isEmpty() ? null : pendientes.get(0);
            if (isHxRequest()) {
                return htmlOk(rapidoFragment(siguiente, "info", MSG_OMITIDO));
            }
            return Response.ok(ApiResponse.ok(new RevisionNextDTO(siguiente != null,
                    siguiente == null ? null : toDTO(siguiente)))).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error omitiendo el movimiento " + codigo, e);
            return serverError("Error omitiendo el movimiento");
        }
    }

    /**
     * POST /ajustes/{codigo}/reabrir — legacy {@code unprocess()} parity:
     * {@code processed=false} through {@link InventarioService#update}
     * (the service re-runs updateStock — preserved quirk).
     */
    @POST
    @Path("/ajustes/{codigo}/reabrir")
    @Operation(summary = "Undo the processing of a movement (unprocess parity)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Reopened (or refreshed fragment when HX-Request)"),
        @APIResponse(responseCode = "404", description = "Unknown codigo"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response reabrir(@PathParam("codigo") int codigo) {
        try {
            Inventario seleccionado = inventarioService.find(codigo);
            if (seleccionado == null) {
                return notFound("No se encontró el movimiento solicitado");
            }
            seleccionado.setProcessed(false);
            inventarioService.update(seleccionado);
            if (isHxRequest()) {
                return tableFragment(TAB_PROCESADOS, 1, 20, null, "asc", null,
                        "info", "Se reabrió el movimiento");
            }
            return Response.ok(ApiResponse.ok(toDTO(seleccionado))).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error reabriendo el movimiento " + codigo, e);
            return serverError("Error reabriendo el movimiento");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // XML upload (legacy subirFacturas.xhtml → uploadController → Parser)
    // ════════════════════════════════════════════════════════════════════

    /**
     * POST /upload — multipart port of the legacy upload dialog. Every file
     * part is processed SEQUENTIALLY (legacy queue semantics): pre-validate
     * well-formedness + consecutivo presence, then invoke the SAME
     * {@link Parser#parseXML(InputStream)} inside the SAME
     * {@link AsyncUserContext} ThreadLocal bracket, with the username taken
     * from the {@link SecurityIdentity} principal before parsing. Parser
     * errors surface as Bulma danger notifications in the upload-result
     * fragment (HX) or structured per-file results (JSON).
     */
    @POST
    @Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Operation(summary = "Upload factura XML files into the parser (uploadController parity)", hidden = true)
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Per-file results (fragment when HX-Request)"),
        @APIResponse(responseCode = "400", description = "No file parts received"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response upload(@RestForm("files") @Nullable List<FileUpload> files) {
        try {
            List<FileUpload> partes = files == null ? Collections.emptyList() : files;
            if (partes.isEmpty()) {
                return failureWithToast(uploadResultadoVacio(), "error",
                        "No se recibió ningún archivo para procesar",
                        Response.Status.BAD_REQUEST, "VALIDATION_ERROR");
            }
            // Capture the principal ONCE before any parsing work (the async
            // propagation contract: parser threads read AsyncUserContext).
            String username = principalUsername();

            List<UploadFileResult> resultados = new ArrayList<>();
            for (FileUpload parte : partes) {
                resultados.add(processSingleFile(parte, username));
            }
            long exitosos = resultados.stream().filter(UploadFileResult::exito).count();
            UploadResponse payload = new UploadResponse(resultados, exitosos,
                    resultados.size() - exitosos);
            if (isHxRequest()) {
                return htmlOk(uploadResultado
                        .data("resultados", resultados)
                        .data("procesados", exitosos)
                        .data("fallidos", payload.fallidos())
                        .data("toastSeverity", payload.fallidos() > 0 ? "warn" : "success")
                        .data("toastMessage", payload.fallidos() > 0
                                ? payload.fallidos() + " archivo(s) con error"
                                : exitosos + " archivo(s) procesado(s)"));
            }
            return Response.ok(ApiResponse.ok(payload)).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error procesando la subida de XML", e);
            return serverError("Error procesando los archivos");
        }
    }

    /** One file's sequential processing (processSingleFile/processXMLDirectly parity). */
    private UploadFileResult processSingleFile(@Nonnull FileUpload parte, @Nonnull String username) {
        String fileName = parte.fileName() == null ? "(sin nombre)" : parte.fileName();
        byte[] contenido;
        try {
            contenido = Files.readAllBytes(parte.uploadedFile());
        } catch (IOException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Archivo: " + fileName + " - Error: " + e.getMessage() + " | source=" + "InventarioResource.processSingleFile()" + " | antes=" + String.valueOf(fileName) + " | despues=" + String.valueOf(e.getMessage()));
            return new UploadFileResult(fileName, false, "No se pudo leer el archivo: " + e.getMessage());
        }
        if (contenido.length == 0) {
                        LOG.log(java.util.logging.Level.WARNING, "File is null or empty: " + fileName + " | source=" + "InventarioResource.processSingleFile()" + " | antes=" + String.valueOf(fileName) + " | despues=" + String.valueOf((Object) null));
            return new UploadFileResult(fileName, false, "El archivo está vacío");
        }
        String errorPrevalidacion = prevalidateXml(contenido);
        if (errorPrevalidacion != null) {
                        LOG.log(java.util.logging.Level.WARNING, errorPrevalidacion + " (" + fileName + ")" + " | source=" + "InventarioResource.prevalidateXml()" + " | antes=" + String.valueOf(fileName) + " | despues=" + String.valueOf((Object) null));
            return new UploadFileResult(fileName, false, errorPrevalidacion);
        }
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(contenido)) {
            // Same ThreadLocal bracket as uploadController.processXMLDirectly:
            // set BEFORE parseXML, cleared in finally, username captured from
            // the authenticated principal instead of the JSF session.
            AsyncUserContext.setCurrentUser(username);
            parser.parseXML(inputStream);
                        LOG.info("Successfully processed file: " + fileName + " | source=" + "InventarioResource.processSingleFile()" + " | antes=" + String.valueOf(fileName) + " | despues=" + String.valueOf((Object) null));
            return new UploadFileResult(fileName, true, "Archivo procesado por el parser");
        } catch (IOException | RuntimeException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Archivo: " + fileName + " - Error: " + e.getMessage() + " | source=" + "InventarioResource.processSingleFile()" + " | antes=" + String.valueOf(fileName) + " | despues=" + String.valueOf(e.getMessage()));
            return new UploadFileResult(fileName, false, "Error al parsear el XML: " + e.getMessage());
        } finally {
            AsyncUserContext.clear();
        }
    }

    /**
     * Cheap structural gate BEFORE the parser runs so malformed documents get
     * a clean, deterministic danger notification instead of relying on the
     * parser's internal swallow-and-log behavior. Mirrors the parser's own
     * first-failure messages: unreadable XML and the missing-consecutivo case
     * (skipped for MensajeHacienda roots, which the parser handles without a
     * consecutivo).
     */
    @Nullable
    private static String prevalidateXml(byte[] contenido) {
        Document documento;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            documento = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(contenido));
        } catch (Exception e) {
            return "Error parsing XML: " + e.getMessage();
        }
        Element raiz = documento.getDocumentElement();
        if (raiz == null) {
            return "Error parsing XML: documento sin elemento raíz";
        }
        if ("MensajeHacienda".equals(raiz.getNodeName())) {
            return null; // parser branch that needs no consecutivo
        }
        if (consecutivoDe(raiz).isEmpty()) {
            return MSG_FALTA_CONSECUTIVO;
        }
        return null;
    }

    /** Loose NumeroConsecutivo search mirroring Parser.extractNumeroConsecutivo. */
    @Nonnull
    private static String consecutivoDe(@Nonnull Element raiz) {
        String directo = raiz.getAttribute("NumeroConsecutivo");
        if (!directo.isEmpty()) {
            return directo;
        }
        NodeList hijos = raiz.getElementsByTagName("*");
        for (int i = 0; i < hijos.getLength(); i++) {
            Element hijo = (Element) hijos.item(i);
            String atributo = hijo.getAttribute("NumeroConsecutivo");
            if (!atributo.isEmpty()) {
                return atributo;
            }
            NodeList nietos = hijo.getChildNodes();
            for (int j = 0; j < nietos.getLength(); j++) {
                if ("NumeroConsecutivo".equals(nietos.item(j).getNodeName())) {
                    String texto = nietos.item(j).getTextContent();
                    if (texto != null && !texto.isBlank() && !"null".equals(texto)) {
                        return texto.trim();
                    }
                }
            }
        }
        return "";
    }

    // ════════════════════════════════════════════════════════════════════
    // Fragment endpoints (docs/ui-kit.md §2.9 dual-mode contract)
    // ════════════════════════════════════════════════════════════════════

    /**
     * GET /table?page&size&sort&dir&tab&q — with the {@code HX-Request}
     * header returns ONLY the requested data-table include; without it
     * renders the FULL page (all four tabs + badges + upload card), exactly
     * the _kit/data-table SERVER-SIDE CONTRACT.
     */
    @GET
    @Path("/table")
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Data-table fragment (HX-Request) or full inventario page")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "HTML fragment or full page"),
        @APIResponse(responseCode = "500", description = "Template rendering failure")
    })
    public Response table(
            @QueryParam("tab") @DefaultValue(TAB_ACTIVOS) String tab,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("sort") @Nullable String sort,
            @QueryParam("dir") @DefaultValue("asc") String dir,
            @QueryParam("q") @Nullable String q) {
        try {
            String tabNormalizado = normalizeTab(tab);
            if (isHxRequest()) {
                return tableFragment(tabNormalizado, page, size, sort, dir, q, null, null);
            }
            return htmlOk(renderFullPage());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error renderizando la página de inventario", e);
            return serverError("Error renderizando la página");
        }
    }

    /**
     * GET /badges — stat-card counts fragment; the page polls it with
     * {@code hx-trigger="every 30s"} so the header numbers track approvals
     * made elsewhere.
     */
    @GET
    @Path("/badges")
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Stat-badge counts fragment (polled every 30s)")
    public Response badgeCounts() {
        try {
            return htmlOk(badges.data("badges", badgeModel()));
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error renderizando los contadores", e);
            return serverError("Error renderizando los contadores");
        }
    }

    // ── Modal-body form endpoints (hx-get targets of _kit/modal) ────────

    /** Empty adjustment creation form (modal body; CreateMovimientoDialog parity). */
    @GET
    @Path("/formularios/ajuste/nueva")
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "New-adjustment form fragment (modal body)")
    public Response formNuevoAjuste() {
        return htmlOk(formAjusteAjusteVacio());
    }

    /** Movement detail card (modal body; verDetallesDeAjuste parity). */
    @GET
    @Path("/formularios/ajuste/{codigo}/detalles")
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Adjustment detail fragment (modal body)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Detail HTML"),
        @APIResponse(responseCode = "404", description = "Unknown codigo")
    })
    public Response formDetallesAjuste(@PathParam("codigo") int codigo) {
        Inventario inventario = inventarioService.find(codigo);
        if (inventario == null) {
            return notFound("No se encontró el movimiento solicitado");
        }
        return htmlOk(detallesAjuste.data("ajuste", toDTO(inventario)));
    }

    /** Revision dialog body for ONE pending movement (DialogoRevisionInventario parity). */
    @GET
    @Path("/formularios/revision/{codigo}")
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Revision-dialog fragment for one pending movement")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Revision HTML"),
        @APIResponse(responseCode = "404", description = "Unknown codigo")
    })
    public Response formRevisionAjuste(@PathParam("codigo") int codigo) {
        Inventario inventario = inventarioService.find(codigo);
        if (inventario == null) {
            return notFound("No se encontró el movimiento solicitado");
        }
        return htmlOk(formRevision
                .data("ajuste", toDTO(inventario))
                .data("toastSeverity", null)
                .data("toastMessage", null));
    }

    /** Quick-process wizard body (DialogoProcesadoRapido parity). */
    @GET
    @Path("/formularios/rapido")
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Quick-process wizard fragment (first pending movement)")
    public Response formRapidoAjuste() {
        List<Inventario> pendientes = orEmpty(inventarioService.listAllSinProcesar());
        Inventario siguiente = pendientes.isEmpty() ? null : pendientes.get(0);
        return htmlOk(rapidoFragment(siguiente, null,
                siguiente == null ? MSG_NO_HAY_PENDIENTES : null));
    }

    // ════════════════════════════════════════════════════════════════════
    // Request classification & shared responses
    // ════════════════════════════════════════════════════════════════════

    /** True when the call comes from HTMX (layout hx-headers carries it). */
    private boolean isHxRequest() {
        String header = routing.request().getHeader("HX-Request");
        return header != null && !"false".equalsIgnoreCase(header);
    }

    @Nonnull
    private static String normalizeTab(@Nullable String tab) {
        if (tab == null) {
            return TAB_ACTIVOS;
        }
        return switch (tab.toLowerCase(Locale.ROOT)) {
            case TAB_INACTIVOS -> TAB_INACTIVOS;
            case TAB_PROCESADOS -> TAB_PROCESADOS;
            case TAB_PENDIENTES -> TAB_PENDIENTES;
            default -> TAB_ACTIVOS;
        };
    }

    @Nullable
    private static Integer parseIntOrNull(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    private static BigDecimal parseDecimalOrNull(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    private static String emptyToNull(@Nullable String raw) {
        return raw == null || raw.isBlank() ? null : raw;
    }

    private static <T> List<T> orEmpty(@Nullable List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }

    private Response notFound(@Nonnull String mensaje) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(ApiResponse.error("NOT_FOUND", mensaje)).build();
    }

    private Response serverError(@Nonnull String mensaje) {
        return Response.serverError()
                .entity(ApiResponse.error("INTERNAL_ERROR", mensaje)).build();
    }

    private static Response htmlOk(@Nonnull TemplateInstance template) {
        return Response.ok(template.render())
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).build();
    }

    /**
     * Failure branch shared by mutating endpoints: HTMX callers get the
     * redisplayed fragment + an out-of-band toast (ui-kit.md Pattern A); API
     * callers get the structured envelope.
     */
    private Response failureWithToast(@Nonnull TemplateInstance fragment,
                                      @Nonnull String severity, @Nonnull String mensaje,
                                      @Nonnull Response.Status status, @Nonnull String code) {
        if (isHxRequest()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                    .entity(fragment
                            .data("toastSeverity", severity)
                            .data("toastMessage", mensaje)
                            .render()).build();
        }
        return Response.status(status)
                .entity(ApiResponse.error(code, mensaje)).build();
    }

    /** Approval failure keeps the wizard/dialog context alive (modo-aware). */
    private Response approvalFailure(@Nonnull Inventario ajuste, @Nullable String modo,
                                     @Nonnull String severity, @Nonnull String mensaje) {
        if (isHxRequest() && "rapido".equals(modo)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                    .entity(rapidoFragment(ajuste, severity, mensaje).render()).build();
        }
        if (isHxRequest()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                    .entity(formRevision
                            .data("ajuste", toDTO(ajuste))
                            .data("toastSeverity", severity)
                            .data("toastMessage", mensaje)
                            .render()).build();
        }
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(ApiResponse.error("VALIDATION_ERROR", mensaje)).build();
    }

    /** HTMX redirect: the client navigates and the page re-renders fresh. */
    private static Response hxRedirect(@Nonnull String url) {
        return Response.status(Response.Status.OK)
                .header("HX-Redirect", url)
                .build();
    }

    // ════════════════════════════════════════════════════════════════════
    // Template models
    // ════════════════════════════════════════════════════════════════════

    /** Full-page model: four tables + badge counts + upload card flags. */
    private TemplateInstance renderFullPage() {
        return pageIndex
                .data("tablaActivos", buildTableModel(TAB_ACTIVOS, 1, 20, null, "asc", null).asMap())
                .data("tablaInactivos", buildTableModel(TAB_INACTIVOS, 1, 20, null, "asc", null).asMap())
                .data("tablaProcesados", buildTableModel(TAB_PROCESADOS, 1, 20, null, "asc", null).asMap())
                .data("tablaPendientes", buildTableModel(TAB_PENDIENTES, 1, 20, null, "asc", null).asMap())
                .data("badges", badgeModel())
                .data("isAdmin", isAdmin());
    }

    /** Badge counts (legacy stat cards + Procesados counter expression). */
    private Map<String, Object> badgeModel() {
        long activos = inventarioService.countActivos();
        long pendientes = inventarioService.countPendientes();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("activos", activos);
        map.put("pendientes", pendientes);
        map.put("inactivos", inventarioService.countInactivos());
        map.put("procesados", Math.max(0L, activos - pendientes)); // legacy tab-counter arithmetic
        return map;
    }

    /** Renders ONLY one tabla-*.html include (the fragment swap target). */
    private Response tableFragment(@Nonnull String tab, int page, int size,
                                   @Nullable String sort, @Nullable String dir,
                                   @Nullable String q,
                                   @Nullable String toastSeverity, @Nullable String toastMessage) {
        TableModel model = buildTableModel(tab, page, size, sort, dir, q);
        Template template = switch (tab) {
            case TAB_INACTIVOS -> tablaInactivos;
            case TAB_PROCESADOS -> tablaProcesados;
            case TAB_PENDIENTES -> tablaPendientes;
            default -> tablaActivos;
        };
        return htmlOk(template
                .data("modelo", model.asMap())
                .data("q", model.q())
                .data("toastSeverity", toastSeverity)
                .data("toastMessage", toastMessage));
    }

    /** Immutable view of everything one tabla-*.html include needs. */
    public record TableModel(String id, String baseUrl, String tab, List<Map<String, Object>> columnas,
                             List<?> filas, String sortKey, String sortDir, int page, int size,
                             long total, int totalPages, List<Integer> paginas,
                             Map<String, Object> filtros, String q) {

        /** Flat map variant for direct TemplateInstance.data(Map) feeding. */
        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("baseUrl", baseUrl);
            map.put("tab", tab);
            map.put("columnas", columnas);
            map.put("filas", filas);
            map.put("sortKey", sortKey);
            map.put("sortDir", sortDir);
            map.put("page", page);
            map.put("size", size);
            map.put("total", total);
            map.put("totalPages", totalPages);
            map.put("paginas", paginas);
            map.put("filtros", filtros);
            map.put("q", q);
            return map;
        }
    }

    /** Builds one tab's full model (filter → sort → slice → columns). */
    private TableModel buildTableModel(@Nonnull String tab, int page, int size,
                                       @Nullable String sort, @Nullable String dir,
                                       @Nullable String q) {
        List<Inventario> filtered = filteredOf(tab, q);
        sortEntities(filtered, sort, dir);

        long total = filtered.size();
        Window w = windowOf(total, page, size);
        List<Inventario> filas = new ArrayList<>(filtered.subList(w.from(), w.to()));

        // Column definitions mirror the legacy per-tab p:column sets; null
        // key ⇒ non-sortable (docs/ui-kit.md §3.1).
        List<Map<String, Object>> columnas = new ArrayList<>();
        columnas.add(col("Estado", null));
        columnas.add(col("Artículo", "articulo"));
        columnas.add(col("Código Barra", null));
        columnas.add(col("Cantidad", "cantidad"));
        columnas.add(col("Tipo Movimiento", "tipoMovimiento"));
        columnas.add(col("Fecha Movimiento", "fechaMovimiento"));
        columnas.add(col("Usuario", "usuario"));
        columnas.add(col("Acciones", null));

        Map<String, Object> filtros = new LinkedHashMap<>();
        filtros.put("tab", tab);
        if (q != null && !q.isBlank()) {
            filtros.put("q", q);
        }

        return new TableModel(
                "tabla-inventario-" + tab,
                "/api/app/inventario/table",
                tab,
                columnas,
                filas,
                sort,
                "desc".equalsIgnoreCase(dir) ? "desc" : "asc",
                w.page(),
                w.size(),
                total,
                w.totalPages(),
                pageWindow(w.page(), w.totalPages()),
                filtros,
                q);
    }

    /** Service-query dispatch per tab (Services layer untouched). */
    private List<Inventario> filteredOf(@Nonnull String tab, @Nullable String q) {
        List<Inventario> source = switch (normalizeTab(tab)) {
            case TAB_INACTIVOS -> orEmpty(inventarioService.listAllInactivos());
            case TAB_PROCESADOS -> orEmpty(inventarioService.listAllActivosYProcesados());
            case TAB_PENDIENTES -> orEmpty(inventarioService.listAllSinProcesar());
            default -> orEmpty(inventarioService.ListAllEnabled());
        };
        return filterAjustes(source, q);
    }

    /** Column definition helper (label + nullable sort key) as a map. */
    private static Map<String, Object> col(@Nonnull String label, @Nullable String key) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("label", label);
        map.put("key", key);
        return map;
    }

    /** Server-computed pager window: current ±2 clamped to [1,totalPages]. */
    private static List<Integer> pageWindow(int page, int totalPages) {
        if (totalPages <= 1) {
            return List.of(1);
        }
        List<Integer> pages = new ArrayList<>();
        int from = Math.max(1, page - 2);
        int to = Math.min(totalPages, page + 2);
        for (int i = from; i <= to; i++) {
            pages.add(i);
        }
        return pages;
    }

    private record Window(int page, int size, int from, int to, int totalPages) {}

    /** Clamped 1-based window over an in-memory result (Qute can't divide). */
    private static Window windowOf(long total, int page, int size) {
        int s = Math.min(Math.max(size, 1), 100);
        int totalPages = (int) Math.max(1L, (total + s - 1) / s);
        int p = Math.min(Math.max(page, 1), totalPages);
        int from = Math.min((p - 1) * s, (int) total);
        int to = Math.min(from + s, (int) total);
        return new Window(p, s, from, to, totalPages);
    }

    // ── Filtering/sorting (in-memory; Services layer untouched) ─────────

    /**
     * Global filter over the legacy {@code globalFilterFunction} field set:
     * codigo, artículo nombre/código de barra, fecha, notas, tipo movimiento,
     * cantidad, usuario username. Null-safe where the legacy version wasn't.
     */
    private static List<Inventario> filterAjustes(@Nonnull List<Inventario> source, @Nullable String q) {
        if (q == null || q.trim().isEmpty()) {
            return new ArrayList<>(source);
        }
        String needle = q.trim().toLowerCase(Locale.ROOT);
        List<Inventario> out = new ArrayList<>();
        for (Inventario m : source) {
            Articulos articulo = m.getArticulo();
            Users usuario = m.getUsuario();
            if (String.valueOf(m.getCodigo()).contains(needle)
                    || (articulo != null && matches(articulo.getNombre(), needle))
                    || (articulo != null && matches(articulo.getCodigoBarra(), needle))
                    || (m.getFechaMovimiento() != null
                        && matches(m.getFechaMovimiento().toString(), needle))
                    || matches(m.getNotas(), needle)
                    || matches(m.getTipoMovimiento(), needle)
                    || (m.getCantidad() != null && m.getCantidad().toString().contains(needle))
                    || (usuario != null && matches(usuario.getUsername(), needle))) {
                out.add(m);
            }
        }
        return out;
    }

    private static boolean matches(@Nullable String value, @Nonnull String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    /** Typed comparator dispatch over a whitelisted key set. */
    private static void sortEntities(@Nonnull List<Inventario> entities, @Nullable String sort,
                                     @Nullable String dir) {
        if (entities.isEmpty() || sort == null || sort.isBlank()) {
            return;
        }
        Comparator<Inventario> cmp = comparatorFor(sort);
        if (cmp != null) {
            entities.sort("desc".equalsIgnoreCase(dir) ? cmp.reversed() : cmp);
        }
    }

    @Nullable
    private static Comparator<Inventario> comparatorFor(@Nonnull String sort) {
        return switch (sort) {
            case "codigo" -> Comparator.comparingInt(Inventario::getCodigo);
            case "articulo" -> Comparator.comparing(InventarioResource::nombreArticuloDe,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "cantidad" -> Comparator.comparing(Inventario::getCantidad,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case "tipoMovimiento" -> Comparator.comparing(Inventario::getTipoMovimiento,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "fechaMovimiento" -> Comparator.comparing(Inventario::getFechaMovimiento,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case "usuario" -> Comparator.comparing(InventarioResource::usernameDe,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            default -> null;
        };
    }

    @Nullable
    private static String nombreArticuloDe(@Nullable Inventario m) {
        return m != null && m.getArticulo() != null ? m.getArticulo().getNombre() : null;
    }

    @Nullable
    private static String usernameDe(@Nullable Inventario m) {
        return m != null && m.getUsuario() != null ? m.getUsuario().getUsername() : null;
    }

    // ── DTO mapper (manual, repo convention) ────────────────────────────

    private static InventarioDTO toDTO(@Nonnull Inventario m) {
        Articulos articulo = m.getArticulo();
        Users usuario = m.getUsuario();
        return new InventarioDTO(
                m.getCodigo(),
                articulo != null ? articulo.getCodigo() : null,
                articulo != null ? articulo.getNombre() : null,
                articulo != null ? articulo.getCodigoBarra() : null,
                m.getCantidad(),
                m.getUnidadesRecomendadasFactura(),
                m.getTipoMovimiento(),
                m.getFechaMovimiento(),
                m.getNotas(),
                m.getStatus(),
                m.getProcessed(),
                usuario != null ? usuario.getId() : null,
                usuario != null ? usuario.getUsername() : null);
    }

    // ── Current-user resolution (SessionController.getCurrentUser parity) ──

    /**
     * Resolves the authenticated {@link Users} row through the T12 identity
     * provider's principal; null for anonymous/system contexts (alertas
     * accepts null, mirroring the legacy null-session branches).
     */
    private Users currentUser() {
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

    /** Principal name for the parser ThreadLocal ("system" fallback parity). */
    @Nonnull
    private String principalUsername() {
        try {
            if (!identity.isAnonymous() && identity.getPrincipal() != null
                    && identity.getPrincipal().getName() != null) {
                return identity.getPrincipal().getName();
            }
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "No principal name resolvable", e);
        }
        return "system"; // uploadController.handleFileUpload fallback parity
    }

    private boolean isAdmin() {
        return !identity.isAnonymous() && identity.hasRole("admin");
    }

    // ── Small fragment helpers ──────────────────────────────────────────

    /** Empty creation-form fragment instance (shared by GET + failures). */
    private TemplateInstance formAjusteAjusteVacio() {
        return formAjuste
                .data("toastSeverity", null)
                .data("toastMessage", null);
    }

    /** Empty upload-result fragment instance (shared by failures). */
    private TemplateInstance uploadResultadoVacio() {
        return uploadResultado
                .data("resultados", Collections.emptyList())
                .data("procesados", 0L)
                .data("fallidos", 0L);
    }

    /** Quick-process wizard fragment for a (nullable) current movement. */
    private TemplateInstance rapidoFragment(@Nullable Inventario actual,
                                            @Nullable String toastSeverity,
                                            @Nullable String toastMessage) {
        return formRapido
                .data("ajuste", actual == null ? null : toDTO(actual))
                .data("toastSeverity", toastSeverity)
                .data("toastMessage", toastMessage);
    }

    // ── Small value carriers ────────────────────────────────────────────

    /** Create-adjustment request body (JSON twin of the dialog form). */
    public record AjusteRequest(Integer articuloId, BigDecimal cantidad,
                                String tipoMovimiento, String notas) {}

    /** Approve request body (JSON twin of the revision/wizard forms). */
    public record AprobacionRequest(BigDecimal cantidad, String notas, String modo) {}

    /** Adjustment read model (manual mapper target). */
    public record InventarioDTO(Integer codigo, Long articuloCodigo, String articuloNombre,
                                String codigoBarra, BigDecimal cantidad,
                                BigDecimal unidadesRecomendadasFactura, String tipoMovimiento,
                                Date fechaMovimiento, String notas, Boolean status,
                                Boolean processed, Long usuarioId, String usuarioUsername) {}

    /** Stock levels for one barcode. */
    public record StockResponse(String codigoBarra, double stockActual, double stockCalculado) {}

    /** Next-pending payload of GET /revision/siguiente and POST .../omitir. */
    public record RevisionNextDTO(boolean hasNext, InventarioDTO ajuste) {}

    /** Approve outcome for JSON clients. */
    public record AprobacionResult(boolean success, String mensaje, long pendientesRestantes) {}

    /** Per-file upload outcome surfaced in the result fragment. */
    public record UploadFileResult(String fileName, boolean exito, String mensaje) {}

    /** Aggregate upload outcome for JSON clients. */
    public record UploadResponse(List<UploadFileResult> resultados, long procesados, long fallidos) {}
}
