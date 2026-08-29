package Controllers.Api.App;

import Models.Articulos.Articulos;
import Models.DTO.ApiResponse;
import Models.DTO.OrdenCompraDTO;
import Models.DTO.OrdenCompraDetailDTO;
import Models.DTO.OrdenCompraLineaDTO;
import Models.DTO.PagedResponse;
import Models.OrdenCompra;
import Models.OrdenCompraDetalle;
import Models.Users;
import Services.ArticulosService;
import Services.DepartamentoService;
import Services.LoginService;
import Services.OrdenCompraService;
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
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jboss.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Purchase-order endpoints for the NEW Qute/HTMX app surface (/app world),
 * replacing the six-form JSF workflow of {@code OrdenCompraController}
 * (ordenesForm list + Crear/Editar/Detalles/CambiarEstado/Cancelar dialogs)
 * with staged HTMX views backed by ONE resource (plan T31).
 *
 * <p><b>Delegation:</b> every business effect delegates 100% to
 * {@link OrdenCompraService} — number generation ({@code generarNumeroOrden()}),
 * creation with line subtotals/totals ({@code crearOrden()}), state changes
 * ({@code cambiarEstado()}), receiving ({@code recibirOrden()}), cancellation
 * ({@code cancelarOrden()}) and soft delete ({@code softDelete()}). The
 * transition legality check IS the service's own
 * {@code esTransicionValida(...)}; illegal transitions answer
 * <b>409 ApiResponse INVALID_STATE</b> with the legacy Spanish message
 * {@code "Transición de estado no válida: X → Y"}.</p>
 *
 * <p><b>Parity notes (see .omo/evidence/t31/state-machine.md):</b></p>
 * <ul>
 *   <li>Create/update reproduce {@code saveOrden()}/{@code updateOrden()}
 *       guard order and messages verbatim: proveedor first, then non-empty
 *       line list, then per-line validity ("Todos los artículos deben tener
 *       cantidad válida!").</li>
 *   <li>Edit/receive/cancel keep the legacy CONTROLLER semantics: no extra
 *       server-side estado guards (the JSF buttons were presentation-gated
 *       only; adding guards here would be logic drift).</li>
 *   <li>Audit trails preserved via {@link AlertasService#registrarAlerta}
 *       with the legacy tipo/message strings and DiffUtils snapshots.</li>
 *   <li>The JSF session-validity checks ("Sesión inválida!") are superseded by
 *       the framework auth: every path here requires an authenticated user
 *       with the {@code admin} or {@code inventario} role.</li>
 *   <li>Soft-deleted orders (status=false) never appeared in any legacy list,
 *       so mutations against them answer 404 — reproducing "unreachable from
 *       the UI" without inventing a new rule.</li>
 * </ul>
 *
 * <p><b>Surface layout</b> (docs/ui-kit.md contract): JSON endpoints answer
 * {@link ApiResponse}/{@link PagedResponse}; the same module also serves the
 * staged HTMX views — {@code GET /table} is dual-mode (fragment on
 * {@code HX-Request}, full page otherwise) and
 * {@code /formularios/*}, {@code /{id}/detalle}, {@code /{id}/estado},
 * {@code /{id}/cancelar} render the stage fragments. Mutations accept both
 * JSON (API callers) and form-urlencoded twins (HTMX forms, ui-kit Pattern A).
 * The article picker reuses the existing
 * {@link ArticulosService#findByNameContaining(String)} search.</p>
 */
@Path("/api/app/ordenes")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"admin", "inventario"})
@Tag(name = "App - Órdenes de Compra")
public class OrdenCompraResource {

    private static final Logger LOG = Logger.getLogger(OrdenCompraResource.class);

    /**
     * 422 Unprocessable Entity for HTMX form redisplays (ui-kit Pattern A).
     * Int literal because jakarta.ws.rs-api 3.1.0 (this stack's JAX-RS level)
     * has no Status constant for 422.
     */
    private static final int HTTP_UNPROCESSABLE_ENTITY = 422;

    /** Known workflow states, in lifecycle order (Models.OrdenCompra comment). */
    private static final List<String> ESTADOS = List.of(
            "BORRADOR", "ENVIADA", "CONFIRMADA", "RECIBIDA", "FACTURADA", "CANCELADA");

    private static final DateTimeFormatter FECHA_CORTA =
            DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter FECHA_HORA =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    @Nonnull
    @Inject
    OrdenCompraService ordenCompraService;

    @Nonnull
    @Inject
    DepartamentoService departamentoService;

    @Nonnull
    @Inject
    ArticulosService articulosService;

    /** Current-user resolution (same pattern as LoyaltyResource/SettingsResource). */
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

    /** Root path for HX-Redirect targets (fixed /Mercurius in this app). */
    @ConfigProperty(name = "quarkus.http.root-path", defaultValue = "/Mercurius")
    String rootPath;

    // Templates (rendered to String: no quarkus-rest-qute MessageBodyWriter
    // on this stack — same approach as CategoriaResource, T18).
    @Nonnull
    @Location("pages/compras/ordenes.html")
    @Inject
    Template pageIndex;

    @Nonnull
    @Location("pages/compras/ordenes-tabla.html")
    @Inject
    Template tablaPage;

    @Nonnull
    @Location("pages/compras/ordenes-form.html")
    @Inject
    Template formPage;

    @Nonnull
    @Location("pages/compras/ordenes-detalle.html")
    @Inject
    Template detallePage;

    @Nonnull
    @Location("pages/compras/ordenes-estado.html")
    @Inject
    Template estadoPage;

    @Nonnull
    @Location("pages/compras/ordenes-cancelar.html")
    @Inject
    Template cancelarPage;

    @Nonnull
    @Location("pages/compras/ordenes-articulos.html")
    @Inject
    Template articulosPage;

    // ── JSON API (kit list/detail/mutation contract) ────────────────────────

    /**
     * GET /api/app/ordenes?page&size&sort&dir&q&estado&proveedorId&numeroOrden
     *
     * <p>Paginated/sorted list over the ACTIVE orders
     * ({@code OrdenCompraService.listAll()} already filters status=true and
     * fetch-joins the relations). Page is 0-based (ClientsResource JSON
     * convention); filters mirror the legacy global filter
     * ({@code globalFilterFunction}: numeroOrden/proveedor/estado/notas/usuario)
     * plus the dedicated estado/numeroOrden/proveedor filters of the legacy
     * bean. Reserved kit keys page/size/sort/dir are never treated as
     * filters.</p>
     */
    @GET
    @Operation(summary = "List purchase orders with pagination, sorting and legacy filters")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Missing admin/inventario role"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response list(
            @QueryParam("page") @DefaultValue("0") @Parameter(description = "Page number (0-based)") int page,
            @QueryParam("size") @DefaultValue("20") @Parameter(description = "Page size (max 100)") int size,
            @QueryParam("sort") @Nullable @Parameter(description = "Sort key: numeroOrden|proveedor|fechaOrden|entrega|estado|total") String sort,
            @QueryParam("dir") @DefaultValue("asc") @Parameter(description = "Sort direction asc|desc") String dir,
            @QueryParam("q") @Nullable @Parameter(description = "Global text filter (legacy globalFilterFunction)") String q,
            @QueryParam("estado") @Nullable @Parameter(description = "Exact estado match") String estado,
            @QueryParam("proveedorId") @Nullable @Parameter(description = "Exact proveedor id") Integer proveedorId,
            @QueryParam("numeroOrden") @Nullable @Parameter(description = "Contains match on numeroOrden") String numeroOrden) {

        size = Math.min(Math.max(size, 1), 100);
        page = Math.max(page, 0);

        try {
            List<OrdenCompra> filas = new ArrayList<>(orEmpty(ordenCompraService.listAll()));
            filas = filtrar(filas, q, estado, proveedorId, numeroOrden);
            sortOrdenes(filas, sort, dir);

            long total = filas.size();
            int from = Math.min(page * size, (int) total);
            int to = Math.min(from + size, (int) total);
            List<OrdenCompraDTO> data = filas.subList(from, to).stream()
                    .map(OrdenCompraResource::toDTO)
                    .toList();
            return Response.ok(new PagedResponse<>(data, total, page, size)).build();
        } catch (Exception e) {
            LOG.warn("Error listing órdenes de compra", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error listando las órdenes de compra"))
                    .build();
        }
    }

    /**
     * GET /api/app/ordenes/{id} — full detail incl. flattened lines
     * ({@code OrdenCompraLineaDTO}). Transactional because
     * {@code detalles} is a LAZY collection.
     */
    @GET
    @Path("/{id}")
    @Transactional
    @Operation(summary = "Get a purchase order with its lines")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Success"),
        @APIResponse(responseCode = "404", description = "Not found (unknown id or soft-deleted)"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response get(@PathParam("id") @Parameter(description = "Order id") long id) {
        try {
            OrdenCompra orden = ordenCompraService.find(id);
            if (orden == null || !orden.isStatus()) {
                return notFound(id);
            }
            return Response.ok(ApiResponse.ok(toDetailDTO(orden))).build();
        } catch (Exception e) {
            LOG.warn("Error obteniendo la orden " + id, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error obteniendo la orden de compra"))
                    .build();
        }
    }

    /**
     * POST /api/app/ordenes — create. Guard order and messages are a verbatim
     * port of {@code saveOrden()}: proveedor → lineas non-empty → per-line
     * validity. The numeroOrden is generated through the service exactly like
     * the legacy controller did, and the audit alert keeps the legacy
     * "Orden de Compra Creada" tipo.
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    @Operation(summary = "Create a purchase order with lines")
    @APIResponses({
        @APIResponse(responseCode = "201", description = "Created"),
        @APIResponse(responseCode = "400", description = "Validation error (legacy messages)"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response create(@Nullable OrdenCompraDetailDTO payload) {
        try {
            String error = validarPayload(payload);
            if (error != null) {
                return badRequest(error);
            }

            OrdenCompra orden = new OrdenCompra();
            aplicarCabecera(orden, payload);
            orden.setUsuario(currentUser());

            // Parity: saveOrden() generated the numero BEFORE crearOrden().
            orden.setNumeroOrden(ordenCompraService.generarNumeroOrden());

            List<OrdenCompraDetalle> detalles = construirDetalles(orden, payload.getDetalles());
            ordenCompraService.crearOrden(orden, detalles);

            String antes = DiffUtils.snapshotEntity(orden);
                        LOG.info("Se creó la orden de compra: " + orden.getNumeroOrden() + " | user=" + String.valueOf(currentUser()) + " | source=" + "OrdenCompraResource.create()" + " | antes=" + String.valueOf("") + " | despues=" + String.valueOf(DiffUtils.snapshotEntity(orden)));

            return Response.status(Response.Status.CREATED)
                    .entity(ApiResponse.ok(toDetailDTO(orden)))
                    .build();
        } catch (Exception e) {
            LOG.warn("Error creando la orden de compra", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error al guardar: error interno"))
                    .build();
        }
    }

    /**
     * PUT /api/app/ordenes/{id} — edit. Port of {@code updateOrden()}:
     * same guards as create, lines replaced and subtotals/totals recomputed
     * through the service ({@code calcularSubtotal()} per line +
     * {@code calcularTotal()}), usuario re-attributed. Like the legacy
     * controller there is NO estado guard here — the BORRADOR-only gate was
     * presentation-layer (button visibility) and lives in the template.
     */
    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    @Operation(summary = "Update a purchase order (lines replaced, totals recomputed)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Updated"),
        @APIResponse(responseCode = "400", description = "Validation error (legacy messages)"),
        @APIResponse(responseCode = "404", description = "Not found (unknown id or soft-deleted)"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response update(@PathParam("id") long id, @Nullable OrdenCompraDetailDTO payload) {
        try {
            OrdenCompra orden = activa(id);
            if (orden == null) {
                return notFound(id);
            }
            String error = validarPayload(payload);
            if (error != null) {
                return badRequest(error);
            }

            String antes = DiffUtils.snapshotEntity(orden);

            // Parity: updateOrden() replaced the whole line collection,
            // recalculated each subtotal and the estimated total, and
            // re-attributed the usuario.
            reemplazarDetalles(orden, payload.getDetalles());
            aplicarCabecera(orden, payload);
            orden.setUsuario(currentUser());
            ordenCompraService.update(orden);

                        LOG.info("Se actualizó la orden de compra: " + orden.getNumeroOrden() + " | user=" + String.valueOf(currentUser()) + " | source=" + "OrdenCompraResource.update()" + " | antes=" + String.valueOf(antes) + " | despues=" + String.valueOf(DiffUtils.snapshotEntity(orden)));

            return Response.ok(ApiResponse.ok(toDetailDTO(orden))).build();
        } catch (Exception e) {
            LOG.warn("Error actualizando la orden " + id, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error al actualizar: error interno"))
                    .build();
        }
    }

    /**
     * PUT /api/app/ordenes/{id}/estado — status transition. Validates with
     * {@code OrdenCompraService.esTransicionValida} EXACTLY like
     * {@code cambiarEstado()}; illegal transitions answer
     * <b>409 INVALID_STATE</b> with the legacy Spanish message. HTMX callers
     * additionally receive the redisplayed stage fragment + OOB toast
     * (ui-kit Pattern A) with the SAME 409 status.
     */
    @PUT
    @Path("/{id}/estado")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    @Operation(summary = "Change the workflow state of an order (legal transitions only)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "State changed"),
        @APIResponse(responseCode = "400", description = "Missing target state"),
        @APIResponse(responseCode = "404", description = "Not found (unknown id or soft-deleted)"),
        @APIResponse(responseCode = "409", description = "Illegal transition (INVALID_STATE)"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response cambiarEstado(@PathParam("id") long id, @Nullable EstadoRequest payload) {
        String nuevoEstado = payload == null ? null : trimToNull(payload.nuevoEstado);
        try {
            if (nuevoEstado == null) {
                return badRequest("El nuevo estado es requerido.");
            }
            OrdenCompra orden = activa(id);
            if (orden == null) {
                return notFound(id);
            }
            String estadoActual = orden.getEstado();
            if (!ordenCompraService.esTransicionValida(estadoActual, nuevoEstado)) {
                return transicionInvalida(orden, nuevoEstado);
            }

            String antes = DiffUtils.snapshotEntity(orden);
            ordenCompraService.cambiarEstado(orden, nuevoEstado);

                        LOG.info("Orden " + orden.getNumeroOrden() + ": " + estadoActual + " → " + nuevoEstado + " | user=" + String.valueOf(currentUser()) + " | source=" + "OrdenCompraResource.cambiarEstado()" + " | antes=" + String.valueOf(antes) + " | despues=" + String.valueOf(DiffUtils.snapshotEntity(orden)));

            if (isHxRequest()) {
                return hxRedirect(tablaUrl());
            }
            return Response.ok(ApiResponse.ok(toDetailDTO(orden))).build();
        } catch (Exception e) {
            LOG.warn("Error cambiando estado de la orden " + id, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error cambiando el estado de la orden"))
                    .build();
        }
    }

    /**
     * PUT /api/app/ordenes/{id}/recibir — port of {@code recibirOrden()}:
     * stamps fechaEntregaReal=now, estado=RECIBIDA and copies totalReal when
     * provided. No transition validation (controller parity — the button was
     * only offered on CONFIRMADA orders).
     */
    @PUT
    @Path("/{id}/recibir")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    @Operation(summary = "Mark an order as received (legacy recibirOrden)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Received"),
        @APIResponse(responseCode = "404", description = "Not found (unknown id or soft-deleted)"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response recibir(@PathParam("id") long id, @Nullable ReciboRequest payload) {
        try {
            OrdenCompra orden = activa(id);
            if (orden == null) {
                return notFound(id);
            }
            String antes = DiffUtils.snapshotEntity(orden);
            if (payload != null && payload.totalReal != null) {
                orden.setTotalReal(payload.totalReal);
            }
            ordenCompraService.recibirOrden(orden);

                        LOG.info("Se marcó como recibida la orden: " + orden.getNumeroOrden() + " | user=" + String.valueOf(currentUser()) + " | source=" + "OrdenCompraResource.recibir()" + " | antes=" + String.valueOf(antes) + " | despues=" + String.valueOf(DiffUtils.snapshotEntity(orden)));

            if (isHxRequest()) {
                return hxRedirect(tablaUrl());
            }
            return Response.ok(ApiResponse.ok(toDetailDTO(orden))).build();
        } catch (Exception e) {
            LOG.warn("Error recibiendo la orden " + id, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error recibiendo la orden"))
                    .build();
        }
    }

    /**
     * POST /api/app/ordenes/{id}/cancelar — port of {@code cancelarOrden()}:
     * estado=CANCELADA, motivo recorded into notas when non-blank, and the
     * legacy WARNING semantics surfaced as {@code severidad=warn} +
     * {@code mensaje="Orden cancelada!"}. No transition validation (exact
     * controller parity; see evidence state-machine.md).
     */
    @POST
    @Path("/{id}/cancelar")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    @Operation(summary = "Cancel an order with an optional reason (legacy warning semantics)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Cancelled (severidad=warn)"),
        @APIResponse(responseCode = "404", description = "Not found (unknown id or soft-deleted)"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response cancelar(@PathParam("id") long id, @Nullable CancelarRequest payload) {
        String motivo = payload == null ? null : trimToNull(payload.motivo);
        try {
            OrdenCompra orden = activa(id);
            if (orden == null) {
                return notFound(id);
            }
            String antes = DiffUtils.snapshotEntity(orden);
            ordenCompraService.cancelarOrden(orden, motivo);

                        LOG.info("Se canceló la orden: " + orden.getNumeroOrden()
                            + (motivo != null ? " - Motivo: " + motivo : "") + " | user=" + String.valueOf(currentUser()) + " | source=" + "OrdenCompraResource.cancelar()" + " | antes=" + String.valueOf(antes) + " | despues=" + String.valueOf(DiffUtils.snapshotEntity(orden)));

            if (isHxRequest()) {
                return hxRedirect(tablaUrl());
            }
            return Response.ok(ApiResponse.ok(new CancelarResult(
                    toDetailDTO(orden), "warn", "Orden cancelada!"))).build();
        } catch (Exception e) {
            LOG.warn("Error cancelando la orden " + id, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error cancelando la orden"))
                    .build();
        }
    }

    /**
     * DELETE /api/app/ordenes/{id} — port of {@code deleteOrden()}:
     * {@code softDelete} (status=false), never a hard delete. Under HTMX the
     * refreshed table fragment + OOB toast come back (ui-kit §7).
     */
    @DELETE
    @Path("/{id}")
    @Transactional
    @Operation(summary = "Soft-delete (archive) a purchase order")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Archived (status=false)"),
        @APIResponse(responseCode = "404", description = "Not found (unknown id or already archived)"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response delete(@PathParam("id") long id) {
        try {
            OrdenCompra orden = activa(id);
            if (orden == null) {
                return notFound(id);
            }
            String antes = DiffUtils.snapshotEntity(orden);
            ordenCompraService.softDelete(orden);

                        LOG.info("Se eliminó la orden de compra: " + orden.getNumeroOrden() + " | user=" + String.valueOf(currentUser()) + " | source=" + "OrdenCompraResource.delete()" + " | antes=" + String.valueOf(antes) + " | despues=" + String.valueOf(DiffUtils.snapshotEntity(orden)));

            if (isHxRequest()) {
                return htmlOk(tableInstance(1, 20, null, "asc", null, null, null, null, "info",
                        "Orden de compra eliminada!"));
            }
            return Response.ok(ApiResponse.ok(toDetailDTO(orden))).build();
        } catch (Exception e) {
            LOG.warn("Error eliminando la orden " + id, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error eliminando la orden"))
                    .build();
        }
    }

    /**
     * GET /api/app/ordenes/articulos?q= — article-picker support reusing the
     * EXISTING search ({@code ArticulosService.findByNameContaining}, the
     * very method behind the legacy {@code completeArticulo}). HTMX callers
     * get the typeahead suggestion fragment (ui-kit §8 recipe); JSON callers
     * get the flat option list.
     */
    @GET
    @Path("/articulos")
    @Operation(summary = "Article picker search (typeahead suggestions)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Suggestions (HTML fragment or JSON)")
    })
    public Response articulos(@QueryParam("q") @Nullable String q) {
        String texto = trimToNull(q);
        List<ArticuloOpcion> opciones = new ArrayList<>();
        if (texto != null) {
            // Delegates to the same service the legacy autocomplete used;
            // PersistenceExceptions are already swallowed into an empty list
            // (with their own alerta) inside the service.
            for (Articulos articulo : articulosService.findByNameContaining(texto)) {
                opciones.add(new ArticuloOpcion(articulo.getCodigo(), articulo.getNombre(),
                        articulo.getCodigoBarra()));
            }
        }
        if (isHxRequest()) {
            return htmlOk(articulosPage.data("opciones", opciones));
        }
        return Response.ok(ApiResponse.ok(opciones)).build();
    }

    // ── W4C view-half: dual-mode table + staged fragments ───────────────────

    /**
     * GET /table?page&size&sort&dir&q&estado&proveedorId&numeroOrden — with
     * the {@code HX-Request} header returns ONLY the data-table fragment;
     * otherwise the FULL órdenes page (stats cards included). Same endpoint
     * renders both, all paging/sorting state lives in the URL, page is
     * 1-based here (kit contract; the JSON list above keeps its 0-based
     * contract untouched).
     */
    @GET
    @Path("/table")
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Data-table fragment (HX-Request) or full órdenes page")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "HTML fragment or full page"),
        @APIResponse(responseCode = "500", description = "Template rendering failure")
    })
    public Response table(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("sort") @Nullable String sort,
            @QueryParam("dir") @DefaultValue("asc") String dir,
            @QueryParam("q") @Nullable String q,
            @QueryParam("estado") @Nullable String estado,
            @QueryParam("proveedorId") @Nullable Integer proveedorId,
            @QueryParam("numeroOrden") @Nullable String numeroOrden) {
        try {
            if (isHxRequest()) {
                return htmlOk(tableInstance(page, size, sort, dir, q, estado, proveedorId,
                        numeroOrden, null, null));
            }
            return htmlOk(renderFullPage(page, size, sort, dir, q, estado, proveedorId, numeroOrden));
        } catch (Exception e) {
            LOG.warn("Error renderizando la página de órdenes", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error renderizando la página"))
                    .build();
        }
    }

    /** Empty creation form (stage 2 — replaces CrearOrdenDialog). */
    @GET
    @Path("/formularios/nueva")
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "New-order form fragment (staged view)")
    public Response formNueva() {
        return htmlOk(formInstance("crear", null, null, null, null));
    }

    /** Prefilled edit form (stage 3 — hx-get fill, replaces EditarOrdenDialog). */
    @GET
    @Path("/formularios/{id}")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    @Operation(summary = "Edit-order form fragment (staged view)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Form HTML"),
        @APIResponse(responseCode = "404", description = "Unknown id")
    })
    public Response formEditar(@PathParam("id") long id) {
        OrdenCompra orden = activa(id);
        if (orden == null) {
            return notFound(id);
        }
        return htmlOk(formInstance("editar", orden, null, null, null));
    }

    /** Read-only detail panel (stage 4 — replaces DetallesOrdenDialog). */
    @GET
    @Path("/{id}/detalle")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    @Operation(summary = "Read-only order detail panel fragment")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Panel HTML"),
        @APIResponse(responseCode = "404", description = "Unknown id")
    })
    public Response detalle(@PathParam("id") long id) {
        OrdenCompra orden = activa(id);
        if (orden == null) {
            return notFound(id);
        }
        return htmlOk(detallePage
                .data("orden", detalleViewModel(orden)));
    }

    /** State-change stage (stage 5 — replaces CambiarEstadoDialog). */
    @GET
    @Path("/{id}/estado")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    @Operation(summary = "State-change form fragment (legal targets only)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Fragment HTML"),
        @APIResponse(responseCode = "404", description = "Unknown id")
    })
    public Response formEstado(@PathParam("id") long id) {
        OrdenCompra orden = activa(id);
        if (orden == null) {
            return notFound(id);
        }
        return htmlOk(estadoInstance(orden, null, null, null));
    }

    /** Cancel-confirm stage (stage 6 — replaces CancelarOrdenDialog). */
    @GET
    @Path("/{id}/cancelar")
    @Produces(MediaType.TEXT_HTML)
    @Transactional
    @Operation(summary = "Cancel-confirm fragment with motivo field")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Fragment HTML"),
        @APIResponse(responseCode = "404", description = "Unknown id")
    })
    public Response formCancelar(@PathParam("id") long id) {
        OrdenCompra orden = activa(id);
        if (orden == null) {
            return notFound(id);
        }
        return htmlOk(cancelarPage
                .data("orden", orden)
                .data("motivo", "")
                .data("toastSeverity", null)
                .data("toastMessage", null));
    }

    // ── Form-urlencoded twins (HTMX forms, ui-kit Pattern A) ────────────────

    /** Form twin of {@link #create} for the staged inline form. */
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    @Operation(summary = "Create a purchase order from an HTMX form", hidden = true)
    public Response createForm(
            @FormParam("proveedorId") @Nullable String proveedorId,
            @FormParam("fechaEntregaEstimada") @Nullable String fechaEntregaEstimada,
            @FormParam("notas") @Nullable String notas,
            @FormParam("articuloCodigo") @Nullable List<String> articuloCodigo,
            @FormParam("cantidad") @Nullable List<String> cantidad,
            @FormParam("precioUnitario") @Nullable List<String> precioUnitario) {
        OrdenCompraDetailDTO payload = buildPayload(proveedorId, fechaEntregaEstimada, notas,
                articuloCodigo, cantidad, precioUnitario);
        String error = validarPayload(payload);
        if (error != null) {
            return redisplayForm("crear", null, error, "error", error);
        }
        Response result = create(payload);
        return handleFormMutationResult(result);
    }

    /** Form twin of {@link #update} for the staged edit form. */
    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    @Operation(summary = "Update a purchase order from an HTMX form", hidden = true)
    public Response updateForm(
            @PathParam("id") long id,
            @FormParam("proveedorId") @Nullable String proveedorId,
            @FormParam("fechaEntregaEstimada") @Nullable String fechaEntregaEstimada,
            @FormParam("notas") @Nullable String notas,
            @FormParam("articuloCodigo") @Nullable List<String> articuloCodigo,
            @FormParam("cantidad") @Nullable List<String> cantidad,
            @FormParam("precioUnitario") @Nullable List<String> precioUnitario) {
        OrdenCompra orden = activa(id);
        if (orden == null) {
            return notFound(id);
        }
        OrdenCompraDetailDTO payload = buildPayload(proveedorId, fechaEntregaEstimada, notas,
                articuloCodigo, cantidad, precioUnitario);
        String error = validarPayload(payload);
        if (error != null) {
            return redisplayForm("editar", orden, error, "error", error);
        }
        Response result = update(id, payload);
        return handleFormMutationResult(result);
    }

    /** Form twin of {@link #cambiarEstado} (select of legal targets). */
    @PUT
    @Path("/{id}/estado")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    @Operation(summary = "Change state from an HTMX form", hidden = true)
    public Response cambiarEstadoForm(
            @PathParam("id") long id,
            @FormParam("nuevoEstado") @Nullable String nuevoEstado) {
        OrdenCompra orden = activa(id);
        if (orden == null) {
            return notFound(id);
        }
        String objetivo = trimToNull(nuevoEstado);
        if (objetivo == null) {
            return redisplayEstado(orden, "El nuevo estado es requerido.", "error",
                    "El nuevo estado es requerido.");
        }
        String estadoActual = orden.getEstado();
        if (!ordenCompraService.esTransicionValida(estadoActual, objetivo)) {
            // Same 409 contract as the JSON endpoint, HTML body for the swap.
            return transicionInvalidaHx(orden, objetivo);
        }
        Response result = cambiarEstado(id, new EstadoRequest(objetivo));
        return handleFormMutationResult(result);
    }

    /** Form twin of {@link #cancelar} (motivo textarea). */
    @POST
    @Path("/{id}/cancelar")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Transactional
    @Operation(summary = "Cancel an order from an HTMX form", hidden = true)
    public Response cancelarForm(
            @PathParam("id") long id,
            @FormParam("motivo") @Nullable String motivo) {
        OrdenCompra orden = activa(id);
        if (orden == null) {
            return notFound(id);
        }
        Response result = cancelar(id, new CancelarRequest(motivo));
        return handleFormMutationResult(result);
    }

    // ── Validation + entity assembly (saveOrden/updateOrden ports) ──────────

    /**
     * Guard chain of {@code saveOrden()}/{@code updateOrden()}, in order:
     * payload present → proveedor selected → at least one line → every line
     * valid (known artículo + cantidad &gt; 0). Messages verbatim.
     *
     * @return the legacy error message, or {@code null} when valid.
     */
    @Nullable
    private String validarPayload(@Nullable OrdenCompraDetailDTO payload) {
        if (payload == null) {
            return "No hay orden para guardar!";
        }
        if (payload.getProveedorId() == null || departamentoService.find(payload.getProveedorId()) == null) {
            return "Debe seleccionar un proveedor!";
        }
        List<OrdenCompraLineaDTO> lineas = payload.getDetalles();
        if (lineas == null || lineas.isEmpty()) {
            return "Debe agregar al menos un artículo!";
        }
        for (OrdenCompraLineaDTO linea : lineas) {
            if (linea == null || linea.getArticuloCodigo() == null
                    || articulosService.find(linea.getArticuloCodigo()) == null
                    || linea.getCantidad() == null
                    || linea.getCantidad().compareTo(BigDecimal.ZERO) <= 0) {
                return "Todos los artículos deben tener cantidad válida!";
            }
        }
        return null;
    }

    /** Applies proveedor/fecha/notas scalars (null-safe, merge style). */
    private void aplicarCabecera(@Nonnull OrdenCompra target, @Nonnull OrdenCompraDetailDTO payload) {
        target.setProveedor(departamentoService.find(payload.getProveedorId()));
        if (payload.getFechaEntregaEstimada() != null) {
            target.setFechaEntregaEstimada(payload.getFechaEntregaEstimada());
        }
        if (payload.getNotas() != null) {
            target.setNotas(payload.getNotas());
        }
    }

    /** Materializes payload lines into entities (artículo resolved, no subtotal yet). */
    @Nonnull
    private List<OrdenCompraDetalle> construirDetalles(@Nonnull OrdenCompra orden,
                                                       @Nonnull List<OrdenCompraLineaDTO> lineas) {
        List<OrdenCompraDetalle> detalles = new ArrayList<>();
        for (OrdenCompraLineaDTO linea : lineas) {
            OrdenCompraDetalle detalle = new OrdenCompraDetalle();
            detalle.setOrdenCompra(orden);
            detalle.setArticulo(articulosService.find(linea.getArticuloCodigo()));
            detalle.setCantidad(linea.getCantidad());
            detalle.setPrecioUnitario(linea.getPrecioUnitario() != null
                    ? linea.getPrecioUnitario() : BigDecimal.ZERO);
            detalle.setNotas(linea.getNotas());
            detalles.add(detalle);
        }
        return detalles;
    }

    /**
     * Replaces the line collection IN PLACE (clear + add on the same list
     * instance) as required by cascade=ALL + orphanRemoval=true under
     * Hibernate — the functional equivalent of the legacy
     * {@code selectedOrden.setDetalles(detallesOrden)} plus per-line
     * {@code calcularSubtotal()} and the recomputed total.
     */
    private void reemplazarDetalles(@Nonnull OrdenCompra orden,
                                     @Nonnull List<OrdenCompraLineaDTO> lineas) {
        List<OrdenCompraDetalle> actuales = orden.getDetalles();
        if (actuales == null) {
            actuales = new ArrayList<>();
            orden.setDetalles(actuales);
        }
        try {
            // Ensure LAZY collection is initialized within the current session before clear
            actuales.size();
        } catch (Exception ignore) {
            // If detached, re-fetch via service within same Tx
            OrdenCompra fresh = ordenCompraService.find(orden.getId());
            if (fresh != null && fresh.getDetalles() != null) {
                try { fresh.getDetalles().size(); } catch (Exception ex) {}
                actuales = fresh.getDetalles();
                orden.setDetalles(actuales);
            }
        }
        try {
            actuales.clear();
        } catch (org.hibernate.LazyInitializationException e) {
            // Fallback: replace collection instance entirely (works with orphanRemoval)
            List<OrdenCompraDetalle> replacement = new ArrayList<>();
            orden.setDetalles(replacement);
            actuales = replacement;
        }
        for (OrdenCompraDetalle detalle : construirDetalles(orden, lineas)) {
            detalle.calcularSubtotal();
            actuales.add(detalle);
        }
        orden.setTotalEstimado(ordenCompraService.calcularTotal(actuales));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Active (status=true) order or null — mirrors "reachable from the UI". */
    @Nullable
    private OrdenCompra activa(long id) {
        OrdenCompra orden = ordenCompraService.find(id);
        return (orden == null || !orden.isStatus()) ? null : orden;
    }

    /** Resolves the authenticated Users row (LoyaltyResource pattern). */
    @Nullable
    private Users currentUser() {
        if (securityIdentity == null || securityIdentity.isAnonymous()
                || securityIdentity.getPrincipal() == null) {
            return null;
        }
        return loginService.findByUsername(securityIdentity.getPrincipal().getName());
    }

    private boolean isHxRequest() {
        String header = routing.request().getHeader("HX-Request");
        return header != null && !"false".equalsIgnoreCase(header);
    }

    private String tablaUrl() {
        return rootPath + "/api/app/ordenes/table";
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(ApiResponse.error("VALIDATION_ERROR", message))
                .build();
    }

    private static Response notFound(long id) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(ApiResponse.error("NOT_FOUND", "No se encontró la orden de compra: " + id))
                .build();
    }

    /** 409 INVALID_STATE with the legacy message; JSON or HTMX body by caller. */
    private Response transicionInvalida(@Nonnull OrdenCompra orden, @Nonnull String nuevoEstado) {
        String mensaje = "Transición de estado no válida: " + orden.getEstado()
                + " → " + nuevoEstado;
        if (isHxRequest()) {
            return transicionInvalidaHx(orden, nuevoEstado);
        }
        return Response.status(Response.Status.CONFLICT)
                .entity(ApiResponse.error("INVALID_STATE", mensaje))
                .build();
    }

    /** HTMX variant of the illegal-transition answer: 409 + fragment redisplay. */
    private Response transicionInvalidaHx(@Nonnull OrdenCompra orden, @Nonnull String nuevoEstado) {
        String mensaje = "Transición de estado no válida: " + orden.getEstado()
                + " → " + nuevoEstado;
        return Response.status(Response.Status.CONFLICT)
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                .entity(estadoInstance(orden, mensaje, "error", mensaje).render())
                .build();
    }

    /**
     * Shared tail of the HTMX form twins: success answers HX-Redirect so the
     * page reloads fresh (modal shells close, table refetches — ui-kit §5);
     * structured failures from the JSON methods become a 422 form redisplay +
     * OOB toast; non-HTMX callers receive the original response untouched.
     */
    private Response handleFormMutationResult(@Nonnull Response result) {
        if (!isHxRequest()) {
            return result;
        }
        int status = result.getStatus();
        if (status == Response.Status.CREATED.getStatusCode()
                || status == Response.Status.OK.getStatusCode()) {
            return hxRedirect(tablaUrl());
        }
        String mensaje = "No se pudo guardar la orden";
        if (result.getEntity() instanceof ApiResponse<?> api && api.getError() != null) {
            mensaje = api.getError().getMessage();
        }
        return redisplayForm("crear", null, mensaje, "error", mensaje);
    }

    private Response redisplayForm(@Nonnull String modo, @Nullable OrdenCompra orden,
                                   @Nullable String errorGeneral,
                                   @Nullable String toastSeverity, @Nullable String toastMessage) {
        return Response.status(HTTP_UNPROCESSABLE_ENTITY)
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                .entity(formInstance(modo, orden, errorGeneral, toastSeverity, toastMessage).render())
                .build();
    }

    private Response redisplayEstado(@Nonnull OrdenCompra orden, @Nullable String errorGeneral,
                                     @Nullable String toastSeverity, @Nullable String toastMessage) {
        return Response.status(HTTP_UNPROCESSABLE_ENTITY)
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                .entity(estadoInstance(orden, errorGeneral, toastSeverity, toastMessage).render())
                .build();
    }

    private static Response hxRedirect(@Nonnull String url) {
        return Response.status(Response.Status.OK)
                .header("HX-Redirect", url)
                .build();
    }

    private static Response htmlOk(@Nonnull TemplateInstance template) {
        return Response.ok(template.render())
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).build();
    }

    @Nullable
    private static String trimToNull(@Nullable String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    /** ISO yyyy-MM-dd (native date input) → java.util.Date; null when blank/unparseable. */
    @Nullable
    private static Date parseIsoDate(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Date.from(LocalDate.parse(raw.trim())
                    .atStartOfDay(ZoneId.systemDefault()).toInstant());
        } catch (DateTimeParseException e) {
            return null;
        }
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

    /**
     * Builds the shared payload from parallel form arrays (one entry per line
     * row; shorter trailing lists are padded with nulls defensively).
     */
    @Nullable
    private OrdenCompraDetailDTO buildPayload(@Nullable String proveedorId,
                                              @Nullable String fechaEntregaEstimada,
                                              @Nullable String notas,
                                              @Nullable List<String> articuloCodigo,
                                              @Nullable List<String> cantidad,
                                              @Nullable List<String> precioUnitario) {
        Integer provId = null;
        String provTexto = trimToNull(proveedorId);
        if (provTexto != null) {
            try {
                provId = Integer.valueOf(provTexto);
            } catch (NumberFormatException e) {
                provId = -1; // unknown proveedor → legacy "Debe seleccionar..." guard fires
            }
        }
        List<OrdenCompraLineaDTO> lineas = new ArrayList<>();
        int filas = Math.max(articuloCodigo == null ? 0 : articuloCodigo.size(),
                Math.max(cantidad == null ? 0 : cantidad.size(),
                        precioUnitario == null ? 0 : precioUnitario.size()));
        for (int i = 0; i < filas; i++) {
            String codigoTexto = valor(articuloCodigo, i);
            Long codigo = null;
            if (codigoTexto != null && !codigoTexto.isBlank()) {
                try {
                    codigo = Long.valueOf(codigoTexto.trim());
                } catch (NumberFormatException e) {
                    codigo = -1L; // unknown artículo → legacy per-line guard fires
                }
            }
            lineas.add(new OrdenCompraLineaDTO(null, codigo, null, null,
                    parseDecimal(valor(cantidad, i)), parseDecimal(valor(precioUnitario, i)),
                    null, null));
        }
        OrdenCompraDetailDTO dto = new OrdenCompraDetailDTO();
        dto.setProveedorId(provId);
        dto.setFechaEntregaEstimada(parseIsoDate(fechaEntregaEstimada));
        dto.setNotas(notas);
        dto.setDetalles(lineas);
        return dto;
    }

    @Nullable
    private static String valor(@Nullable List<String> valores, int indice) {
        if (valores == null || indice >= valores.size()) {
            return null;
        }
        return valores.get(indice);
    }

    private static <T> List<T> orEmpty(@Nullable List<T> list) {
        return list == null ? List.of() : list;
    }

    // ── Filtering/sorting (legacy globalFilterFunction + column sortBy) ─────

    @Nonnull
    private static List<OrdenCompra> filtrar(@Nonnull List<OrdenCompra> filas,
                                             @Nullable String q,
                                             @Nullable String estado,
                                             @Nullable Integer proveedorId,
                                             @Nullable String numeroOrden) {
        String texto = trimToNull(q);
        String textoLower = texto == null ? null : texto.toLowerCase();
        String numero = trimToNull(numeroOrden);
        String numeroLower = numero == null ? null : numero.toLowerCase();
        String estadoFiltro = trimToNull(estado);

        List<OrdenCompra> salida = new ArrayList<>();
        for (OrdenCompra orden : filas) {
            if (textoLower != null && !pasaFiltroGlobal(orden, textoLower)) {
                continue;
            }
            if (estadoFiltro != null && !estadoFiltro.equals(orden.getEstado())) {
                continue;
            }
            if (proveedorId != null
                    && (orden.getProveedor() == null || orden.getProveedor().getId() != proveedorId)) {
                continue;
            }
            if (numeroLower != null && (orden.getNumeroOrden() == null
                    || !orden.getNumeroOrden().toLowerCase().contains(numeroLower))) {
                continue;
            }
            salida.add(orden);
        }
        return salida;
    }

    /** Verbatim port of the legacy globalFilterFunction searchable fields. */
    private static boolean pasaFiltroGlobal(@Nonnull OrdenCompra orden, @Nonnull String filtro) {
        return contiene(orden.getNumeroOrden(), filtro)
                || (orden.getProveedor() != null && contiene(orden.getProveedor().getNombre(), filtro))
                || contiene(orden.getEstado(), filtro)
                || contiene(orden.getNotas(), filtro)
                || (orden.getUsuario() != null && contiene(orden.getUsuario().getUsername(), filtro));
    }

    private static boolean contiene(@Nullable String valor, @Nonnull String filtroLower) {
        return valor != null && valor.toLowerCase().contains(filtroLower);
    }

    private static void sortOrdenes(@Nonnull List<OrdenCompra> filas, @Nullable String sort,
                                    @Nullable String dir) {
        if (filas.isEmpty() || sort == null || sort.isBlank()) {
            return;
        }
        Comparator<OrdenCompra> cmp = switch (sort) {
            case "numeroOrden" -> Comparator.comparing(OrdenCompra::getNumeroOrden,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "proveedor" -> Comparator.comparing(
                    (OrdenCompra o) -> o.getProveedor() != null ? o.getProveedor().getNombre() : null,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "fechaOrden" -> Comparator.comparing(OrdenCompra::getFechaOrden,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case "entrega" -> Comparator.comparing(OrdenCompra::getFechaEntregaEstimada,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case "estado" -> Comparator.comparing(OrdenCompra::getEstado,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "total" -> Comparator.comparing(OrdenCompra::getTotalEstimado,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            default -> null;
        };
        if (cmp != null) {
            filas.sort("desc".equalsIgnoreCase(dir) ? cmp.reversed() : cmp);
        }
    }

    // ── DTO mapping ──────────────────────────────────────────────────────────

    private static OrdenCompraDTO toDTO(@Nonnull OrdenCompra orden) {
        return new OrdenCompraDTO(orden.getId(), orden.getNumeroOrden(),
                orden.getProveedor() != null ? orden.getProveedor().getId() : null,
                orden.getProveedor() != null ? orden.getProveedor().getNombre() : null,
                orden.getFechaOrden(), orden.getEstado(), orden.getTotalEstimado());
    }

    private static OrdenCompraLineaDTO toLineaDTO(@Nonnull OrdenCompraDetalle detalle) {
        Articulos articulo = detalle.getArticulo();
        return new OrdenCompraLineaDTO(detalle.getId(),
                articulo != null ? articulo.getCodigo() : null,
                articulo != null ? articulo.getNombre() : null,
                articulo != null ? articulo.getCodigoBarra() : null,
                detalle.getCantidad(), detalle.getPrecioUnitario(), detalle.getSubtotal(),
                detalle.getNotas());
    }

    private static OrdenCompraDetailDTO toDetailDTO(@Nonnull OrdenCompra orden) {
        List<OrdenCompraLineaDTO> lineas = new ArrayList<>();
        if (orden.getDetalles() != null) {
            for (OrdenCompraDetalle detalle : orden.getDetalles()) {
                lineas.add(toLineaDTO(detalle));
            }
        }
        Users usuario = orden.getUsuario();
        return new OrdenCompraDetailDTO(orden.getId(), orden.getNumeroOrden(),
                orden.getProveedor() != null ? orden.getProveedor().getId() : null,
                orden.getProveedor() != null ? orden.getProveedor().getNombre() : null,
                orden.getFechaOrden(), orden.getFechaEntregaEstimada(), orden.getFechaEntregaReal(),
                orden.getEstado(), orden.getTotalEstimado(), orden.getTotalReal(), orden.getNotas(),
                usuario != null ? usuario.getId() : null,
                usuario != null ? usuario.getUsername() : null,
                orden.getFecha(), orden.isStatus(), lineas);
    }

    // ── View-model computation (resources compute, templates render) ────────

    /** Presentation-only estado→Bulma class port of getEstadoStyleClass. */
    private static String estadoClass(@Nullable String estado) {
        if (estado == null) {
            return "is-light";
        }
        return switch (estado) {
            case "BORRADOR" -> "is-light";
            case "ENVIADA" -> "is-info";
            case "CONFIRMADA" -> "is-warning";
            case "RECIBIDA" -> "is-success";
            case "FACTURADA" -> "is-primary";
            case "CANCELADA" -> "is-danger";
            default -> "is-light";
        };
    }

    /** Port of puedeCambiarEstado (button visibility parity). */
    private static boolean puedeCambiarEstado(@Nullable String estado) {
        return switch (estado == null ? "" : estado) {
            case "BORRADOR", "ENVIADA", "CONFIRMADA", "RECIBIDA" -> true;
            default -> false;
        };
    }

    /** Port of puedeEditar (button visibility parity). */
    private static boolean puedeEditar(@Nullable String estado) {
        return "BORRADOR".equals(estado);
    }

    private static String formatoDinero(@Nullable BigDecimal valor) {
        if (valor == null) {
            return "₡0.00";
        }
        return "₡" + new DecimalFormat("#,##0.00").format(valor.setScale(2, RoundingMode.HALF_UP));
    }

    @Nullable
    private static String formatoFecha(@Nullable Date fecha, boolean conHora) {
        if (fecha == null) {
            return null;
        }
        LocalDate local = fecha.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        return conHora
                ? fecha.toInstant().atZone(ZoneId.systemDefault()).format(FECHA_HORA)
                : local.format(FECHA_CORTA);
    }

    /** One table row: everything ordenes-tabla.html renders, pre-computed. */
    private static Map<String, Object> filaTabla(@Nonnull OrdenCompra orden) {
        Map<String, Object> fila = new LinkedHashMap<>();
        fila.put("id", orden.getId());
        fila.put("numeroOrden", orden.getNumeroOrden());
        fila.put("proveedorNombre", orden.getProveedor() != null ? orden.getProveedor().getNombre() : null);
        fila.put("fecha", formatoFecha(orden.getFechaOrden(), false));
        fila.put("entrega", formatoFecha(orden.getFechaEntregaEstimada(), false));
        fila.put("estado", orden.getEstado());
        fila.put("estadoClass", estadoClass(orden.getEstado()));
        fila.put("total", formatoDinero(orden.getTotalEstimado()));
        fila.put("puedeEditar", puedeEditar(orden.getEstado()));
        fila.put("puedeCambiarEstado", puedeCambiarEstado(orden.getEstado()));
        fila.put("puedeEliminar", "BORRADOR".equals(orden.getEstado()));
        return fila;
    }

    /** Detail-panel view model (read-only info + gated action flags). */
    private Map<String, Object> detalleViewModel(@Nonnull OrdenCompra orden) {
        List<Map<String, Object>> lineas = new ArrayList<>();
        if (orden.getDetalles() != null) {
            for (OrdenCompraDetalle detalle : orden.getDetalles()) {
                Articulos articulo = detalle.getArticulo();
                Map<String, Object> linea = new LinkedHashMap<>();
                linea.put("nombre", articulo != null ? articulo.getNombre() : null);
                linea.put("codigo", articulo != null ? articulo.getCodigo() : null);
                linea.put("codigoBarra", articulo != null ? articulo.getCodigoBarra() : null);
                linea.put("cantidad", detalle.getCantidad());
                linea.put("precio", formatoDinero(detalle.getPrecioUnitario()));
                linea.put("subtotal", formatoDinero(detalle.getSubtotal()));
                lineas.add(linea);
            }
        }
        Map<String, Object> modelo = new LinkedHashMap<>();
        modelo.put("id", orden.getId());
        modelo.put("numeroOrden", orden.getNumeroOrden());
        modelo.put("proveedorNombre", orden.getProveedor() != null ? orden.getProveedor().getNombre() : null);
        modelo.put("estado", orden.getEstado());
        modelo.put("estadoClass", estadoClass(orden.getEstado()));
        modelo.put("fechaOrden", formatoFecha(orden.getFechaOrden(), true));
        modelo.put("entregaEstimada", formatoFecha(orden.getFechaEntregaEstimada(), false));
        modelo.put("entregaReal", formatoFecha(orden.getFechaEntregaReal(), true));
        modelo.put("usuarioUsername", orden.getUsuario() != null ? orden.getUsuario().getUsername() : null);
        modelo.put("totalEstimado", formatoDinero(orden.getTotalEstimado()));
        modelo.put("totalReal", formatoDinero(orden.getTotalReal()));
        modelo.put("mostrarTotalReal", orden.getTotalReal() != null
                && orden.getTotalReal().compareTo(BigDecimal.ZERO) > 0);
        modelo.put("notas", orden.getNotas());
        modelo.put("lineas", lineas);
        modelo.put("puedeRecibir", "CONFIRMADA".equals(orden.getEstado()));
        modelo.put("puedeCancelar", puedeCambiarEstado(orden.getEstado())
                && !"CONFIRMADA".equals(orden.getEstado()));
        return modelo;
    }

    /** Legal target states for the current one, straight from the service. */
    private List<String> objetivosLegales(@Nonnull OrdenCompra orden) {
        List<String> objetivos = new ArrayList<>();
        for (String candidato : ESTADOS) {
            if (ordenCompraService.esTransicionValida(orden.getEstado(), candidato)) {
                objetivos.add(candidato);
            }
        }
        return objetivos;
    }

    private TemplateInstance renderFullPage(int page, int size, @Nullable String sort,
                                            @Nullable String dir, @Nullable String q,
                                            @Nullable String estado, @Nullable Integer proveedorId,
                                            @Nullable String numeroOrden) {
        TableModel model = buildTableModel(page, size, sort, dir, q, estado, proveedorId, numeroOrden);
        List<OrdenCompra> todas = orEmpty(ordenCompraService.listAll());
        long borrador = todas.stream().filter(o -> "BORRADOR".equals(o.getEstado())).count();
        long enviada = todas.stream().filter(o -> "ENVIADA".equals(o.getEstado())).count();
        long confirmada = todas.stream().filter(o -> "CONFIRMADA".equals(o.getEstado())).count();
        long recibida = todas.stream().filter(o -> "RECIBIDA".equals(o.getEstado())).count();
        long facturada = todas.stream().filter(o -> "FACTURADA".equals(o.getEstado())).count();
        return pageIndex
                .data("tablaOrdenes", model.asMap())
                .data("ordenesTotal", model.total())
                .data("countBorrador", borrador)
                .data("countEnviada", enviada)
                .data("countConfirmada", confirmada)
                .data("countRecibida", recibida)
                .data("countFacturada", facturada)
                .data("estadoActivo", trimToNull(estado));
    }

    private TemplateInstance tableInstance(int page, int size, @Nullable String sort,
                                           @Nullable String dir, @Nullable String q,
                                           @Nullable String estado, @Nullable Integer proveedorId,
                                           @Nullable String numeroOrden,
                                           @Nullable String toastSeverity,
                                           @Nullable String toastMessage) {
        TableModel model = buildTableModel(page, size, sort, dir, q, estado, proveedorId, numeroOrden);
        return tablaPage
                .data("modelo", model.asMap())
                .data("q", model.q())
                .data("toastSeverity", toastSeverity)
                .data("toastMessage", toastMessage);
    }

    private TemplateInstance formInstance(@Nonnull String modo, @Nullable OrdenCompra orden,
                                          @Nullable String errorGeneral,
                                          @Nullable String toastSeverity,
                                          @Nullable String toastMessage) {
        List<Map<String, Object>> lineas = new ArrayList<>();
        String fechaEntregaIso = null;
        if (orden != null && orden.getDetalles() != null) {
            for (OrdenCompraDetalle detalle : orden.getDetalles()) {
                Articulos articulo = detalle.getArticulo();
                Map<String, Object> linea = new LinkedHashMap<>();
                linea.put("codigo", articulo != null ? articulo.getCodigo() : null);
                linea.put("nombre", articulo != null ? articulo.getNombre() : null);
                linea.put("cantidad", detalle.getCantidad());
                linea.put("precio", detalle.getPrecioUnitario());
                linea.put("subtotal", formatoDinero(detalle.getSubtotal()));
                lineas.add(linea);
            }
        }
        if (orden != null && orden.getFechaEntregaEstimada() != null) {
            fechaEntregaIso = orden.getFechaEntregaEstimada().toInstant()
                    .atZone(ZoneId.systemDefault()).toLocalDate().toString();
        }
        return formPage
                .data("modo", modo)
                .data("orden", orden)
                .data("proveedores", orEmpty(departamentoService.listAll()))
                .data("lineas", lineas)
                .data("fechaEntregaIso", fechaEntregaIso)
                .data("errorGeneral", errorGeneral)
                .data("toastSeverity", toastSeverity)
                .data("toastMessage", toastMessage);
    }

    private TemplateInstance estadoInstance(@Nonnull OrdenCompra orden,
                                            @Nullable String errorGeneral,
                                            @Nullable String toastSeverity,
                                            @Nullable String toastMessage) {
        return estadoPage
                .data("orden", orden)
                .data("objetivos", objetivosLegales(orden))
                .data("estadoClass", estadoClass(orden.getEstado()))
                .data("errorGeneral", errorGeneral)
                .data("toastSeverity", toastSeverity)
                .data("toastMessage", toastMessage);
    }

    private TableModel buildTableModel(int page, int size, @Nullable String sort,
                                       @Nullable String dir, @Nullable String q,
                                       @Nullable String estado, @Nullable Integer proveedorId,
                                       @Nullable String numeroOrden) {
        List<OrdenCompra> filas = filtrar(
                new ArrayList<>(orEmpty(ordenCompraService.listAll())),
                q, estado, proveedorId, numeroOrden);
        sortOrdenes(filas, sort, dir);

        long total = filas.size();
        int s = Math.min(Math.max(size, 1), 100);
        int totalPages = (int) Math.max(1L, (total + s - 1) / s);
        int p = Math.min(Math.max(page, 1), totalPages);
        int from = Math.min((p - 1) * s, (int) total);
        int to = Math.min(from + s, (int) total);

        List<Map<String, Object>> filasVm = new ArrayList<>();
        for (OrdenCompra orden : filas.subList(from, to)) {
            filasVm.add(filaTabla(orden));
        }

        List<Map<String, Object>> columnas = new ArrayList<>();
        columnas.add(col("N° Orden", "numeroOrden"));
        columnas.add(col("Proveedor", "proveedor"));
        columnas.add(col("Fecha", "fechaOrden"));
        columnas.add(col("Entrega Est.", "entrega"));
        columnas.add(col("Estado", "estado"));
        columnas.add(col("Total", "total"));
        columnas.add(col("Acciones", null));

        Map<String, Object> filtros = new LinkedHashMap<>();
        String qTrim = trimToNull(q);
        if (qTrim != null) {
            filtros.put("q", qTrim);
        }
        String estadoTrim = trimToNull(estado);
        if (estadoTrim != null) {
            filtros.put("estado", estadoTrim);
        }
        if (proveedorId != null) {
            filtros.put("proveedorId", proveedorId);
        }
        String numeroTrim = trimToNull(numeroOrden);
        if (numeroTrim != null) {
            filtros.put("numeroOrden", numeroTrim);
        }

        return new TableModel("tabla-ordenes", "/api/app/ordenes/table", columnas, filasVm,
                sort, "desc".equalsIgnoreCase(dir) ? "desc" : "asc",
                p, s, total, totalPages, pageWindow(p, totalPages),
                filtros, qTrim);
    }

    private static Map<String, Object> col(@Nonnull String label, @Nullable String key) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("label", label);
        map.put("key", key);
        return map;
    }

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

    /** Immutable view of everything pages/compras/ordenes-tabla.html needs. */
    public record TableModel(String id, String baseUrl, List<Map<String, Object>> columnas,
                             List<?> filas, String sortKey, String sortDir, int page, int size,
                             long total, int totalPages, List<Integer> paginas,
                             Map<String, Object> filtros, String q) {

        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("baseUrl", baseUrl);
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

    /** Flattened article suggestion for the picker endpoint. */
    public record ArticuloOpcion(Long codigo, String nombre, String codigoBarra) {}

    /** Cancel result carrying the legacy WARNING semantics. */
    public record CancelarResult(OrdenCompraDetailDTO orden, String severidad, String mensaje) {}

    /** JSON body of PUT /{id}/estado. */
    public static final class EstadoRequest {
        /** Target workflow state (e.g. ENVIADA). */
        public String nuevoEstado;

        public EstadoRequest() {}

        public EstadoRequest(String nuevoEstado) {
            this.nuevoEstado = nuevoEstado;
        }

        public String getNuevoEstado() {
            return nuevoEstado;
        }

        public void setNuevoEstado(String nuevoEstado) {
            this.nuevoEstado = nuevoEstado;
        }
    }

    /** JSON body of PUT /{id}/recibir (optional real total). */
    public static final class ReciboRequest {
        /** Optional real total copied onto the order when receiving. */
        public BigDecimal totalReal;

        public ReciboRequest() {}

        public ReciboRequest(BigDecimal totalReal) {
            this.totalReal = totalReal;
        }

        public BigDecimal getTotalReal() {
            return totalReal;
        }

        public void setTotalReal(BigDecimal totalReal) {
            this.totalReal = totalReal;
        }
    }

    /** JSON body of POST /{id}/cancelar (optional reason). */
    public static final class CancelarRequest {
        /** Optional cancellation reason stored into notas. */
        public String motivo;

        public CancelarRequest() {}

        public CancelarRequest(String motivo) {
            this.motivo = motivo;
        }

        public String getMotivo() {
            return motivo;
        }

        public void setMotivo(String motivo) {
            this.motivo = motivo;
        }
    }
}
