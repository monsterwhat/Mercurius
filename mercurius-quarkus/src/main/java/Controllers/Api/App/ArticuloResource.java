package Controllers.Api.App;

import Models.Articulos.ArticuloPrecio;
import Models.Articulos.Articulos;
import Models.Articulos.Carrito.ArticuloCarrito;
import Models.Articulos.Promocion;
import Models.Cabys;
import Models.Departamento;
import Models.DTO.ApiResponse;
import Models.DTO.CabysDTO;
import Models.DTO.PagedResponse;
import Models.DTO.PromocionDTO;
import Models.Enums.Tipo_Codigo_Descuento;
import Models.Familia;
import Models.ProductoExoneracion;
import Models.Users;
import Services.AlertasService;
import Services.ArticuloCarritoService;
import Services.ArticuloPrecioService;
import Services.ArticulosService;
import Services.CabysService;
import Services.DepartamentoService;
import Services.FamiliaService;
import Services.InventarioService;
import Services.LoginService;
import Services.PromocionesService;
import Services.ProductoExoneracionService;
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
import java.text.ParseException;
import java.text.SimpleDateFormat;
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
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Artículos module for the NEW Qute/HTMX app surface (plan task T34): the
 * five-tab monster (Activos / Inactivos / Catálogo / Pendientes /
 * Promociones) replacing the legacy JSF trio
 * {@code Controllers.ArticulosController} (33 FC),
 * {@code Controllers.PromocionesController} (16 FC) and the precio-update
 * surface the plan calls "ArticuloPrecioController" (no such class exists in
 * the tree — its behavior lives in ArticulosController's price-calculation
 * methods + {@link ArticuloPrecioService}; folded here into the
 * supervisor-gated price endpoint, see {@link #updatePrecio}).
 *
 * <p><b>Behavior parity contract</b> (ported 1:1, receipts in
 * .omo/evidence/t34/parity-matrix.md):</p>
 * <ul>
 *   <li>Tab lists: {@code ListAllEnabled} / {@code listAllInactivos} /
 *       {@code listAllActivosYProcesados} / {@code listAllSinProcesar} /
 *       {@code PromocionesService.listAll}, with the legacy global filter
 *       ({@code globalFilterFunction}: codigo, nombre, codigoBarra,
 *       departamento, familia, usuario contains; promociones: nombre, id,
 *       username).</li>
 *   <li>Create ({@code createArticuloByDialog} parity): duplicate barcode →
 *       legacy warning "El codigo de barra ingresado ya existe." as 409;
 *       missing departamento/familia → "No se encontro seleccion para
 *       Departamentos o Familias"; sets status+processed true, seeds one
 *       {@link ArticuloPrecio} row, persists exoneración when exento.</li>
 *   <li>Edit ({@code updateArticuloByDialog} parity): requires
 *       departamento/familia ("No se seleccionó departamento o familia") and
 *       código CABYS ("No se seleccionó un código del CABYS"); refreshes
 *       usuario, processed=true, saveOrUpdateExoneracion semantics.</li>
 *   <li>Delete ({@code deleteArticulo} parity):
 *       {@link ArticulosService#softDelete} (deactivate only — no toggle
 *       back, preserved quirk) + audit alerta.</li>
 *   <li>Revision workflow ({@code updateArticuloRevision} /
 *       {@code updateArticulosRevision} / {@code skipCurrentArticle}
 *       parity): pendiente→procesado requires dep+fam, CABYS and a non-null
 *       precioFinal ("No hay precio final..."); skip writes the
 *       "Artículo omitido" alerta and answers "Se omitió el artículo".
 *       Processing moves counts between the Pendientes and Catálogo tabs.</li>
 *   <li>Price override: NEW-world supervisor gate per task spec — the
 *       dedicated price endpoint verifies a second credential pair exactly
 *       like {@code AppAuthResource.supervisorAuthorize()} (LoginService
 *       lookup + BCrypt verify + status check); without valid authorization
 *       it is rejected 401 SUPERVISOR_REQUIRED. Authorized calls recompute
 *       precioConUtilidad and precioFinal with the legacy math
 *       ({@code calcularPrecioConUtilidadEdit}/{@code calcularPrecioConIVAEdit}:
 *       HALF_UP utilidad factor at 4dp, CEILING to 0dp on both steps) and
 *       append a new {@link ArticuloPrecio} history row (newest = current).</li>
 *   <li>Promotions ({@code createPromocionByDialog} /
 *       {@code updatePromocionByDialog} / {@code deletePromocion} parity):
 *       empty items → "No hay artículos en la promoción"; missing dates →
 *       "No se seleccionaron fechas para la promoción"; null bounds →
 *       "Fechas de promoción incompletas"; transient carrito items without
 *       codigo are persisted via {@link ArticuloCarritoService#create};
 *       delete is a HARD delete (service quirk preserved). Date-range
 *       validation (fin ≥ inicio) is added per task spec on client AND
 *       server (400 DATE_RANGE_INVALID) — the legacy dialogs lacked it.</li>
 * </ul>
 *
 * <p><b>Paging/sorting contract</b> follows docs/ui-kit.md §3.1: {@code page}
 * 1-based (default 1), {@code size} default 20, {@code sort} from a
 * whitelist (null = service order), {@code dir} asc|desc. Filtering/sorting/
 * paging computed in memory because the Services layer must not be modified
 * by this task.</p>
 *
 * <p><b>Fragment dual-mode:</b> endpoints backing a UI surface check the
 * {@code HX-Request} header — fragment-only HTML when present, ApiResponse/
 * PagedResponse JSON otherwise (ui-kit.md §2.9).</p>
 *
 * <p><b>Authorization:</b> {@code admin} or {@code inventario} — the navbar
 * gates the legacy Articulos dropdown behind the inventario role token
 * (admin implies all roles), mirroring the module-entry check. Export
 * buttons stay gated by {@code registro} in the template, matching
 * {@code SessionController.registros}. The {@code /api/app/*} surface
 * additionally requires any authenticated user through the T13 policy.</p>
 */
@Path("/api/app/articulos")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"admin", "inventario"})
@Tag(name = "App - Artículos")
public class ArticuloResource {

    private static final Logger LOG = Logger.getLogger(ArticuloResource.class.getName());

    /** Tab keys of the five-tab page (legacy tab order preserved). */
    public static final String TAB_ACTIVOS = "activos";
    public static final String TAB_INACTIVOS = "inactivos";
    public static final String TAB_CATALOGO = "catalogo";
    public static final String TAB_PENDIENTES = "pendientes";
    public static final String TAB_PROMOCIONES = "promociones";

    /** Legacy required/warning message parity (JSF FacesMessages). */
    static final String MSG_BARRA_DUPLICADA = "El codigo de barra ingresado ya existe.";
    static final String MSG_SELECCION_REQUERIDA = "No se encontro seleccion para Departamentos o Familias";
    static final String MSG_DEP_FAM_REQUERIDO = "No se seleccionó departamento o familia";
    static final String MSG_CABYS_REQUERIDO = "No se seleccionó un código del CABYS";
    static final String MSG_CABYS_REQUERIDO_REV = "No se selecciono un codigo del CABYS";
    static final String MSG_SIN_PRECIO_FINAL = "No hay precio final";
    static final String MSG_SIN_PRECIO_FINAL_DETALLE =
            "se debe re-ajustar la utilidad y verificar que el codigo cabys sea correcto";
    static final String MSG_PROMO_SIN_ARTICULOS = "No hay artículos en la promoción";
    static final String MSG_PROMO_SIN_FECHAS = "No se seleccionaron fechas para la promoción";
    static final String MSG_PROMO_FECHAS_INCOMPLETAS = "Fechas de promoción incompletas";
    static final String MSG_PROMO_RANGO_INVALIDO =
            "La fecha de fin debe ser posterior o igual a la fecha de inicio";

    @Inject
    @Nonnull
    ArticulosService articulosService;

    @Inject
    @Nonnull
    ArticuloPrecioService precioService;

    @Inject
    @Nonnull
    DepartamentoService departamentoService;

    @Inject
    @Nonnull
    FamiliaService familiaService;

    @Inject
    @Nonnull
    InventarioService inventarioService;

    @Inject
    @Nonnull
    PromocionesService promoService;

    @Inject
    @Nonnull
    ArticuloCarritoService articuloCarritoService;

    @Inject
    @Nonnull
    ProductoExoneracionService productoExoneracionService;

    @Inject
    @Nonnull
    CabysService cabysService;

    @Inject
    @Nonnull
    AlertasService alertas;

    @Inject
    @Nonnull
    LoginService loginService;

    @Inject
    @Nonnull
    SecurityIdentity identity;

    /** Request context (quarkus-rest injectable) — source of HX-Request. */
    @Inject
    @Nonnull
    RoutingContext routing;

    // Templates (rendered to String: no quarkus-rest-qute MessageBodyWriter
    // on this stack — same approach as CategoriaResource, T18).
    @Inject
    @Nonnull
    @Location("pages/articulos/index.html")
    Template pageIndex;

    @Inject
    @Nonnull
    @Location("pages/articulos/tabla-articulos.html")
    Template tablaArticulos;

    @Inject
    @Nonnull
    @Location("pages/articulos/tabla-promociones.html")
    Template tablaPromociones;

    @Inject
    @Nonnull
    @Location("pages/articulos/form-articulo.html")
    Template formArticulo;

    @Inject
    @Nonnull
    @Location("pages/articulos/form-revision.html")
    Template formRevision;

    @Inject
    @Nonnull
    @Location("pages/articulos/form-promocion.html")
    Template formPromocion;

    @Inject
    @Nonnull
    @Location("pages/articulos/detalles.html")
    Template detallesArticulo;

    @Inject
    @Nonnull
    @Location("pages/articulos/buscador-cabys.html")
    Template buscadorCabys;

    @Inject
    @Nonnull
    @Location("pages/articulos/selector-articulos.html")
    Template selectorArticulos;

    // ════════════════════════════════════════════════════════════════════
    // Tab-filtered paginated list (kit contract)
    // ════════════════════════════════════════════════════════════════════

    /**
     * GET /api/app/articulos?tab=activos|inactivos|catalogo|pendientes|promociones
     * with the kit paging params. JSON {@link PagedResponse} by default;
     * with {@code HX-Request} renders ONLY that tab's data-table fragment.
     */
    @GET
    @Operation(summary = "Tab-filtered paginated article/promotion list (kit contract)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Paginated rows (or HTML fragment when HX-Request)"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Role not allowed"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response list(
            @QueryParam("tab") @DefaultValue(TAB_ACTIVOS) @Parameter(description = "Tab key") String tab,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("sort") @Nullable String sort,
            @QueryParam("dir") @DefaultValue("asc") String dir,
            @QueryParam("q") @Nullable String q) {
        try {
            String t = normalizeTab(tab);
            if (isHxRequest()) {
                return tableFragment(t, page, size, sort, dir, q, null, null);
            }
            if (TAB_PROMOCIONES.equals(t)) {
                List<Promocion> filtered = filterPromociones(orEmpty(promoService.listAll()), q);
                sortPromociones(filtered, sort, dir);
                long total = filtered.size();
                Window w = windowOf(total, page, size);
                List<PromocionDTO> data = filtered.subList(w.from(), w.to()).stream()
                        .map(ArticuloResource::toDTO).toList();
                return Response.ok(new PagedResponse<>(data, total, w.page(), w.size())).build();
            }
            List<Articulos> filtered = filterArticulos(articulosOfTab(t), q);
            sortArticulos(filtered, sort, dir);
            long total = filtered.size();
            Window w = windowOf(total, page, size);
            List<ArticuloListDTO> data = filtered.subList(w.from(), w.to()).stream()
                    .map(ArticuloResource::toListDTO).toList();
            return Response.ok(new PagedResponse<>(data, total, w.page(), w.size())).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error listando artículos", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error listando los artículos"))
                    .build();
        }
    }

    /**
     * GET /api/app/articulos/table?tab=… — fragment-or-full-page endpoint
     * backing the five-tab page (ui-kit §2.9): HX-Request swaps ONLY the
     * requested table include; otherwise the whole page renders through the
     * layout.
     */
    @GET
    @Path("/table")
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Data-table fragment (HX-Request) or full artículos page")
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
            if (isHxRequest()) {
                return tableFragment(normalizeTab(tab), page, size, sort, dir, q, null, null);
            }
            return htmlOk(renderFullPage());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error renderizando la página de artículos", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error renderizando la página"))
                    .build();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Detail
    // ════════════════════════════════════════════════════════════════════

    /**
     * GET /api/app/articulos/{codigo} — detail with price history and stock
     * (legacy verDetalles read model). HX-Request renders the read-only
     * detalles fragment instead of JSON.
     */
    @GET
    @Path("/{codigo}")
    @Operation(summary = "Article detail with price history and stock")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Detail (or HTML fragment when HX-Request)"),
        @APIResponse(responseCode = "404", description = "Unknown codigo"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response detail(@PathParam("codigo") long codigo) {
        try {
            Articulos articulo = articulosService.findById((int) codigo);
            if (articulo == null) {
                return notFound("No se encontró el artículo solicitado");
            }
            if (isHxRequest()) {
                return htmlOk(detallesArticulo
                        .data("articulo", articulo)
                        .data("stock", stockOf(articulo))
                        .data("precios", orEmpty(precioService.findAllByArticulo(articulo))));
            }
            return Response.ok(ApiResponse.ok(toDetailDTO(articulo))).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error leyendo el artículo " + codigo, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error leyendo el artículo"))
                    .build();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // CRUD (create/edit/delete parity)
    // ════════════════════════════════════════════════════════════════════

    /**
     * POST /api/app/articulos — legacy {@code createArticuloByDialog()}
     * parity. JSON body or urlencoded form twin (HTMX dialog).
     */
    @POST
    @Operation(summary = "Create an artículo (legacy createArticuloByDialog parity)")
    @APIResponses({
        @APIResponse(responseCode = "201", description = "Created"),
        @APIResponse(responseCode = "400", description = "Missing selection fields"),
        @APIResponse(responseCode = "409", description = "Duplicate barcode"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response create(@Nullable ArticuloForm body) {
        return doCreate(formOf(body));
    }

    /** Form-urlencoded twin of {@link #create} for the HTMX dialog forms. */
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Operation(summary = "Create an artículo from an HTMX form", hidden = true)
    public Response createForm(
            @FormParam("nombre") @Nullable String nombre,
            @FormParam("codigoBarra") @Nullable String codigoBarra,
            @FormParam("descripcion") @Nullable String descripcion,
            @FormParam("unidadMedida") @Nullable String unidadMedida,
            @FormParam("unidadMedidaComercial") @Nullable String unidadMedidaComercial,
            @FormParam("departamentoId") @Nullable String departamentoId,
            @FormParam("familiaId") @Nullable String familiaId,
            @FormParam("cabysCodigo") @Nullable String cabysCodigo,
            @FormParam("precioCostoSinIVA") @Nullable String precioCostoSinIVA,
            @FormParam("porcentajeUtilidad") @Nullable String porcentajeUtilidad,
            @FormParam("exento") @Nullable String exento,
            @FormParam("stockOptimo") @Nullable String stockOptimo,
            @FormParam("diasStockSeguridad") @Nullable String diasStockSeguridad) {
        ArticuloForm form = new ArticuloForm();
        form.nombre = nombre;
        form.codigoBarra = codigoBarra;
        form.descripcion = descripcion;
        form.unidadMedida = unidadMedida;
        form.unidadMedidaComercial = unidadMedidaComercial;
        form.departamentoId = parseIntOrNull(departamentoId);
        form.familiaId = parseIntOrNull(familiaId);
        form.cabysCodigo = emptyToNull(cabysCodigo);
        form.precioCostoSinIVA = parseDecimalOrNull(precioCostoSinIVA);
        form.porcentajeUtilidad = parseDecimalOrNull(porcentajeUtilidad);
        form.exento = "on".equalsIgnoreCase(exento) || "true".equalsIgnoreCase(exento);
        form.stockOptimo = parseIntOrNull(stockOptimo);
        form.diasStockSeguridad = parseIntOrNull(diasStockSeguridad);
        return doCreate(form);
    }

    private Response doCreate(@Nonnull ArticuloForm form) {
        try {
            // Legacy isValidArticulo() gate order: session → dep/fam selection
            // → duplicate barcode. The legacy `DepartamentoID != 0 ||
            // FamiliaID != 0` test is only an entry guard: the inner
            // `dep != null && fam != null` check makes BOTH selections
            // effectively required (one-sided input silently no-opped in
            // JSF); here it surfaces as the legacy selection warning.
            Departamento departamento =
                    form.departamentoId == null ? null : departamentoService.findById(form.departamentoId);
            Familia familia =
                    form.familiaId == null ? null : familiaService.findById(form.familiaId);
            if (departamento == null || familia == null) {
                return failureWith(TAB_ACTIVOS, "error", MSG_SELECCION_REQUERIDA, 400, "VALIDATION_ERROR");
            }
            if (form.codigoBarra != null && !form.codigoBarra.isBlank()
                    && articulosService.findByBarCode(form.codigoBarra.trim()) != null) {
                return failureWith(TAB_ACTIVOS, "warn", MSG_BARRA_DUPLICADA, 409, "DUPLICATE_BARCODE");
            }

            Articulos nuevo = new Articulos();
            nuevo.setNombre(trimOrNull(form.nombre));
            nuevo.setCodigoBarra(trimOrNull(form.codigoBarra));
            nuevo.setDescripcion(emptyToNull(form.descripcion));
            nuevo.setUnidadMedida(emptyToNull(form.unidadMedida));
            nuevo.setUnidadMedidaComercial(emptyToNull(form.unidadMedidaComercial));
            nuevo.setDepartamento(departamento);
            nuevo.setFamilia(familia);
            nuevo.setUsuario(currentUser());
            nuevo.setProcessed(true);  // legacy: newArticulo.setProcessed(true)
            nuevo.setStatus(true);     // legacy: newArticulo.setStatus(true)
            nuevo.setStockOptimo(form.stockOptimo);
            nuevo.setDiasStockSeguridad(form.diasStockSeguridad);
            nuevo.setEstadoAlertas(Boolean.TRUE);
            nuevo.setExento(form.exento);
            if (form.cabysCodigo != null) {
                nuevo.setCodigoCabys(findCabys(form.cabysCodigo));
            }

            // Legacy: seed one precio row so getLastPrecio() never NPEs.
            ArticuloPrecio precio = new ArticuloPrecio();
            precio.setArticulo(nuevo);
            precio.setPrecioCostoSinIVA(form.precioCostoSinIVA);
            precio.setPorcentajeUtilidad(form.porcentajeUtilidad);
            calcularPrecioConUtilidad(precio, form.cabysCodigo);
            List<ArticuloPrecio> precios = new ArrayList<>();
            precios.add(precio);
            nuevo.setPrecios(precios);

            articulosService.create(nuevo);

            // Legacy: persist exoneración only when exento.
            if (nuevo.isExento()) {
                ProductoExoneracion exoneracion = new ProductoExoneracion();
                exoneracion.setArticuloCodigo(String.valueOf(nuevo.getCodigo()));
                productoExoneracionService.save(exoneracion);
            }

            alertas.registrarAlerta("Artículo creado",
                    "Se ha creado el artículo: " + nuevo.getNombre(), currentUser(), 0,
                    "ArticuloResource.createArticulo", null, String.valueOf(nuevo.getCodigo()));

            if (isHxRequest()) {
                return hxRedirect("/api/app/articulos/table?tab=" + TAB_ACTIVOS);
            }
            return Response.status(Response.Status.CREATED)
                    .entity(ApiResponse.ok(toDetailDTO(nuevo))).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error creando el artículo", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error creando el artículo"))
                    .build();
        }
    }

    /**
     * PUT /api/app/articulos/{codigo} — legacy {@code updateArticuloByDialog()}
     * parity: dep/fam required, CABYS required, processed=true, usuario
     * refreshed, exoneración saved when exento.
     */
    @PUT
    @Path("/{codigo}")
    @Operation(summary = "Update an artículo (legacy updateArticuloByDialog parity)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Updated"),
        @APIResponse(responseCode = "400", description = "Missing departamento/familia/CABYS"),
        @APIResponse(responseCode = "404", description = "Unknown codigo"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response update(@PathParam("codigo") long codigo, @Nullable ArticuloForm body) {
        return doUpdate((int) codigo, formOf(body));
    }

    /** Form-urlencoded twin of {@link #update} for the HTMX dialogs. */
    @PUT
    @Path("/{codigo}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Operation(summary = "Update an artículo from an HTMX form", hidden = true)
    public Response updateForm(
            @PathParam("codigo") long codigo,
            @FormParam("nombre") @Nullable String nombre,
            @FormParam("codigoBarra") @Nullable String codigoBarra,
            @FormParam("descripcion") @Nullable String descripcion,
            @FormParam("unidadMedida") @Nullable String unidadMedida,
            @FormParam("unidadMedidaComercial") @Nullable String unidadMedidaComercial,
            @FormParam("departamentoId") @Nullable String departamentoId,
            @FormParam("familiaId") @Nullable String familiaId,
            @FormParam("cabysCodigo") @Nullable String cabysCodigo,
            @FormParam("precioCostoSinIVA") @Nullable String precioCostoSinIVA,
            @FormParam("porcentajeUtilidad") @Nullable String porcentajeUtilidad,
            @FormParam("exento") @Nullable String exento,
            @FormParam("stockOptimo") @Nullable String stockOptimo,
            @FormParam("diasStockSeguridad") @Nullable String diasStockSeguridad) {
        ArticuloForm form = new ArticuloForm();
        form.nombre = nombre;
        form.codigoBarra = codigoBarra;
        form.descripcion = descripcion;
        form.unidadMedida = unidadMedida;
        form.unidadMedidaComercial = unidadMedidaComercial;
        form.departamentoId = parseIntOrNull(departamentoId);
        form.familiaId = parseIntOrNull(familiaId);
        form.cabysCodigo = emptyToNull(cabysCodigo);
        form.precioCostoSinIVA = parseDecimalOrNull(precioCostoSinIVA);
        form.porcentajeUtilidad = parseDecimalOrNull(porcentajeUtilidad);
        form.exento = "on".equalsIgnoreCase(exento) || "true".equalsIgnoreCase(exento);
        form.stockOptimo = parseIntOrNull(stockOptimo);
        form.diasStockSeguridad = parseIntOrNull(diasStockSeguridad);
        return doUpdate((int) codigo, form);
    }

    private Response doUpdate(int codigo, @Nonnull ArticuloForm form) {
        try {
            Articulos articulo = articulosService.findById(codigo);
            if (articulo == null) {
                return notFound("No se encontró el artículo solicitado");
            }
            // Legacy gate order: dep/fam first, then CABYS. Both selections
            // must resolve (see doCreate — legacy inner AND-check).
            Departamento departamento =
                    form.departamentoId == null ? null : departamentoService.findById(form.departamentoId);
            Familia familia =
                    form.familiaId == null ? null : familiaService.findById(form.familiaId);
            if (departamento == null || familia == null) {
                return failureWith(TAB_ACTIVOS, "warn", MSG_DEP_FAM_REQUERIDO, 400, "VALIDATION_ERROR");
            }
            if (form.cabysCodigo == null || findCabys(form.cabysCodigo) == null) {
                return failureWith(TAB_ACTIVOS, "warn", MSG_CABYS_REQUERIDO, 400, "VALIDATION_ERROR");
            }

            String antes = DiffUtils.snapshotEntity(articulo);
            articulo.setNombre(trimOrNull(form.nombre));
            articulo.setCodigoBarra(trimOrNull(form.codigoBarra));
            articulo.setDescripcion(emptyToNull(form.descripcion));
            articulo.setUnidadMedida(emptyToNull(form.unidadMedida));
            articulo.setUnidadMedidaComercial(emptyToNull(form.unidadMedidaComercial));
            articulo.setDepartamento(departamento);
            articulo.setFamilia(familia);
            articulo.setCodigoCabys(findCabys(form.cabysCodigo));
            articulo.setUsuario(currentUser());
            articulo.setProcessed(true); // legacy quirk: edit forces processed=true

            if (form.stockOptimo != null) {
                articulo.setStockOptimo(form.stockOptimo);
            }
            if (form.diasStockSeguridad != null) {
                articulo.setDiasStockSeguridad(form.diasStockSeguridad);
            }
            articulo.setExento(form.exento);

            articulosService.update(articulo);

            // Legacy saveOrUpdateExoneracion(): keep the exoneración row in
            // sync when the article is exento (fields preserved from the
            // existing row; the new-world form captures none of them yet).
            if (articulo.isExento()) {
                ProductoExoneracion existing =
                        productoExoneracionService.findByArticuloCodigo(String.valueOf(articulo.getCodigo()));
                if (existing == null) {
                    ProductoExoneracion fresh = new ProductoExoneracion();
                    fresh.setArticuloCodigo(String.valueOf(articulo.getCodigo()));
                    productoExoneracionService.save(fresh);
                }
            }

            alertas.registrarAlerta("Artículo actualizado",
                    "Se ha actualizado el artículo: " + articulo.getNombre(), currentUser(), 0,
                    "ArticuloResource.updateArticulo", antes, DiffUtils.snapshotEntity(articulo));

            if (isHxRequest()) {
                return hxRedirect("/api/app/articulos/table?tab=" + TAB_ACTIVOS);
            }
            Articulos updated = articulosService.findById(codigo);
            if (updated == null) {
                return notFound("No se encontró el artículo solicitado");
            }
            return Response.ok(ApiResponse.ok(toDetailDTO(updated))).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error actualizando el artículo " + codigo, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error actualizando el artículo"))
                    .build();
        }
    }

    /**
     * DELETE /api/app/articulos/{codigo} — legacy {@code deleteArticulo()}
     * parity: soft-deactivate only (no toggle back — preserved quirk).
     */
    @DELETE
    @Path("/{codigo}")
    @Operation(summary = "Deactivate an artículo (legacy deleteArticulo soft-delete parity)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Deactivated (or refreshed fragment when HX-Request)"),
        @APIResponse(responseCode = "404", description = "Unknown codigo"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response delete(@PathParam("codigo") long codigo) {
        try {
            Articulos articulo = articulosService.findById((int) codigo);
            if (articulo == null) {
                return notFound("No se encontró el artículo solicitado");
            }
            String antes = DiffUtils.snapshotEntity(articulo);
            articulosService.softDelete(articulo);
            alertas.registrarAlerta("Artículo eliminado",
                    "Se ha eliminado el artículo: " + articulo.getNombre(), currentUser(), 0,
                    "ArticuloResource.deleteArticulo", antes, DiffUtils.snapshotEntity(articulo));
            if (isHxRequest()) {
                return tableFragment(TAB_INACTIVOS, 1, 20, null, "asc", null,
                        "warn", "Se desactivo el artículo");
            }
            return Response.ok(ApiResponse.ok(Map.of(
                    "resultado", "DEACTIVATED",
                    "mensaje", "Se desactivo el artículo"))).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error eliminando el artículo " + codigo, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error eliminando el artículo"))
                    .build();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Revision workflow (pendiente → procesado)
    // ════════════════════════════════════════════════════════════════════

    /**
     * GET /api/app/articulos/revision/siguiente — legacy
     * {@code procesadoRapido()}/{@code loadNextArticulo()} parity: loads the
     * first unprocessed article. HX-Request returns the revision wizard
     * body; JSON clients get {hasNext, articulo}.
     */
    @GET
    @Path("/revision/siguiente")
    @Operation(summary = "Load the next pending article for revision (procesadoRapido parity)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Revision payload (or wizard fragment when HX-Request)"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response revisionSiguiente() {
        try {
            List<Articulos> pendientes = orEmpty(articulosService.listAllSinProcesar());
            boolean hasNext = !pendientes.isEmpty();
            Articulos siguiente = hasNext ? pendientes.get(0) : null;
            if (isHxRequest()) {
                return htmlOk(revisionFragment(siguiente, null, null, !hasNext, true));
            }
            return Response.ok(ApiResponse.ok(new RevisionNextDTO(hasNext,
                    siguiente == null ? null : toDetailDTO(siguiente)))).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error cargando el siguiente pendiente", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error cargando el siguiente artículo"))
                    .build();
        }
    }

    /**
     * POST /api/app/articulos/{codigo}/revision — legacy
     * {@code updateArticuloRevision()} parity: validates dep/fam, CABYS and
     * precioFinal, then flips processed=true (pendiente→procesado).
     * {@code modo=rapido} continues the wizard with the next pending article
     * (updateArticulosRevision parity).
     */
    @POST
    @Path("/{codigo}/revision")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Operation(summary = "Process a pending article (revision-dialogs parity)", hidden = true)
    public Response revisar(
            @PathParam("codigo") long codigo,
            @FormParam("departamentoId") @Nullable String departamentoId,
            @FormParam("familiaId") @Nullable String familiaId,
            @FormParam("cabysCodigo") @Nullable String cabysCodigo,
            @FormParam("precioCostoSinIVA") @Nullable String precioCostoSinIVA,
            @FormParam("porcentajeUtilidad") @Nullable String porcentajeUtilidad,
            @FormParam("modo") @Nullable String modo) {
        try {
            Articulos articulo = articulosService.findById((int) codigo);
            if (articulo == null) {
                return notFound("No se encontró el artículo solicitado");
            }
            Integer depId = parseIntOrNull(departamentoId);
            Integer famId = parseIntOrNull(familiaId);
            // Legacy inner AND-check: BOTH selections must resolve (see doCreate).
            Departamento departamento = depId == null ? null : departamentoService.findById(depId);
            Familia familia = famId == null ? null : familiaService.findById(famId);
            if (departamento == null || familia == null) {
                return revisionFailure(articulo, "warn", MSG_DEP_FAM_REQUERIDO, modo);
            }
            Cabys cabys = cabysCodigo == null ? null : findCabys(cabysCodigo);
            if (cabys == null) {
                return revisionFailure(articulo, "warn", MSG_CABYS_REQUERIDO_REV, modo);
            }

            String antes = DiffUtils.snapshotEntity(articulo);
            articulo.setDepartamento(departamento);
            articulo.setFamilia(familia);
            articulo.setUsuario(currentUser());
            articulo.setCodigoCabys(cabys);

            // Legacy: recalculate the LAST precio row in place before the
            // processed flip; precioFinal must end up non-null.
            ArticuloPrecio precio = lastPrecioOf(articulo);
            if (precio == null) {
                precio = new ArticuloPrecio();
                precio.setArticulo(articulo);
                List<ArticuloPrecio> precios = articulo.getPrecios() == null
                        ? new ArrayList<>() : new ArrayList<>(articulo.getPrecios());
                precios.add(precio);
                articulo.setPrecios(precios);
            }
            precio.setPrecioCostoSinIVA(parseDecimalOrNull(precioCostoSinIVA) != null
                    ? parseDecimalOrNull(precioCostoSinIVA) : precio.getPrecioCostoSinIVA());
            precio.setPorcentajeUtilidad(parseDecimalOrNull(porcentajeUtilidad) != null
                    ? parseDecimalOrNull(porcentajeUtilidad) : precio.getPorcentajeUtilidad());
            calcularPrecioConUtilidad(precio, cabysCodigo);
            if (precio.getPrecioFinal() == null) {
                return revisionFailure(articulo, "warn", MSG_SIN_PRECIO_FINAL, modo);
            }

            articulo.setProcessed(true);
            articulosService.update(articulo);
            alertas.registrarAlerta("Artículo actualizado",
                    "Se ha actualizado el artículo: " + articulo.getNombre(), currentUser(), 0,
                    "ArticuloResource.updateArticuloRevision", antes, DiffUtils.snapshotEntity(articulo));

            boolean rapido = "rapido".equals(modo);
            if (isHxRequest()) {
                if (rapido) {
                    // Wizard continuation: load the NEXT pending article
                    // (loadNextArticulo parity) and re-render the wizard body.
                    List<Articulos> pendientes = orEmpty(articulosService.listAllSinProcesar());
                    Articulos siguiente = pendientes.isEmpty() ? null : pendientes.get(0);
                    return htmlOk(revisionFragment(siguiente, "success",
                            "Se proceso el articulo", siguiente == null, true));
                }
                return hxRedirect("/api/app/articulos/table?tab=" + TAB_CATALOGO);
            }
            RevisionResultDTO result = new RevisionResultDTO(true,
                    rapido ? orEmpty(articulosService.listAllSinProcesar()).size() : 0,
                    "Se proceso el articulo");
            return Response.ok(ApiResponse.ok(result)).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error procesando la revisión del artículo " + codigo, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error procesando la revisión"))
                    .build();
        }
    }

    /**
     * POST /api/app/articulos/{codigo}/revision/saltar — legacy
     * {@code skipCurrentArticle()} parity: audit alerta + move to the next
     * pending article without processing.
     */
    @POST
    @Path("/{codigo}/revision/saltar")
    @Operation(summary = "Skip the pending article (skipCurrentArticle parity)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Skipped (or next wizard fragment when HX-Request)"),
        @APIResponse(responseCode = "404", description = "Unknown codigo"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response saltar(@PathParam("codigo") long codigo) {
        try {
            Articulos articulo = articulosService.findById((int) codigo);
            if (articulo == null) {
                return notFound("No se encontró el artículo solicitado");
            }
            String antes = DiffUtils.snapshotEntity(articulo);
            alertas.registrarAlerta("Artículo omitido",
                    "Se ha omitido el artículo: " + articulo.getNombre(), currentUser(), 0,
                    "ArticuloResource.skipCurrentArticle()", antes, DiffUtils.snapshotEntity(articulo));

            List<Articulos> pendientes = orEmpty(articulosService.listAllSinProcesar());
            Articulos siguiente = pendientes.isEmpty() ? null : pendientes.get(0);
            if (isHxRequest()) {
                return htmlOk(revisionFragment(siguiente, "info", "Se omitió el artículo",
                        siguiente == null, true));
            }
            return Response.ok(ApiResponse.ok(new RevisionNextDTO(siguiente != null,
                    siguiente == null ? null : toDetailDTO(siguiente)))).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error saltando el artículo " + codigo, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error saltando el artículo"))
                    .build();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Price override (supervisor-gated; folds the plan's
    // "ArticuloPrecioController" surface)
    // ════════════════════════════════════════════════════════════════════

    /**
     * POST /api/app/articulos/{codigo}/precio — dedicated price override.
     * Requires a second supervisor credential pair (same verification as
     * {@code AppAuthResource.supervisorAuthorize}: LoginService lookup +
     * BCrypt verify + enabled check). On success appends a NEW
     * {@link ArticuloPrecio} history row (newest = current) recomputed with
     * the legacy math.
     */
    @POST
    @Path("/{codigo}/precio")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Operation(summary = "Override the article price (requires supervisor re-authorization)", hidden = true)
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Price history row appended"),
        @APIResponse(responseCode = "401", description = "Missing/invalid supervisor credentials"),
        @APIResponse(responseCode = "404", description = "Unknown codigo"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response updatePrecio(
            @PathParam("codigo") long codigo,
            @FormParam("supervisorUsername") @Nullable String supervisorUsername,
            @FormParam("supervisorPassword") @Nullable String supervisorPassword,
            @FormParam("precioCostoSinIVA") @Nullable String precioCostoSinIVA,
            @FormParam("porcentajeUtilidad") @Nullable String porcentajeUtilidad) {
        try {
            // Supervisor gate FIRST — no price mutation without it.
            if (!isSupervisorAuthorized(supervisorUsername, supervisorPassword)) {
                return Response.status(Response.Status.UNAUTHORIZED)
                        .entity(ApiResponse.error("SUPERVISOR_REQUIRED",
                                "Se requiere autorización de supervisor para cambiar precios"))
                        .build();
            }

            Articulos articulo = articulosService.findById((int) codigo);
            if (articulo == null) {
                return notFound("No se encontró el artículo solicitado");
            }

            BigDecimal costo = parseDecimalOrNull(precioCostoSinIVA);
            BigDecimal utilidad = parseDecimalOrNull(porcentajeUtilidad);
            if (costo == null || utilidad == null) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(ApiResponse.error("VALIDATION_ERROR",
                                "El precio costo y el porcentaje de utilidad son obligatorios"))
                        .build();
            }

            String antes = DiffUtils.snapshotEntity(articulo);
            ArticuloPrecio nuevo = new ArticuloPrecio();
            nuevo.setArticulo(articulo);
            nuevo.setPrecioCostoSinIVA(costo);
            nuevo.setPorcentajeUtilidad(utilidad);
            calcularPrecioConUtilidad(nuevo, articulo.getCodigoCabys() == null
                    ? null : articulo.getCodigoCabys().getCodigo());

            List<ArticuloPrecio> precios = articulo.getPrecios() == null
                    ? new ArrayList<>() : new ArrayList<>(articulo.getPrecios());
            precios.add(nuevo);
            articulo.setPrecios(precios);
            articulosService.update(articulo);

            alertas.registrarAlerta("Precio actualizado",
                    "Se actualizó el precio del artículo: " + articulo.getNombre(),
                    currentUser(), 0, "ArticuloResource.updatePrecio()", antes,
                    DiffUtils.snapshotEntity(articulo));

            if (isHxRequest()) {
                return hxRedirect("/api/app/articulos/table?tab=" + TAB_CATALOGO);
            }
            return Response.ok(ApiResponse.ok(toDetailDTO(articulo))).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error actualizando el precio del artículo " + codigo, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error actualizando el precio"))
                    .build();
        }
    }

    /**
     * Supervisor re-authorization with the exact AppAuthResource semantics:
     * lookup + BCrypt verify + disabled-user check, audit-alerting failures.
     */
    private boolean isSupervisorAuthorized(@Nullable String username, @Nullable String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return false;
        }
        Users authUser = loginService.findByUsername(username.trim());
        if (authUser == null) {
            alertas.registrarAlerta("Autorización Fallida",
                    "Intento con usuario inexistente: " + username, null, 0,
                    "ArticuloResource.isSupervisorAuthorized()", null, null);
            return false;
        }
        if (!Boolean.TRUE.equals(authUser.getStatus())) {
            alertas.registrarAlerta("Autorización Fallida",
                    "Intento con usuario deshabilitado: " + username, null, 0,
                    "ArticuloResource.isSupervisorAuthorized()", null, null);
            return false;
        }
        if (!loginService.verifyPassword(password, authUser.getPassword())) {
            alertas.registrarAlerta("Autorización Fallida",
                    "Contraseña incorrecta de: " + username, null, 0,
                    "ArticuloResource.isSupervisorAuthorized()", null, null);
            return false;
        }
        return true;
    }

    // ════════════════════════════════════════════════════════════════════
    // CAByS picker (reuses CabysService.searchByName)
    // ════════════════════════════════════════════════════════════════════

    /**
     * GET /api/app/articulos/cabys?q= — search-as-you-type picker over
     * {@link CabysService#searchByName} (10-result cap preserved). HX-Request
     * renders the suggestion buttons fragment.
     */
    @GET
    @Path("/cabys")
    @Operation(summary = "CAByS picker search (CabysService.searchByName reuse)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Suggestions (or HTML fragment when HX-Request)"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response buscarCabys(@QueryParam("q") @Nullable String q,
                                @QueryParam("pickerId") @Nullable String pickerId) {
        try {
            List<Cabys> resultados = (q == null || q.isBlank())
                    ? Collections.emptyList()
                    : orEmpty(cabysService.searchByName(q.trim()));
            if (isHxRequest()) {
                return htmlOk(buscadorCabys
                        .data("resultados", resultados)
                        .data("pickerId", emptyToNull(pickerId)));
            }
            List<CabysDTO> data = resultados.stream().map(ArticuloResource::toDTO).toList();
            return Response.ok(ApiResponse.ok(data)).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error buscando en CAByS", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error buscando en CAByS"))
                    .build();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Promotions CRUD (folds PromocionesController)
    // ════════════════════════════════════════════════════════════════════

    /**
     * GET /api/app/articulos/promociones/{id} — detail incl. flattened
     * articulosCarrito rows.
     */
    @GET
    @Path("/promociones/{id}")
    @Operation(summary = "Promotion detail with flattened items")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Promotion detail"),
        @APIResponse(responseCode = "404", description = "Unknown id"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response promoDetail(@PathParam("id") int id) {
        try {
            Promocion promo = promoService.findById(id);
            if (promo == null) {
                return notFound("No se encontró la promoción solicitada");
            }
            return Response.ok(ApiResponse.ok(toDTO(promo))).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error leyendo la promoción " + id, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error leyendo la promoción"))
                    .build();
        }
    }

    /**
     * POST /api/app/articulos/promociones — legacy
     * {@code createPromocionByDialog()} parity plus the task-mandated
     * date-range validation (fin ≥ inicio → else 400 DATE_RANGE_INVALID).
     */
    @POST
    @Path("/promociones")
    @Operation(summary = "Create a promotion (createPromocionByDialog parity + date-range validation)")
    @APIResponses({
        @APIResponse(responseCode = "201", description = "Created"),
        @APIResponse(responseCode = "400", description = "Validation failure (items/dates/range)"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response createPromocion(@Nullable PromocionForm body) {
        return doSavePromocion(null, body == null ? new PromocionForm() : body);
    }

    /** Form-urlencoded twin of {@link #createPromocion} for the HTMX editor. */
    @POST
    @Path("/promociones")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Operation(summary = "Create a promotion from an HTMX form", hidden = true)
    public Response createPromocionForm(
            @FormParam("nombre") @Nullable String nombre,
            @FormParam("descuento") @Nullable String descuento,
            @FormParam("cantidad") @Nullable String cantidad,
            @FormParam("codigoDescuento") @Nullable String codigoDescuento,
            @FormParam("ensambladoOrigen") @Nullable String ensambladoOrigen,
            @FormParam("fechaInicio") @Nullable String fechaInicio,
            @FormParam("fechaFin") @Nullable String fechaFin,
            @FormParam("itemCodigo") @Nullable List<String> itemCodigos,
            @FormParam("itemCantidad") @Nullable List<String> itemCantidades) {
        PromocionForm form = promoFormOf(nombre, descuento, cantidad, codigoDescuento,
                ensambladoOrigen, fechaInicio, fechaFin, itemCodigos, itemCantidades);
        return doSavePromocion(null, form);
    }

    /**
     * PUT /api/app/articulos/promociones/{id} — legacy
     * {@code updatePromocionByDialog()} parity (+ date-range validation).
     */
    @PUT
    @Path("/promociones/{id}")
    @Operation(summary = "Update a promotion (updatePromocionByDialog parity + date-range validation)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Updated"),
        @APIResponse(responseCode = "400", description = "Validation failure (items/dates/range)"),
        @APIResponse(responseCode = "404", description = "Unknown id"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response updatePromocion(@PathParam("id") int id, @Nullable PromocionForm body) {
        return doSavePromocion(id, body == null ? new PromocionForm() : body);
    }

    /** Form-urlencoded twin of {@link #updatePromocion} for the HTMX editor. */
    @PUT
    @Path("/promociones/{id}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Operation(summary = "Update a promotion from an HTMX form", hidden = true)
    public Response updatePromocionForm(
            @PathParam("id") int id,
            @FormParam("nombre") @Nullable String nombre,
            @FormParam("descuento") @Nullable String descuento,
            @FormParam("cantidad") @Nullable String cantidad,
            @FormParam("codigoDescuento") @Nullable String codigoDescuento,
            @FormParam("ensambladoOrigen") @Nullable String ensambladoOrigen,
            @FormParam("fechaInicio") @Nullable String fechaInicio,
            @FormParam("fechaFin") @Nullable String fechaFin,
            @FormParam("itemCodigo") @Nullable List<String> itemCodigos,
            @FormParam("itemCantidad") @Nullable List<String> itemCantidades) {
        PromocionForm form = promoFormOf(nombre, descuento, cantidad, codigoDescuento,
                ensambladoOrigen, fechaInicio, fechaFin, itemCodigos, itemCantidades);
        return doSavePromocion(id, form);
    }

    /**
     * DELETE /api/app/articulos/promociones/{id} — legacy
     * {@code deletePromocion()} parity: HARD delete via
     * {@link PromocionesService#delete} (preserved service quirk) + audit.
     */
    @DELETE
    @Path("/promociones/{id}")
    @Operation(summary = "Delete a promotion (deletePromocion hard-delete parity)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Deleted (or refreshed fragment when HX-Request)"),
        @APIResponse(responseCode = "404", description = "Unknown id"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response deletePromocion(@PathParam("id") int id) {
        try {
            Promocion promo = promoService.findById(id);
            if (promo == null) {
                return notFound("No se encontró la promoción solicitada");
            }
            String antes = DiffUtils.snapshotEntity(promo);
            promoService.delete(promo);
            alertas.registrarAlerta("Promocion Eliminada",
                    "Se elimino la promocion: " + promo.getNombre(), currentUser(), 0,
                    "ArticuloResource.deletePromocion()", antes, null);
            if (isHxRequest()) {
                return tableFragment(TAB_PROMOCIONES, 1, 20, null, "asc", null,
                        "warn", "Se elimino la promocion");
            }
            return Response.ok(ApiResponse.ok(Map.of("mensaje", "Se elimino la promocion"))).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error eliminando la promoción " + id, e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error eliminando la promoción"))
                    .build();
        }
    }

    /** Shared create/update branch (dialog-parity validation order). */
    private Response doSavePromocion(@Nullable Integer id, @Nonnull PromocionForm form) {
        try {
            Promocion promo = id == null ? new Promocion() : promoService.findById(id);
            if (id != null && promo == null) {
                return notFound("No se encontró la promoción solicitada");
            }

            // Legacy validation order: items → dates present → bounds non-null.
            List<ArticuloCarrito> items = buildItems(form.items);
            if (items.isEmpty()) {
                return promoFailure(MSG_PROMO_SIN_ARTICULOS, "warn");
            }
            if (form.fechaInicio == null && form.fechaFin == null) {
                return promoFailure(MSG_PROMO_SIN_FECHAS, "warn");
            }
            if (form.fechaInicio == null || form.fechaFin == null) {
                return promoFailure(MSG_PROMO_FECHAS_INCOMPLETAS, "warn");
            }
            // Task-mandated hardening (client + server): fin must not precede inicio.
            if (form.fechaFin.before(form.fechaInicio)) {
                return promoFailure(MSG_PROMO_RANGO_INVALIDO, "error");
            }

            String antes = id == null ? null : DiffUtils.snapshotEntity(promo);
            promo.setNombre(trimOrNull(form.nombre));
            promo.setDescuento(form.descuento);
            promo.setCantidad(form.cantidad);
            promo.setCodigoDescuento(emptyToNull(form.codigoDescuento) == null
                    ? "06" : form.codigoDescuento.trim()); // legacy default Nota 20
            promo.setEnsambladoOrigen(form.ensambladoOrigen);
            promo.setFechaInicio(form.fechaInicio);
            promo.setFechaFin(form.fechaFin);
            promo.setActiva(true); // legacy: setActiva(true) on both paths
            promo.setUsuario(currentUser());

            // Legacy: persist transient carrito items (no codigo yet) and link
            // both sides of the ManyToMany.
            for (ArticuloCarrito item : items) {
                if (item.getCodigo() == null) {
                    articuloCarritoService.create(item);
                }
                if (item.getPromociones() == null) {
                    item.setPromociones(new ArrayList<>());
                }
                if (!item.getPromociones().contains(promo)) {
                    item.getPromociones().add(promo);
                }
            }
            promo.setArticulosCarrito(items);

            if (id == null) {
                promoService.create(promo);
                alertas.registrarAlerta("Promoción Creada",
                        "Se creó la promoción: " + promo.getNombre(), currentUser(), 0,
                        "ArticuloResource.createPromocionByDialog()", null,
                        String.valueOf(promo.getId()));
            } else {
                promoService.update(promo);
                alertas.registrarAlerta("Promoción Actualizada",
                        "Se actualizó la promoción: " + promo.getNombre(), currentUser(), 0,
                        "ArticuloResource.updatePromocionByDialog()", antes,
                        DiffUtils.snapshotEntity(promo));
            }

            if (isHxRequest()) {
                return hxRedirect("/api/app/articulos/table?tab=" + TAB_PROMOCIONES);
            }
            return Response.status(id == null
                            ? Response.Status.CREATED : Response.Status.OK)
                    .entity(ApiResponse.ok(toDTO(promo))).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error guardando la promoción", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error guardando la promoción"))
                    .build();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Modal-body form endpoints (hx-get targets of _kit/modal)
    // ════════════════════════════════════════════════════════════════════

    /** Empty artículo creation form (modal body). */
    @GET
    @Path("/formularios/articulos/nueva")
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "New-article form fragment (modal body)")
    public Response formNuevaArticulo() {
        return htmlOk(articuloFormFragment(null, "crear", null, null, null));
    }

    /** Prefilled artículo edit form (modal body). */
    @GET
    @Path("/formularios/articulos/{codigo}")
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Edit-article form fragment (modal body)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Form HTML"),
        @APIResponse(responseCode = "404", description = "Unknown codigo")
    })
    public Response formEditarArticulo(@PathParam("codigo") long codigo) {
        Articulos articulo = articulosService.findById((int) codigo);
        if (articulo == null) {
            return notFound("No se encontró el artículo solicitado");
        }
        return htmlOk(articuloFormFragment(articulo, "editar", null, null, null));
    }

    /** Single-article revision form (modal body; DialogoRevisionArticulo port). */
    @GET
    @Path("/formularios/revision/{codigo}")
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Revision form fragment for one pending article (modal body)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Form HTML"),
        @APIResponse(responseCode = "404", description = "Unknown codigo")
    })
    public Response formRevisionArticulo(@PathParam("codigo") long codigo) {
        Articulos articulo = articulosService.findById((int) codigo);
        if (articulo == null) {
            return notFound("No se encontró el artículo solicitado");
        }
        return htmlOk(revisionFragment(articulo, null, null, false, false));
    }

    /** Empty promotion editor (modal body). */
    @GET
    @Path("/formularios/promociones/nueva")
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "New-promotion editor fragment (modal body)")
    public Response formNuevaPromocion() {
        return htmlOk(promoEditorFragment(null, Collections.emptyList(), null, null, null));
    }

    /** Prefilled promotion editor (modal body). */
    @GET
    @Path("/formularios/promociones/{id}")
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Edit-promotion editor fragment (modal body)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Form HTML"),
        @APIResponse(responseCode = "404", description = "Unknown id")
    })
    public Response formEditarPromocion(@PathParam("id") int id) {
        Promocion promo = promoService.findById(id);
        if (promo == null) {
            return notFound("No se encontró la promoción solicitada");
        }
        return htmlOk(promoEditorFragment(promo,
                orEmpty(promo.getArticulosCarrito()), null, null, null));
    }

    /**
     * Article selector for the promotion editor (legacy ArticuloRevisionDialog
     * port): searchable list of activos-y-procesados with pick buttons.
     */
    @GET
    @Path("/formularios/promociones/articulos")
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Article picker fragment for the promotion editor")
    public Response formSelectorArticulos(@QueryParam("q") @Nullable String q) {
        try {
            List<Articulos> filtered =
                    filterArticulos(orEmpty(articulosService.listAllActivosYProcesados()), q);
            return htmlOk(selectorArticulos
                    .data("resultados", filtered.subList(0, Math.min(filtered.size(), 10)))
                    .data("q", q));
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error renderizando el selector de artículos", e);
            return Response.serverError()
                    .entity(ApiResponse.error("INTERNAL_ERROR", "Error renderizando el selector"))
                    .build();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Request classification & shared responses
    // ════════════════════════════════════════════════════════════════════

    /** True when the call comes from HTMX (layout hx-headers carries it). */
    private boolean isHxRequest() {
        String header = routing.request().getHeader("HX-Request");
        return header != null && !"false".equalsIgnoreCase(header);
    }

    private static String normalizeTab(@Nullable String tab) {
        if (tab == null) {
            return TAB_ACTIVOS;
        }
        return switch (tab.toLowerCase(Locale.ROOT)) {
            case TAB_INACTIVOS -> TAB_INACTIVOS;
            case TAB_CATALOGO, "procesados" -> TAB_CATALOGO;
            case TAB_PENDIENTES -> TAB_PENDIENTES;
            case TAB_PROMOCIONES -> TAB_PROMOCIONES;
            default -> TAB_ACTIVOS;
        };
    }

    private Response notFound(@Nonnull String mensaje) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(ApiResponse.error("NOT_FOUND", mensaje)).build();
    }

    private static Response htmlOk(@Nonnull TemplateInstance template) {
        return Response.ok(template.render())
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).build();
    }

    /**
     * Failure branch shared by the mutating article endpoints: HTMX callers
     * get the refreshed table fragment + OOB toast (ui-kit Pattern A); API
     * callers get the structured envelope with the legacy message.
     */
    private Response failureWith(@Nonnull String tab, @Nonnull String severity,
                                 @Nonnull String mensaje, int status, @Nonnull String code) {
        if (isHxRequest()) {
            return tableFragment(tab, 1, 20, null, "asc", null, severity, mensaje);
        }
        return Response.status(status)
                .entity(ApiResponse.error(code, mensaje)).build();
    }

    /** Revision-flow failure: wizard mode re-renders the wizard with the toast. */
    private Response revisionFailure(@Nonnull Articulos articulo, @Nonnull String severity,
                                     @Nonnull String mensaje, @Nullable String modo) {
        if (isHxRequest()) {
            // Both modes keep the same article selected and redisplay the
            // wizard body with the legacy warning as an out-of-band toast.
            boolean rapido = "rapido".equals(modo);
            return htmlOk(revisionFragment(articulo, severity, mensaje, false, rapido));
        }
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(ApiResponse.error("VALIDATION_ERROR", mensaje)).build();
    }

    /** Promotion-flow failure: HTMX gets the refreshed promotions table + toast. */
    private Response promoFailure(@Nonnull String mensaje, @Nonnull String severity) {
        if (isHxRequest()) {
            return tableFragment(TAB_PROMOCIONES, 1, 20, null, "asc", null, severity, mensaje);
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

    /** Full-page model: five tables + stat counters (legacy stat cards). */
    private TemplateInstance renderFullPage() {
        TableModel activos = buildTableModel(TAB_ACTIVOS, 1, 10, null, "asc", null);
        TableModel inactivos = buildTableModel(TAB_INACTIVOS, 1, 10, null, "asc", null);
        TableModel catalogo = buildTableModel(TAB_CATALOGO, 1, 10, null, "asc", null);
        TableModel pendientes = buildTableModel(TAB_PENDIENTES, 1, 10, null, "asc", null);
        TableModel promociones = buildTableModel(TAB_PROMOCIONES, 1, 10, null, "asc", null);
        return pageIndex
                .data("activosTabla", activos.asMap())
                .data("inactivosTabla", inactivos.asMap())
                .data("catalogoTabla", catalogo.asMap())
                .data("pendientesTabla", pendientes.asMap())
                .data("promocionesTabla", promociones.asMap())
                .data("activosCount", articulosService.countActivos())
                .data("inactivosCount", articulosService.countInactivos())
                .data("catalogoCount", articulosService.count())
                .data("pendientesCount", articulosService.countPendientes())
                .data("promocionesCount", promoService.count())
                .data("canExport", !identity.isAnonymous() && identity.hasRole("registro"));
    }

    /**
     * Renders ONLY one tab's data-table include (the fragment swap target).
     * Model keys mirror the _kit/data-table DATA CONTRACT verbatim.
     */
    private Response tableFragment(@Nonnull String tab, int page, int size,
                                   @Nullable String sort, @Nullable String dir,
                                   @Nullable String q,
                                   @Nullable String toastSeverity, @Nullable String toastMessage) {
        TableModel model = buildTableModel(tab, page, size, sort, dir, q);
        Template template = TAB_PROMOCIONES.equals(tab) ? tablaPromociones : tablaArticulos;
        return htmlOk(template
                .data("modelo", model.asMap())
                .data("q", model.q())
                .data("toastSeverity", toastSeverity)
                .data("toastMessage", toastMessage));
    }

    /** Immutable view of everything one tabla include needs. */
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
        boolean promociones = TAB_PROMOCIONES.equals(tab);
        List<?> filtered;
        if (promociones) {
            List<Promocion> source = filterPromociones(orEmpty(promoService.listAll()), q);
            sortPromociones(source, sort, dir);
            filtered = source;
        } else {
            List<Articulos> source = filterArticulos(articulosOfTab(tab), q);
            sortArticulos(source, sort, dir);
            filtered = source;
        }

        long total = filtered.size();
        Window w = windowOf(total, page, size);
        List<?> filas = filtered.subList(w.from(), w.to());

        // Column definitions mirror the legacy p:column sets per tab; null
        // key ⇒ non-sortable (docs/ui-kit.md §3.1).
        List<Map<String, Object>> columnas = new ArrayList<>();
        if (promociones) {
            columnas.add(col("Nombre", "nombre"));
            columnas.add(col("Artículos", null));
            columnas.add(col("Fecha Inicio", "fechaInicio"));
            columnas.add(col("Fecha Fin", "fechaFin"));
            columnas.add(col("Descuento", "descuento"));
            columnas.add(col("Estado", null));
            columnas.add(col("Acciones", null));
        } else {
            columnas.add(col("Estado", null));
            columnas.add(col("Código Barra", "codigoBarra"));
            if (TAB_ACTIVOS.equals(tab)) {
                columnas.add(col("Código Cabys", "cabys"));
            }
            columnas.add(col("Nombre", "nombre"));
            if (TAB_CATALOGO.equals(tab)) {
                columnas.add(col("Stock", null));
                columnas.add(col("Precio", "precio"));
            }
            columnas.add(col("Acciones", null));
        }

        Map<String, Object> filtros = new LinkedHashMap<>();
        filtros.put("tab", tab);
        if (q != null && !q.isBlank()) {
            filtros.put("q", q);
        }

        return new TableModel(
                "tabla-" + tab,
                "/api/app/articulos/table",
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

    /** Revision wizard body model (single + rapid modes). */
    private TemplateInstance revisionFragment(@Nullable Articulos articulo,
                                              @Nullable String toastSeverity,
                                              @Nullable String toastMessage,
                                              boolean sinPendientes,
                                              boolean modoRapido) {
        return formRevision
                .data("articulo", articulo)
                .data("sinPendientes", sinPendientes || articulo == null)
                .data("modoRapido", modoRapido)
                .data("departamentos", orEmpty(departamentoService.listAll()))
                .data("familias", orEmpty(familiaService.listAll()))
                .data("precio", articulo == null ? null : lastPrecioOf(articulo))
                .data("toastSeverity", toastSeverity)
                .data("toastMessage", toastMessage);
    }

    /** Article create/edit modal body model. */
    private TemplateInstance articuloFormFragment(@Nullable Articulos articulo,
                                                  @Nonnull String modo,
                                                  @Nullable String errorMensaje,
                                                  @Nullable String toastSeverity,
                                                  @Nullable String toastMessage) {
        return formArticulo
                .data("modo", modo)
                .data("articulo", articulo)
                .data("precio", articulo == null ? null : lastPrecioOf(articulo))
                .data("departamentos", orEmpty(departamentoService.listAll()))
                .data("familias", orEmpty(familiaService.listAll()))
                .data("errorMensaje", errorMensaje)
                .data("toastSeverity", toastSeverity)
                .data("toastMessage", toastMessage);
    }

    /** Promotion editor modal body model (totals precomputed server-side). */
    private TemplateInstance promoEditorFragment(@Nullable Promocion promo,
                                                 @Nonnull List<ArticuloCarrito> items,
                                                 @Nullable String errorMensaje,
                                                 @Nullable String toastSeverity,
                                                 @Nullable String toastMessage) {
        BigDecimal totalConIVA = BigDecimal.ZERO;
        BigDecimal totalConUtilidad = BigDecimal.ZERO;
        for (ArticuloCarrito item : items) {
            Articulos articulo = item.getArticulo();
            if (articulo != null && item.getCantidad() != null) {
                if (articulo.getLastPrecio() != null) {
                    if (articulo.getLastPrecio().getPrecioFinal() != null) {
                        totalConIVA = totalConIVA.add(articulo.getLastPrecio().getPrecioFinal()
                                .multiply(item.getCantidad()));
                    }
                    if (articulo.getLastPrecio().getPrecioConUtilidad() != null) {
                        totalConUtilidad = totalConUtilidad.add(articulo.getLastPrecio()
                                .getPrecioConUtilidad().multiply(item.getCantidad()));
                    }
                }
            }
        }
        BigDecimal descuento = promo == null ? null : promo.getDescuento();
        return formPromocion
                .data("modo", promo == null ? "crear" : "editar")
                .data("promocion", promo)
                .data("items", items)
                .data("tiposDescuento", Tipo_Codigo_Descuento.values())
                .data("totalConIVA", totalConIVA)
                .data("totalConUtilidad", totalConUtilidad)
                .data("totalDescuentoEIVA", promo == null ? BigDecimal.ZERO
                        : promo.getTotalPromo(items, descuento))
                .data("errorMensaje", errorMensaje)
                .data("toastSeverity", toastSeverity)
                .data("toastMessage", toastMessage);
    }

    // ════════════════════════════════════════════════════════════════════
    // Legacy price math (calcularPrecioConUtilidad/Edit + ConIVA/Edit parity)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Legacy {@code calcularPrecioConUtilidad(Edit)} parity: utilidad factor
     * HALF_UP at 4dp, CEILING to 0dp, then delegates to the IVA step.
     */
    private void calcularPrecioConUtilidad(@Nonnull ArticuloPrecio precio, @Nullable String cabysCodigo) {
        BigDecimal porcentajeUtilidad = precio.getPorcentajeUtilidad();
        BigDecimal precioCosto = precio.getPrecioCostoSinIVA();
        if (precioCosto == null || porcentajeUtilidad == null) {
            return;
        }
        if (precioCosto.compareTo(BigDecimal.ZERO) < 0 || porcentajeUtilidad.compareTo(BigDecimal.ZERO) < 0) {
            // Legacy surfaced an error message; here the computation simply
            // does not run (no partial write) — same observable outcome.
            return;
        }
        BigDecimal factorUtilidad = porcentajeUtilidad.divide(new BigDecimal(100), 4, RoundingMode.HALF_UP);
        BigDecimal utilidad = precioCosto.multiply(factorUtilidad);
        BigDecimal precioConUtilidad = precioCosto.add(utilidad).setScale(0, RoundingMode.CEILING);
        precio.setPrecioConUtilidad(precioConUtilidad);
        calcularPrecioConIVA(precio, cabysCodigo);
    }

    /**
     * Legacy {@code calcularPrecioConIVA(Edit)} parity: IVA from the CABYS
     * impuesto percent, CEILING to 0dp, stamps the adjusting user.
     */
    private void calcularPrecioConIVA(@Nonnull ArticuloPrecio precio, @Nullable String cabysCodigo) {
        BigDecimal precioConUtilidad = precio.getPrecioConUtilidad();
        Cabys cabys = cabysCodigo == null ? null : findCabys(cabysCodigo);
        if (precioConUtilidad == null || precioConUtilidad.compareTo(BigDecimal.ZERO) == 0
                || cabys == null || cabys.getImpuesto() == null || cabys.getImpuesto().isEmpty()) {
            return;
        }
        BigDecimal impuesto = new BigDecimal(cabys.getImpuesto());
        BigDecimal factorIVA = impuesto.divide(new BigDecimal(100));
        BigDecimal iva = precioConUtilidad.multiply(factorIVA);
        BigDecimal precioConIVA = precioConUtilidad.add(iva).setScale(0, RoundingMode.CEILING);
        precio.setUsuario(currentUser()); // legacy: registrar quien ajusto el precio
        precio.setPrecioFinal(precioConIVA);
    }

    // ════════════════════════════════════════════════════════════════════
    // Lookups, filtering, sorting (in-memory; Services layer untouched)
    // ════════════════════════════════════════════════════════════════════

    @Nullable
    private Cabys findCabys(@Nonnull String codigo) {
        // Exact PK resolution first (GService.find), then fall back to the
        // description-search scan for callers that post a description token.
        Cabys exact = cabysService.find(codigo);
        if (exact != null) {
            return exact;
        }
        for (Cabys c : orEmpty(cabysService.searchByName(codigo))) {
            if (codigo.equals(c.getCodigo())) {
                return c;
            }
        }
        return null;
    }

    @Nullable
    private ArticuloPrecio lastPrecioOf(@Nonnull Articulos articulo) {
        // Legacy getLastPrecio() parity FIRST: the entity's own (EAGER)
        // collection element is the one the update() cascade persists, so
        // recalculations must target it — not a detached service lookup.
        ArticuloPrecio fromCollection = articulo.getLastPrecio();
        if (fromCollection != null) {
            return fromCollection;
        }
        return precioService.findByArticulo(articulo);
    }

    private double stockOf(@Nonnull Articulos articulo) {
        String codigoBarra = articulo.getCodigoBarra();
        return codigoBarra == null ? 0.0 : inventarioService.getStock(codigoBarra);
    }

    private List<Articulos> articulosOfTab(@Nonnull String tab) {
        return switch (tab) {
            case TAB_INACTIVOS -> orEmpty(articulosService.listAllInactivos());
            case TAB_CATALOGO -> orEmpty(articulosService.listAllActivosYProcesados());
            case TAB_PENDIENTES -> orEmpty(articulosService.listAllSinProcesar());
            default -> orEmpty(articulosService.ListAllEnabled());
        };
    }

    /**
     * Legacy {@code ArticulosController.globalFilterFunction} parity:
     * codigo | nombre | codigoBarra | departamento | familia | usuario.
     */
    private static List<Articulos> filterArticulos(@Nonnull List<Articulos> source, @Nullable String q) {
        if (q == null || q.trim().isEmpty()) {
            return new ArrayList<>(source);
        }
        String needle = q.trim().toLowerCase(Locale.ROOT);
        List<Articulos> out = new ArrayList<>();
        for (Articulos a : source) {
            if (String.valueOf(a.getCodigo()).contains(needle)
                    || matches(a.getNombre(), needle)
                    || matches(a.getCodigoBarra(), needle)
                    || (a.getDepartamento() != null && matches(a.getDepartamento().getNombre(), needle))
                    || (a.getFamilia() != null && matches(a.getFamilia().getNombre(), needle))
                    || (a.getUsuario() != null && matches(a.getUsuario().getUsername(), needle))) {
                out.add(a);
            }
        }
        return out;
    }

    /**
     * Legacy {@code PromocionesController.globalFilterFunction} parity:
     * nombre | id | usuario.username.
     */
    private static List<Promocion> filterPromociones(@Nonnull List<Promocion> source, @Nullable String q) {
        if (q == null || q.trim().isEmpty()) {
            return new ArrayList<>(source);
        }
        String needle = q.trim().toLowerCase(Locale.ROOT);
        List<Promocion> out = new ArrayList<>();
        for (Promocion p : source) {
            if (matches(p.getNombre(), needle)
                    || String.valueOf(p.getId()).contains(needle)
                    || (p.getUsuario() != null && matches(p.getUsuario().getUsername(), needle))) {
                out.add(p);
            }
        }
        return out;
    }

    private static boolean matches(@Nullable String value, @Nonnull String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    /** Typed comparator dispatch over a whitelisted key set (articles). */
    private static void sortArticulos(@Nonnull List<Articulos> rows, @Nullable String sort,
                                      @Nullable String dir) {
        if (rows.isEmpty() || sort == null || sort.isBlank()) {
            return;
        }
        Comparator<Articulos> cmp = switch (sort) {
            case "codigo" -> Comparator.comparingLong(Articulos::getCodigo);
            case "nombre" -> Comparator.comparing(Articulos::getNombre,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "codigoBarra" -> Comparator.comparing(Articulos::getCodigoBarra,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "cabys" -> Comparator.comparing(a -> a.getCodigoCabys() == null
                    ? null : a.getCodigoCabys().getCodigo(),
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "precio" -> Comparator.comparing(a -> {
                ArticuloPrecio p = a.getLastPrecio();
                return p == null ? null : p.getPrecioFinal();
            }, Comparator.nullsLast(Comparator.naturalOrder()));
            default -> null;
        };
        if (cmp != null) {
            rows.sort("desc".equalsIgnoreCase(dir) ? cmp.reversed() : cmp);
        }
    }

    /** Typed comparator dispatch over a whitelisted key set (promotions). */
    private static void sortPromociones(@Nonnull List<Promocion> rows, @Nullable String sort,
                                        @Nullable String dir) {
        if (rows.isEmpty() || sort == null || sort.isBlank()) {
            return;
        }
        Comparator<Promocion> cmp = switch (sort) {
            case "id" -> Comparator.comparingInt(Promocion::getId);
            case "nombre" -> Comparator.comparing(Promocion::getNombre,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "fechaInicio" -> Comparator.comparing(Promocion::getFechaInicio,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case "fechaFin" -> Comparator.comparing(Promocion::getFechaFin,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case "descuento" -> Comparator.comparing(Promocion::getDescuento,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            default -> null;
        };
        if (cmp != null) {
            rows.sort("desc".equalsIgnoreCase(dir) ? cmp.reversed() : cmp);
        }
    }

    // ── Small parsers (legacy p:inputNumber parity: blank → null) ───────

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

    /**
     * ISO (yyyy-MM-dd) or ISO datetime local input parity; legacy used a
     * range datePicker whose first/last selections became inicio/fin.
     */
    @Nullable
    private static Date parseDateOrNull(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        try {
            if (value.length() > 10) {
                return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm").parse(value);
            }
            return new SimpleDateFormat("yyyy-MM-dd").parse(value);
        } catch (ParseException e) {
            return null;
        }
    }

    @Nullable
    private static String trimOrNull(@Nullable String raw) {
        return raw == null ? null : raw.trim();
    }

    @Nullable
    private static String emptyToNull(@Nullable String raw) {
        return raw == null || raw.isBlank() ? null : raw;
    }

    private static <T> List<T> orEmpty(@Nullable List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }

    // ── Current-user resolution (SessionController.getCurrentUser parity) ──

    /**
     * Resolves the authenticated {@link Users} row through the T12 identity
     * provider's principal. Returns null for anonymous/system contexts
     * (alertas accepts null).
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

    // ════════════════════════════════════════════════════════════════════
    // Form payloads & DTO mappers (manual, repo convention)
    // ════════════════════════════════════════════════════════════════════

    /** Mutable create/update payload shared by JSON and form variants. */
    public static class ArticuloForm {
        @Nullable public String nombre;
        @Nullable public String codigoBarra;
        @Nullable public String descripcion;
        @Nullable public String unidadMedida;
        @Nullable public String unidadMedidaComercial;
        @Nullable public Integer departamentoId;
        @Nullable public Integer familiaId;
        @Nullable public String cabysCodigo;
        @Nullable public BigDecimal precioCostoSinIVA;
        @Nullable public BigDecimal porcentajeUtilidad;
        public boolean exento;
        @Nullable public Integer stockOptimo;
        @Nullable public Integer diasStockSeguridad;
    }

    /** Promotion create/update payload (JSON shape). */
    public static class PromocionForm {
        @Nullable public String nombre;
        @Nullable public BigDecimal descuento;
        @Nullable public BigDecimal cantidad;
        @Nullable public String codigoDescuento;
        public boolean ensambladoOrigen;
        @Nullable public Date fechaInicio;
        @Nullable public Date fechaFin;
        @Nullable public List<ItemForm> items;
    }

    /** One promotion line: article code + quantity. */
    public static class ItemForm {
        @Nullable public Long articuloCodigo;
        @Nullable public BigDecimal cantidad;
    }

    private static @Nonnull ArticuloForm formOf(@Nullable ArticuloForm body) {
        return body == null ? new ArticuloForm() : body;
    }

    private static @Nonnull PromocionForm promoFormOf(@Nullable String nombre,
                                                      @Nullable String descuento,
                                                      @Nullable String cantidad,
                                                      @Nullable String codigoDescuento,
                                                      @Nullable String ensambladoOrigen,
                                                      @Nullable String fechaInicio,
                                                      @Nullable String fechaFin,
                                                      @Nullable List<String> itemCodigos,
                                                      @Nullable List<String> itemCantidades) {
        PromocionForm form = new PromocionForm();
        form.nombre = nombre;
        form.descuento = parseDecimalOrNull(descuento);
        form.cantidad = parseDecimalOrNull(cantidad);
        form.codigoDescuento = codigoDescuento;
        form.ensambladoOrigen = "on".equalsIgnoreCase(ensambladoOrigen)
                || "true".equalsIgnoreCase(ensambladoOrigen);
        form.fechaInicio = parseDateOrNull(fechaInicio);
        form.fechaFin = parseDateOrNull(fechaFin);
        List<ItemForm> items = new ArrayList<>();
        if (itemCodigos != null && itemCantidades != null) {
            int n = Math.min(itemCodigos.size(), itemCantidades.size());
            for (int i = 0; i < n; i++) {
                Long codigo = parseLongOrNull(itemCodigos.get(i));
                BigDecimal cant = parseDecimalOrNull(itemCantidades.get(i));
                if (codigo != null && cant != null) {
                    ItemForm item = new ItemForm();
                    item.articuloCodigo = codigo;
                    item.cantidad = cant;
                    items.add(item);
                }
            }
        }
        form.items = items;
        return form;
    }

    @Nullable
    private static Long parseLongOrNull(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Builds transient/persisted carrito items from the form payload. */
    private List<ArticuloCarrito> buildItems(@Nullable List<ItemForm> payloads) {
        List<ArticuloCarrito> items = new ArrayList<>();
        if (payloads == null) {
            return items;
        }
        for (ItemForm payload : payloads) {
            if (payload.articuloCodigo == null) {
                continue;
            }
            Articulos articulo = articulosService.findById(payload.articuloCodigo.intValue());
            if (articulo == null) {
                continue;
            }
            ArticuloCarrito item = new ArticuloCarrito();
            item.setArticulo(articulo);
            item.setCantidad(payload.cantidad == null ? BigDecimal.ONE : payload.cantidad);
            items.add(item);
        }
        return items;
    }

    private static CabysDTO toDTO(@Nonnull Cabys c) {
        return new CabysDTO(c.getCodigo(), c.getDescripcion(), c.getCategorias(),
                c.getImpuesto(), c.getUri(), c.getEstado());
    }

    private static PromocionDTO toDTO(@Nonnull Promocion p) {
        List<PromocionDTO.ArticuloRef> refs = new ArrayList<>();
        for (ArticuloCarrito item : orEmpty(p.getArticulosCarrito())) {
            refs.add(new PromocionDTO.ArticuloRef(item.getCodigo(),
                    item.getArticulo() != null ? item.getArticulo().getNombre() : null));
        }
        return new PromocionDTO(p.getId(), p.getNombre(), p.getDescuento(), p.getCantidad(),
                p.getFechaInicio(), p.getFechaFin(), p.isActiva(), p.isEnsambladoOrigen(),
                p.getCodigoDescuento(),
                p.getUsuario() != null ? p.getUsuario().getId() : null,
                p.getUsuario() != null ? p.getUsuario().getUsername() : null,
                refs);
    }

    /** Compact list-row view (keeps lazy relations out of Jackson). */
    private static ArticuloListDTO toListDTO(@Nonnull Articulos a) {
        ArticuloPrecio precio = a.getLastPrecio();
        return new ArticuloListDTO(
                a.getCodigo(),
                a.getCodigoBarra(),
                a.getNombre(),
                a.isStatus(),
                a.isProcessed(),
                a.getCodigoCabys() != null ? a.getCodigoCabys().getCodigo() : null,
                a.getDepartamento() != null ? a.getDepartamento().getNombre() : null,
                a.getFamilia() != null ? a.getFamilia().getNombre() : null,
                a.getUsuario() != null ? a.getUsuario().getUsername() : null,
                precio == null ? null : precio.getPrecioFinal());
    }

    /** Full detail view incl. flattened price history. */
    private ArticuloDetailDTO toDetailDTO(@Nonnull Articulos a) {
        List<ArticuloPrecio> historial = orEmpty(precioService.findAllByArticulo(a));
        if (historial.isEmpty() && a.getPrecios() != null) {
            historial = a.getPrecios();
        }
        List<PrecioDTO> precios = new ArrayList<>();
        for (ArticuloPrecio p : historial) {
            precios.add(new PrecioDTO(p.getId(), p.getPrecioCostoSinIVA(), p.getPorcentajeUtilidad(),
                    p.getPrecioConUtilidad(), p.getPrecioFinal(), p.getFechaCompra(),
                    p.getUsuario() != null ? p.getUsuario().getUsername() : null));
        }
        return new ArticuloDetailDTO(
                a.getCodigo(),
                a.getCodigoBarra(),
                a.getNombre(),
                a.getDescripcion(),
                a.getUnidadMedida(),
                a.getUnidadMedidaComercial(),
                a.isStatus(),
                a.isProcessed(),
                a.isExento(),
                a.getCodigoCabys() != null ? a.getCodigoCabys().getCodigo() : null,
                a.getCodigoCabys() != null ? a.getCodigoCabys().getDescripcion() : null,
                a.getDepartamento() != null ? a.getDepartamento().getId() : null,
                a.getDepartamento() != null ? a.getDepartamento().getNombre() : null,
                a.getFamilia() != null ? a.getFamilia().getId() : null,
                a.getFamilia() != null ? a.getFamilia().getNombre() : null,
                a.getUsuario() != null ? a.getUsuario().getUsername() : null,
                stockOf(a),
                precios);
    }

    /** Compact list-row DTO. */
    public static class ArticuloListDTO {
        public Long codigo;
        @Nullable public String codigoBarra;
        @Nullable public String nombre;
        public boolean status;
        public boolean processed;
        @Nullable public String cabysCodigo;
        @Nullable public String departamento;
        @Nullable public String familia;
        @Nullable public String usuario;
        @Nullable public BigDecimal precioFinal;

        ArticuloListDTO(Long codigo, @Nullable String codigoBarra, @Nullable String nombre,
                        boolean status, boolean processed, @Nullable String cabysCodigo,
                        @Nullable String departamento, @Nullable String familia,
                        @Nullable String usuario, @Nullable BigDecimal precioFinal) {
            this.codigo = codigo;
            this.codigoBarra = codigoBarra;
            this.nombre = nombre;
            this.status = status;
            this.processed = processed;
            this.cabysCodigo = cabysCodigo;
            this.departamento = departamento;
            this.familia = familia;
            this.usuario = usuario;
            this.precioFinal = precioFinal;
        }
    }

    /** Detail DTO with price history + stock. */
    public static class ArticuloDetailDTO {
        public Long codigo;
        @Nullable public String codigoBarra;
        @Nullable public String nombre;
        @Nullable public String descripcion;
        @Nullable public String unidadMedida;
        @Nullable public String unidadMedidaComercial;
        public boolean status;
        public boolean processed;
        public boolean exento;
        @Nullable public String cabysCodigo;
        @Nullable public String cabysDescripcion;
        @Nullable public Integer departamentoId;
        @Nullable public String departamentoNombre;
        @Nullable public Integer familiaId;
        @Nullable public String familiaNombre;
        @Nullable public String usuario;
        public double stock;
        public List<PrecioDTO> precios;

        ArticuloDetailDTO(Long codigo, @Nullable String codigoBarra, @Nullable String nombre,
                          @Nullable String descripcion, @Nullable String unidadMedida,
                          @Nullable String unidadMedidaComercial, boolean status, boolean processed,
                          boolean exento, @Nullable String cabysCodigo, @Nullable String cabysDescripcion,
                          @Nullable Integer departamentoId, @Nullable String departamentoNombre,
                          @Nullable Integer familiaId, @Nullable String familiaNombre,
                          @Nullable String usuario, double stock, List<PrecioDTO> precios) {
            this.codigo = codigo;
            this.codigoBarra = codigoBarra;
            this.nombre = nombre;
            this.descripcion = descripcion;
            this.unidadMedida = unidadMedida;
            this.unidadMedidaComercial = unidadMedidaComercial;
            this.status = status;
            this.processed = processed;
            this.exento = exento;
            this.cabysCodigo = cabysCodigo;
            this.cabysDescripcion = cabysDescripcion;
            this.departamentoId = departamentoId;
            this.departamentoNombre = departamentoNombre;
            this.familiaId = familiaId;
            this.familiaNombre = familiaNombre;
            this.usuario = usuario;
            this.stock = stock;
            this.precios = precios;
        }
    }

    /** Flattened price-history row. */
    public static class PrecioDTO {
        public int id;
        @Nullable public BigDecimal precioCostoSinIVA;
        @Nullable public BigDecimal porcentajeUtilidad;
        @Nullable public BigDecimal precioConUtilidad;
        @Nullable public BigDecimal precioFinal;
        @Nullable public Date fechaCompra;
        @Nullable public String usuario;

        PrecioDTO(int id, @Nullable BigDecimal precioCostoSinIVA, @Nullable BigDecimal porcentajeUtilidad,
                  @Nullable BigDecimal precioConUtilidad, @Nullable BigDecimal precioFinal,
                  @Nullable Date fechaCompra, @Nullable String usuario) {
            this.id = id;
            this.precioCostoSinIVA = precioCostoSinIVA;
            this.porcentajeUtilidad = porcentajeUtilidad;
            this.precioConUtilidad = precioConUtilidad;
            this.precioFinal = precioFinal;
            this.fechaCompra = fechaCompra;
            this.usuario = usuario;
        }
    }

    /** Payload of GET /revision/siguiente. */
    public static class RevisionNextDTO {
        public boolean hasNext;
        @Nullable public ArticuloDetailDTO articulo;

        RevisionNextDTO(boolean hasNext, @Nullable ArticuloDetailDTO articulo) {
            this.hasNext = hasNext;
            this.articulo = articulo;
        }
    }

    /** Payload of POST /{codigo}/revision. */
    public static class RevisionResultDTO {
        public boolean success;
        public int pendientesRestantes;
        public String mensaje;

        RevisionResultDTO(boolean success, int pendientesRestantes, String mensaje) {
            this.success = success;
            this.pendientesRestantes = pendientesRestantes;
            this.mensaje = mensaje;
        }
    }
}
