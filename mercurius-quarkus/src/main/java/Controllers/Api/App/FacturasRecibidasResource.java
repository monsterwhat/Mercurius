package Controllers.Api.App;

import Controllers.Api.App.Reportes.ReportePageSupport;
import Models.ComprobantesRecibidos;
import Models.DTO.ApiResponse;
import Models.DTO.ComprobantesRecibidosDetailDTO;
import Models.DTO.ComprobantesRecibidosListDTO;
import Models.DTO.PagedResponse;
import Models.Detalles.LineaDetalle;
import Models.Encabezado.CorreoElectronicoEmisor;
import Models.Encabezado.CorreoElectronicoReceptor;
import Models.Encabezado.Emisor;
import Models.Encabezado.Encabezado;
import Models.Encabezado.Receptor;
import Models.Users;
import Models.Validacion.PrevalidationResult;
import Models.Validacion.ValidationError;
import Services.AlertasService;
import Services.ComprobantesRecibidosPrevalidationService;
import Services.ComprobantesRecibidosService;
import Services.ConsecutivoReceptorService;
import Services.Facturas.LineaDetalleService;
import Services.LoginService;
import Services.MensajeReceptorService;
import Services.Strategies.DocumentoStrategyFactory;
import Utils.AsyncUserContext;
import Utils.DiffUtils;
import Utils.Parsers.Parser;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
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
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.parsers.DocumentBuilderFactory;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Facturas recibidas (received electronic invoices) module for the NEW
 * Qute/HTMX app surface (plan task T36): JSON + fragment endpoints replacing
 * the received-invoice-processing subset of the legacy JSF pair
 * {@code Controllers.FacturasController} (upload, line review, Mensaje
 * Receptor send, ConsecutivoReceptor assignment, prevalidation wiring) and
 * {@code Controllers.ComprobantesRecibidosController} (upload/list/detail).
 *
 * <p><b>Behavior parity contract</b> (ported 1:1, receipts in
 * .omo/evidence/t36/parity-matrix.md):</p>
 * <ul>
 *   <li>Bucket listings delegate ONLY to existing
 *       {@link ComprobantesRecibidosService} queries: todas/activas =
 *       {@code ListAllEnabled()}, pagadas/procesadas = in-memory filters over
 *       {@code ListAllEnabled()} (legacy stream-filter parity), pendientes =
 *       {@code listPendientes()}, vencidas = {@code listVencidas()}.</li>
 *   <li>Global filter {@code q} reproduces the legacy
 *       {@code globalFilterFunction} fields: codigoActividadEmisor,
 *       condicionVenta, emisor nombre/correos/identificacion/nombreComercial,
 *       fechaEmision and numeroConsecutivo (case-insensitive contains).</li>
 *   <li>Upload (legacy {@code parseXMLFromUploadedFile}/{@code processFacturas}):
 *       each multipart part is pre-validated, then fed to the SAME
 *       {@link Parser#parseXML(java.io.InputStream)} inside the SAME
 *       {@link AsyncUserContext#setCurrentUser(String)}/{@link AsyncUserContext#clear()
 *       clear()} ThreadLocal bracket with the username captured from the
 *       {@link SecurityIdentity} principal BEFORE parsing (parser threads read
 *       AsyncUserContext). The parser persists the comprobante through
 *       {@code ComprobantesRecibidosService.createWithRelatedEntities}, which
 *       records prevalidation issues on the entity but ALWAYS persists.
 *       Deviation (documented): the legacy ComprobantesRecibidos dialog also
 *       archived raw files via SettingsDirController.saveUploadedFile; that
 *       helper is PrimeFaces-coupled, so the REST surface keeps T35's
 *       precedent of not archiving (the parsed comprobante is the system of
 *       record).</li>
 *   <li>Line review: legacy accepted/rejected lines in-session for partial
 *       acceptance ({@code aceptarLinea/rechazarLinea}); the NEW surface adds
 *       the plan-required PUT correction of the CAByS code per line (the one
 *       field prevalidation flags and a reviewer can fix without touching
 *       tax math), persisted through {@link LineaDetalleService#update}.</li>
 *   <li>Mensaje Receptor send (legacy {@code aceptarFacturaRecibida}/
 *       {@code rechazarFacturaRecibida}/
 *       {@code enviarAceptacionParcial}): re-fetch with details →
 *       {@link ComprobantesRecibidosPrevalidationService#prevalidarCompleto(Long)}
 *       gate (ERROR-level issues block with 409; WARNING-level log + allow) →
 *       CondicionVenta validation against the document strategy's permitted
 *       codes → totals (full: resumen totals; partial: sums over the accepted
 *       lines) → {@link MensajeReceptorService#enviarMensajeReceptor}. No
 *       Hacienda call happens outside that service.</li>
 *   <li>ConsecutivoReceptor assignment: GET preview is NON-mutating
 *       ({@link ConsecutivoReceptorService#findCounter}); the authoritative
 *       increment stays inside {@code MensajeReceptorService}
 *       ({@code getNextSequential}), exactly like the legacy flow — the legacy
 *       controller injected the service but never called it directly.</li>
 * </ul>
 *
 * <p><b>Paging/sorting contract</b> follows docs/ui-kit.md §3.1: 1-based
 * {@code page} (default 1), {@code size} default 20, whitelisted {@code sort}
 * keys, {@code dir} asc|desc, reserved keys never treated as filters.
 * Filtering/sorting/paging is computed in memory over the existing service
 * queries because the Services layer is frozen for this task.</p>
 *
 * <p><b>Fragment dual-mode:</b> endpoints backing a UI surface check the
 * {@code HX-Request} header — present ⇒ only the requested fragment (inbox
 * table, detail drawer body, prevalidation panel, upload result + OOB toast);
 * absent ⇒ plain JSON following the {@link ApiResponse}/{@link PagedResponse}
 * envelopes. GET /tabla without HX-Request renders the FULL page (mirroring
 * T35).</p>
 *
 * <p><b>Authorization:</b> {@code admin} or {@code facturacion} (the module's
 * managing roles, matching the legacy facturacion area gate).</p>
 */
@Path("/api/app/facturas-recibidas")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"admin", "facturacion"})
@Tag(name = "App - Facturas Recibidas")
public class FacturasRecibidasResource {

    private static final Logger LOG = Logger.getLogger(FacturasRecibidasResource.class.getName());

    /** Bucket keys (URL-facing); legacy tab titles kept in templates. */
    public static final String BUCKET_TODAS = "todas";
    public static final String BUCKET_ACTIVAS = "activas";
    public static final String BUCKET_PAGADAS = "pagadas";
    public static final String BUCKET_PROCESADAS = "procesadas";
    public static final String BUCKET_PENDIENTES = "pendientes";
    public static final String BUCKET_VENCIDAS = "vencidas";

    /** Parser's own missing-consecutivo message (surfaced verbatim, T35 parity). */
    private static final String MSG_FALTA_CONSECUTIVO = "XML inválido: falta el número consecutivo";

    @Inject
    @Nonnull
    ComprobantesRecibidosService recibidosService;

    @Inject
    @Nonnull
    LineaDetalleService lineaDetalleService;

    @Inject
    @Nonnull
    ComprobantesRecibidosPrevalidationService prevalidationService;

    @Inject
    @Nonnull
    ConsecutivoReceptorService consecutivoReceptorService;

    @Inject
    @Nonnull
    MensajeReceptorService mensajeReceptorService;

    @Inject
    @Nonnull
    DocumentoStrategyFactory strategyFactory;

    @Inject
    @Nonnull
    AlertasService alertas;

    @Inject
    @Nonnull
    LoginService loginService;

    @Inject
    @Nonnull
    Parser parser;

    @Inject
    @Nonnull
    SecurityIdentity identity;

    /** Request headers (quarkus-rest injectable) — source of HX-Request. */
    @Inject
    @Nonnull
    HttpHeaders httpHeaders;

    // Templates (rendered to String: no quarkus-rest-qute MessageBodyWriter
    // on this stack — same approach as InventarioResource/TributacionPagesResource).
    @Inject
    @Nonnull
    @Location("pages/facturas-recibidas/index.html")
    Template pageIndex;

    @Inject
    @Nonnull
    @Location("pages/facturas-recibidas/tabla.html")
    Template tabla;

    @Inject
    @Nonnull
    @Location("pages/facturas-recibidas/detalle-drawer.html")
    Template detalleDrawer;

    @Inject
    @Nonnull
    @Location("pages/facturas-recibidas/prevalidacion-panel.html")
    Template prevalidacionPanel;

    @Inject
    @Nonnull
    @Location("pages/facturas-recibidas/upload-resultado.html")
    Template uploadResultado;

    // ════════════════════════════════════════════════════════════════════
    // Inbox list (kit contract params page/size/sort/dir)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Paginated inbox list. {@code bucket} selects the legacy source query,
     * {@code q} reproduces the legacy {@code globalFilterFunction}.
     */
    @GET
    @Operation(summary = "List received invoices with pagination, sorting and global filter")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Paginated received invoices"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Role not allowed"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response list(
            @QueryParam("bucket") @DefaultValue(BUCKET_TODAS) @org.eclipse.microprofile.openapi.annotations.parameters.Parameter(
                    description = "todas|activas|pagadas|procesadas|pendientes|vencidas") String bucket,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("sort") @Nullable String sort,
            @QueryParam("dir") @DefaultValue("asc") String dir,
            @QueryParam("q") @Nullable String q) {
        try {
            PagedResponse<ComprobantesRecibidosListDTO> payload = listPage(bucket, page, size, sort, dir, q);
            return Response.ok(payload).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error listando las facturas recibidas", e);
            return serverError("Error listando las facturas recibidas");
        }
    }

    /**
     * Inbox table fragment (HX-Request) or FULL page otherwise — exactly the
     * _kit/data-table SERVER-SIDE CONTRACT used by T35's /table endpoint.
     */
    @GET
    @Path("/tabla")
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Inbox data-table fragment (HX-Request) or full page", hidden = true)
    public Response tabla(
            @QueryParam("bucket") @DefaultValue(BUCKET_TODAS) String bucket,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("sort") @Nullable String sort,
            @QueryParam("dir") @DefaultValue("asc") String dir,
            @QueryParam("q") @Nullable String q) {
        try {
            Map<String, Object> modelo = tablaModel(bucket, page, size, sort, dir, q);
            TemplateInstance instance = isHxRequest()
                    ? tabla.instance()
                    : pageIndex.instance();
            modelo.forEach(instance::data);
            return htmlOk(instance);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error renderizando la tabla de facturas recibidas", e);
            return serverError("Error cargando el buzón de facturas recibidas");
        }
    }

    /** Shared table/page model (kit data contract keys). */
    public @Nonnull Map<String, Object> tablaModel(@Nonnull String bucket, int page, int size,
                                                   @Nullable String sort, @Nullable String dir,
                                                   @Nullable String q) {
        PagedResponse<ComprobantesRecibidosListDTO> payload = listPage(bucket, page, size, sort, dir, q);
        int pagina = clampPage(page);
        int totalPaginas = totalPages(payload.getTotal(), payload.getSize());
        List<Map<String, Object>> columnas = new ArrayList<>();
        columnas.add(columna("Consecutivo", "consecutivo"));
        columnas.add(columna("Fecha", "fechaEmision"));
        columnas.add(columna("Emisor", "emisorNombre"));
        columnas.add(columna("Estado MR", null));
        columnas.add(columna("Total", "totalComprobante"));
        columnas.add(columna("Impuesto", "totalImpuesto"));
        return ReportePageSupport.model(
                "id", "facturas-recibidas",
                "baseUrl", "/api/app/facturas-recibidas/tabla",
                "columnas", columnas,
                "filas", payload.getData(),
                "sortKey", sort,
                "sortDir", isDescending(dir) ? "desc" : "asc",
                "page", pagina,
                "size", payload.getSize(),
                "total", payload.getTotal(),
                "totalPages", totalPaginas,
                "paginas", pageWindow(pagina, totalPaginas),
                "filtros", params("bucket", normalizarBucket(bucket), "q", q),
                "q", q,
                "bucket", normalizarBucket(bucket));
    }

    private @Nonnull PagedResponse<ComprobantesRecibidosListDTO> listPage(@Nonnull String bucket, int page,
                                                                          int size, @Nullable String sort,
                                                                          @Nullable String dir,
                                                                          @Nullable String q) {
        String cubeta = normalizarBucket(bucket);
        List<ComprobantesRecibidos> fuente = aplicarPredicado(cubeta, fuenteDelBucket(cubeta));
        List<ComprobantesRecibidos> filtradas = filtrar(fuente, q);
        ordenar(filtradas, sort, dir);
        long total = filtradas.size();
        int pagina = clampPage(page);
        int medida = clampSize(size);
        int from = (pagina - 1) * medida;
        List<ComprobantesRecibidosListDTO> data = new ArrayList<>();
        if (from < filtradas.size()) {
            for (ComprobantesRecibidos f : filtradas.subList(from, Math.min(from + medida, filtradas.size()))) {
                data.add(toListDTO(f));
            }
        }
        return new PagedResponse<>(data, total, pagina, medida);
    }

    private static @Nonnull Map<String, Object> columna(@Nonnull String label, @Nullable String key) {
        Map<String, Object> mapa = new LinkedHashMap<>();
        mapa.put("label", label);
        mapa.put("key", key);
        return mapa;
    }

    /** Legacy bucket → service query mapping (parity documented above). */
    private @Nonnull List<ComprobantesRecibidos> fuenteDelBucket(@Nonnull String bucket) {
        return switch (bucket) {
            case BUCKET_PENDIENTES -> orEmpty(recibidosService.listPendientes());
            case BUCKET_VENCIDAS -> orEmpty(recibidosService.listVencidas());
            case BUCKET_TODAS, BUCKET_ACTIVAS, BUCKET_PAGADAS, BUCKET_PROCESADAS -> orEmpty(recibidosService.ListAllEnabled());
            default -> orEmpty(recibidosService.ListAllEnabled());
        };
    }

    private static @Nonnull String normalizarBucket(@Nullable String bucket) {
        if (bucket == null || bucket.isBlank()) {
            return BUCKET_TODAS;
        }
        return switch (bucket.trim().toLowerCase()) {
            case BUCKET_ACTIVAS -> BUCKET_ACTIVAS;
            case BUCKET_PAGADAS -> BUCKET_PAGADAS;
            case BUCKET_PROCESADAS -> BUCKET_PROCESADAS;
            case BUCKET_PENDIENTES -> BUCKET_PENDIENTES;
            case BUCKET_VENCIDAS -> BUCKET_VENCIDAS;
            default -> BUCKET_TODAS;
        };
    }

    /** Applies the legacy bucket predicates after the shared source query. */
    private static @Nonnull List<ComprobantesRecibidos> aplicarPredicado(@Nonnull String bucket,
                                                                         @Nonnull List<ComprobantesRecibidos> filas) {
        return switch (bucket) {
            case BUCKET_PAGADAS -> filas.stream().filter(f -> f.getPaid() != null && f.getPaid()).toList();
            case BUCKET_PROCESADAS -> filas.stream().filter(f -> f.getProcessed() != null && f.getProcessed()).toList();
            case BUCKET_ACTIVAS -> filas.stream().filter(f -> f.getStatus() != null && f.getStatus()).toList();
            default -> filas;
        };
    }

    /** Legacy globalFilterFunction fields, case-insensitive contains. */
    private static @Nonnull List<ComprobantesRecibidos> filtrar(@Nonnull List<ComprobantesRecibidos> filas,
                                                                @Nullable String q) {
        String texto = q == null ? "" : q.trim().toLowerCase();
        if (texto.isEmpty()) {
            return filas;
        }
        List<ComprobantesRecibidos> salida = new ArrayList<>();
        for (ComprobantesRecibidos f : filas) {
            Encabezado enc = f.getEncabezado();
            Emisor emisor = enc == null ? null : enc.getEmisor();
            if (contiene(enc == null ? null : enc.getCodigoActividadEmisor(), texto)
                    || contiene(enc == null ? null : enc.getCondicionVenta(), texto)
                    || contiene(emisor == null ? null : emisor.getNombre(), texto)
                    || contieneCorreo(emisor == null ? null : emisor.getCorreosElectronicos(), texto)
                    || contiene(emisor != null && emisor.getIdentificacion() != null
                                ? emisor.getIdentificacion().getNumero() : null, texto)
                    || contiene(emisor == null ? null : emisor.getNombreComercial(), texto)
                    || contiene(enc == null ? null : String.valueOf(enc.getFechaEmision()), texto)
                    || contiene(enc == null ? null : enc.getNumeroConsecutivo(), texto)) {
                salida.add(f);
            }
        }
        return salida;
    }

    private static boolean contiene(@Nullable String fuente, @Nonnull String filtro) {
        return fuente != null && fuente.toLowerCase().contains(filtro);
    }

    private static boolean contieneCorreo(@Nullable List<CorreoElectronicoEmisor> correos, @Nonnull String filtro) {
        if (correos == null) {
            return false;
        }
        return correos.stream()
                .map(CorreoElectronicoEmisor::getCorreo)
                .filter(Objects::nonNull)
                .anyMatch(correo -> correo.toLowerCase().contains(filtro));
    }

    /** Whitelisted sort keys (kit golden rule #5: reserved keys excluded). */
    private void ordenar(@Nonnull List<ComprobantesRecibidos> filas, @Nullable String sort, @Nullable String dir) {
        filas.sort(comparadorPor(sort, !isDescending(dir)));
    }

    private static @Nonnull Comparator<ComprobantesRecibidos> comparadorPor(@Nullable String sort,
                                                                            boolean ascendente) {
        Comparator<ComprobantesRecibidos> base;
        if ("consecutivo".equals(sort)) {
            base = Comparator.comparing(FacturasRecibidasResource::consecutivoDe,
                    Comparator.nullsFirst(Comparator.naturalOrder()));
        } else if ("fechaEmision".equals(sort)) {
            base = Comparator.comparing(FacturasRecibidasResource::fechaDe,
                    Comparator.nullsFirst(Comparator.naturalOrder()));
        } else if ("emisor".equals(sort)) {
            base = Comparator.comparing(FacturasRecibidasResource::emisorDe,
                    Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER));
        } else if ("totalComprobante".equals(sort)) {
            base = Comparator.comparing(FacturasRecibidasResource::totalDe,
                    Comparator.nullsFirst(Comparator.naturalOrder()));
        } else if ("totalImpuesto".equals(sort)) {
            base = Comparator.comparing(FacturasRecibidasResource::impuestoDe,
                    Comparator.nullsFirst(Comparator.naturalOrder()));
        } else if ("estado".equals(sort)) {
            base = Comparator.comparing(FacturasRecibidasResource::estadoDe,
                    Comparator.nullsFirst(Comparator.naturalOrder()));
        } else {
            base = Comparator.comparing(ComprobantesRecibidos::getId,
                    Comparator.nullsFirst(Comparator.naturalOrder()));
        }
        return ascendente ? base : base.reversed();
    }

    private static @Nullable String consecutivoDe(@Nonnull ComprobantesRecibidos f) {
        return f.getEncabezado() != null ? f.getEncabezado().getNumeroConsecutivo() : null;
    }

    private static @Nullable java.time.LocalDateTime fechaDe(@Nonnull ComprobantesRecibidos f) {
        return f.getEncabezado() != null ? f.getEncabezado().getFechaEmision() : null;
    }

    private static @Nullable String emisorDe(@Nonnull ComprobantesRecibidos f) {
        return f.getEncabezado() != null && f.getEncabezado().getEmisor() != null
                ? f.getEncabezado().getEmisor().getNombre() : null;
    }

    private static @Nullable BigDecimal totalDe(@Nonnull ComprobantesRecibidos f) {
        return f.getResumen() != null ? f.getResumen().getTotalComprobante() : null;
    }

    private static @Nullable BigDecimal impuestoDe(@Nonnull ComprobantesRecibidos f) {
        return f.getResumen() != null ? f.getResumen().getTotalImpuesto() : null;
    }

    private static @Nullable String estadoDe(@Nonnull ComprobantesRecibidos f) {
        return f.getEncabezado() != null ? f.getEncabezado().getEstado() : null;
    }

    // ════════════════════════════════════════════════════════════════════
    // Detail drawer (detail DTO + editable lines)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Detail of one comprobante (legacy showDetailsDialog payload): flattened
     * {@link ComprobantesRecibidosDetailDTO} plus the reviewable lines. With
     * {@code HX-Request} renders the drawer body fragment instead.
     */
    @GET
    @Path("/{id}")
    @Operation(summary = "Detail of one received invoice (HX-Request renders the drawer body)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Detail"),
        @APIResponse(responseCode = "404", description = "Unknown id"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response detalle(@PathParam("id") long id) {
        try {
            ComprobantesRecibidos factura = recibidosService.findByIdWithDetails(id);
            if (factura == null) {
                return notFound("No se encontró la factura solicitada");
            }
            if (isHxRequest()) {
                return htmlOk(drawerFragment(factura, null, null));
            }
            return Response.ok(ApiResponse.ok(toDetalleResponse(factura))).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error leyendo el detalle de la factura " + id, e);
            return serverError("Error leyendo el detalle de la factura");
        }
    }

    /** Drawer body fragment instance for one comprobante (+ optional toast). */
    private @Nonnull TemplateInstance drawerFragment(@Nonnull ComprobantesRecibidos factura,
                                                     @Nullable String toastSeverity,
                                                     @Nullable String toastMessage) {
        DetalleResponse respuesta = toDetalleResponse(factura);
        return detalleDrawer
                .data("d", respuesta.detalle())
                .data("lineas", respuesta.lineas())
                .data("toastSeverity", toastSeverity)
                .data("toastMessage", toastMessage);
    }

    /**
     * PUT line correction — the plan-required line-review fix. Only the CAByS
     * code is correctable (the field prevalidation flags; correcting it cannot
     * drift tax math). A present value must be a 13-digit code. Persisted via
     * {@link LineaDetalleService#update}; audit alerta mirrors the legacy
     * DiffUtils before/after discipline.
     */
    @PUT
    @Path("/{id}/lineas/{lineaId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Correct the CAByS code of one line (line-review PUT)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Corrected line (fragment when HX-Request)"),
        @APIResponse(responseCode = "400", description = "Invalid CAByS format"),
        @APIResponse(responseCode = "404", description = "Unknown comprobante or line"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response corregirLineaJson(@PathParam("id") long id, @PathParam("lineaId") long lineaId,
                                      @Nullable LineaCorrectionRequest cuerpo) {
        return doCorregirLinea(id, lineaId, cuerpo == null ? null : cuerpo.codigoCabys());
    }

    /** Form-urlencoded twin of {@link #corregirLineaJson} for the HTMX input. */
    @PUT
    @Path("/{id}/lineas/{lineaId}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Operation(summary = "Correct the CAByS code of one line from an HTMX form", hidden = true)
    public Response corregirLineaForm(@PathParam("id") long id, @PathParam("lineaId") long lineaId,
                                      @RestForm("codigoCabys") @Nullable String codigoCabys) {
        return doCorregirLinea(id, lineaId, codigoCabys);
    }

    private Response doCorregirLinea(long id, long lineaId, @Nullable String nuevoCodigo) {
        try {
            ComprobantesRecibidos factura = recibidosService.findByIdWithDetails(id);
            if (factura == null) {
                return notFound("No se encontró la factura solicitada");
            }
            LineaDetalle linea = lineaDetalleService.findById(lineaId);
            if (linea == null || factura.getDetalles() == null
                    || !perteneceALaFactura(factura, lineaId)) {
                return notFound("No se encontró la línea solicitada en esta factura");
            }
            if (nuevoCodigo == null || nuevoCodigo.isBlank()) {
                return badRequest("El código CAByS no puede estar vacío");
            }
            String codigo = nuevoCodigo.trim();
            if (!codigo.matches("\\d{13}")) {
                return badRequest("El código CAByS '" + codigo + "' no tiene 13 dígitos");
            }
            String antes = DiffUtils.snapshotEntity(linea);
            linea.setCodigoCabys(codigo);
            lineaDetalleService.update(linea);
            alertas.registrarAlerta("Exito",
                    "Código CAByS de la línea " + lineaId + " corregido a " + codigo,
                    currentUser(), 0, "FacturasRecibidasResource.corregirLinea()", antes,
                    DiffUtils.snapshotEntity(linea));

            ComprobantesRecibidos actualizada = recibidosService.findByIdWithDetails(id);
            if (isHxRequest()) {
                return htmlOk(drawerFragment(actualizada == null ? factura : actualizada,
                        "success", "Línea " + lineaId + " actualizada"));
            }
            return Response.ok(ApiResponse.ok(LineaView.of(lineaDetalleService.findById(lineaId)))).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error corrigiendo la línea " + lineaId, e);
            return serverError("Error corrigiendo la línea");
        }
    }

    private static boolean perteneceALaFactura(@Nonnull ComprobantesRecibidos factura, long lineaId) {
        if (factura.getDetalles() == null || factura.getDetalles().getLineasDetalle() == null) {
            return false;
        }
        return factura.getDetalles().getLineasDetalle().stream()
                .anyMatch(l -> l.getId() != null && l.getId() == lineaId);
    }

    // ════════════════════════════════════════════════════════════════════
    // Prevalidation panel (ComprobantesRecibidosPrevalidationService)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Panel data from {@link ComprobantesRecibidosPrevalidationService} —
     * structural flags (INVALID_FORMAT / MISSING_CABYS, LINE_TAX_MISMATCH /
     * RESUMEN_MISMATCH, receptor/reference issues) rendered as Bulma tags.
     */
    @GET
    @Path("/{id}/prevalidacion")
    @Produces({MediaType.APPLICATION_JSON, MediaType.TEXT_HTML})
    @Operation(summary = "Prevalidation panel data (HX-Request renders the panel fragment)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Prevalidation result"),
        @APIResponse(responseCode = "404", description = "Unknown id"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response prevalidacion(@PathParam("id") long id) {
        try {
            ComprobantesRecibidos factura = recibidosService.findByIdWithDetails(id);
            if (factura == null) {
                return notFound("No se encontró la factura solicitada");
            }
            PrevalidationResult resultado = prevalidationService.prevalidarCompleto(id);
            if (isHxRequest()) {
                return htmlOk(panelFragment(resultado));
            }
            return Response.ok(ApiResponse.ok(toPanel(resultado))).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error pre-validando la factura " + id, e);
            return serverError("Error ejecutando la pre-validación");
        }
    }

    private @Nonnull TemplateInstance panelFragment(@Nonnull PrevalidationResult resultado) {
        Map<String, Object> panel = toPanel(resultado);
        return prevalidacionPanel.data("panel", panel);
    }

    /** Flat panel model consumed by both the JSON twin and the template. */
    private static @Nonnull Map<String, Object> toPanel(@Nonnull PrevalidationResult resultado) {
        List<Map<String, Object>> issues = new ArrayList<>();
        for (ValidationError e : resultado.getAllIssues()) {
            Map<String, Object> fila = new LinkedHashMap<>();
            fila.put("severity", e.getSeverity() == ValidationError.Severity.ERROR ? "error" : "warning");
            fila.put("category", e.getCategory() != null ? e.getCategory().name() : null);
            fila.put("code", e.getCode());
            fila.put("field", e.getField());
            fila.put("message", e.getMessage());
            issues.add(fila);
        }
        Map<String, Object> panel = new LinkedHashMap<>();
        panel.put("comprobanteId", resultado.getComprobanteId());
        panel.put("numeroConsecutivo", resultado.getNumeroConsecutivo());
        panel.put("isValid", !resultado.hasErrors());
        panel.put("hasWarnings", resultado.hasWarnings());
        panel.put("errorCount", resultado.getErrorCount());
        panel.put("warningCount", resultado.getWarningCount());
        panel.put("validatedAt", resultado.getValidatedAt());
        panel.put("issues", issues);
        return panel;
    }

    // ════════════════════════════════════════════════════════════════════
    // Mensaje Receptor send (aceptar / rechazar / aceptación parcial)
    // ════════════════════════════════════════════════════════════════════

    /**
     * POST mensaje-receptor — port of {@code aceptarFacturaRecibida} (1),
     * {@code enviarAceptacionParcial} (2, requires {@code lineasAceptadas}) and
     * {@code rechazarFacturaRecibida} (3). ERROR-level prevalidation issues
     * block with 409 (legacy blocked with an error FacesMessage); warnings are
     * logged and allowed. The actual queueing/signing/Hacienda submission
     * happens ONLY inside {@link MensajeReceptorService}.
     */
    @POST
    @Path("/{id}/mensaje-receptor")
    @Consumes({MediaType.APPLICATION_FORM_URLENCODED, MediaType.MULTIPART_FORM_DATA})
    @Operation(summary = "Send Mensaje Receptor (accept=1 / partial=2 / reject=3)", hidden = true)
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Processed (data.success carries the outcome)"),
        @APIResponse(responseCode = "400", description = "Invalid request or CondicionVenta"),
        @APIResponse(responseCode = "404", description = "Unknown id"),
        @APIResponse(responseCode = "409", description = "Blocked by prevalidation errors"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response mensajeReceptorForm(@PathParam("id") long id,
                                        @FormParam("codigoMensaje") @Nullable String codigoMensaje,
                                        @FormParam("lineasAceptadas") @Nullable List<Long> lineasAceptadas) {
        return doMensajeReceptor(id, codigoMensaje, lineasAceptadas);
    }

    /** JSON twin of {@link #mensajeReceptorForm}. */
    @POST
    @Path("/{id}/mensaje-receptor")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Send Mensaje Receptor (JSON twin)")
    public Response mensajeReceptorJson(@PathParam("id") long id, @Nullable MensajeReceptorRequest cuerpo) {
        return doMensajeReceptor(id, cuerpo == null ? null : cuerpo.codigoMensaje(),
                cuerpo == null ? null : cuerpo.lineasAceptadas());
    }

    private Response doMensajeReceptor(long id, @Nullable String codigoMensajeRaw,
                                       @Nullable List<Long> lineasAceptadas) {
        try {
            Integer codigo = parseCodigoMensaje(codigoMensajeRaw);
            if (codigo == null) {
                return badRequest("codigoMensaje debe ser 1 (aceptar), 2 (parcial) o 3 (rechazar)");
            }
            ComprobantesRecibidos factura = recibidosService.findByIdWithDetails(id);
            if (factura == null) {
                return notFound("No se encontró la factura solicitada");
            }

            // ── Legacy prevalidation gate: errors block, warnings allow ──
            PrevalidationResult preResultado = prevalidationService.prevalidarCompleto(id);
            if (preResultado.hasErrors()) {
                List<String> detalles = new ArrayList<>();
                for (ValidationError err : preResultado.getErrors()) {
                    detalles.add(err.getField() + ": " + err.getMessage());
                    alertas.registrarAlerta("Pre-validación (bloqueo)",
                            err.getField() + ": " + err.getMessage(), currentUser(), 0,
                            "FacturasRecibidasResource.doMensajeReceptor()", null, err.getMessage());
                }
                String resumen = preResultado.getErrorCount()
                        + " error(es) de pre-validación impiden enviar el Mensaje Receptor";
                if (isHxRequest()) {
                    return Response.status(Response.Status.CONFLICT)
                            .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                            .entity(prevalidacionPanel
                                    .data("panel", toPanel(preResultado))
                                    .data("toastSeverity", "error")
                                    .data("toastMessage", resumen)
                                    .render())
                            .build();
                }
                return Response.status(Response.Status.CONFLICT)
                        .entity(ApiResponse.error("PREVALIDATION_FAILED", resumen, detalles))
                        .build();
            }
            for (ValidationError warn : preResultado.getWarnings()) {
                alertas.registrarAlerta("Pre-validación (advertencia)",
                        warn.getField() + ": " + warn.getMessage(), currentUser(), 0,
                        "FacturasRecibidasResource.doMensajeReceptor()", null, warn.getMessage());
            }

            // ── Totals: full uses resumen, partial sums accepted lines ──
            BigDecimal montoTotalImpuesto;
            BigDecimal montoTotalFactura;
            String accion;
            switch (codigo) {
                case 1 -> {
                    accion = "Aceptado";
                    montoTotalImpuesto = factura.getResumen() != null
                            && factura.getResumen().getTotalImpuesto() != null
                            ? factura.getResumen().getTotalImpuesto() : BigDecimal.ZERO;
                    montoTotalFactura = factura.getResumen() != null
                            && factura.getResumen().getTotalComprobante() != null
                            ? factura.getResumen().getTotalComprobante() : BigDecimal.ZERO;
                }
                case 3 -> {
                    accion = "Rechazado";
                    montoTotalImpuesto = factura.getResumen() != null
                            && factura.getResumen().getTotalImpuesto() != null
                            ? factura.getResumen().getTotalImpuesto() : BigDecimal.ZERO;
                    montoTotalFactura = factura.getResumen() != null
                            && factura.getResumen().getTotalComprobante() != null
                            ? factura.getResumen().getTotalComprobante() : BigDecimal.ZERO;
                }
                default -> {
                    accion = "Aceptado Parcial";
                    if (lineasAceptadas == null || lineasAceptadas.isEmpty()) {
                        return badRequest("Debe aceptar al menos una línea para enviar aceptación parcial");
                    }
                    BigDecimal impuesto = BigDecimal.ZERO;
                    BigDecimal total = BigDecimal.ZERO;
                    List<LineaDetalle> lineas = factura.getDetalles() != null
                            && factura.getDetalles().getLineasDetalle() != null
                            ? factura.getDetalles().getLineasDetalle() : Collections.emptyList();
                    Set<Long> aceptadas = Set.copyOf(lineasAceptadas);
                    for (LineaDetalle linea : lineas) {
                        if (linea.getId() != null && aceptadas.contains(linea.getId())) {
                            if (linea.getImpuestoNeto() != null) {
                                impuesto = impuesto.add(linea.getImpuestoNeto());
                            }
                            if (linea.getMontoTotal() != null) {
                                total = total.add(linea.getMontoTotal());
                            }
                        }
                    }
                    montoTotalImpuesto = impuesto;
                    montoTotalFactura = total;
                }
            }

            // ── Legacy validarCondicionVentaFactura ─────────────────────
            Encabezado encabezado = factura.getEncabezado();
            String condicionVenta = encabezado != null ? encabezado.getCondicionVenta() : null;
            String codigoDocumento = encabezado != null ? encabezado.getCodigoDocumento() : null;
            if (condicionVenta != null) {
                var strategy = strategyFactory.forCode(codigoDocumento);
                java.util.Set<String> permitidas = strategy.getCondicionVentaPermitidas();
                if (!permitidas.contains(condicionVenta)) {
                    return Response.status(Response.Status.BAD_REQUEST)
                            .entity(ApiResponse.error("VALIDATION_ERROR",
                                    "CondicionVenta código " + condicionVenta + " no permitido para tipo documento "
                                            + codigoDocumento + ". Códigos permitidos: " + permitidas))
                            .build();
                }
            }

            // ── Queue through the existing service (only Hacienda path) ──
            MensajeReceptorService.MRResult resultado = mensajeReceptorService.enviarMensajeReceptor(
                    factura, codigo, accion, montoTotalImpuesto, montoTotalFactura);

            String severidad = resultado.success ? "success" : "error";
            if (isHxRequest()) {
                ComprobantesRecibidos actualizada = recibidosService.findByIdWithDetails(id);
                return htmlOk(drawerFragment(actualizada == null ? factura : actualizada,
                        severidad, resultado.message));
            }
            return Response.ok(ApiResponse.ok(new MRResultView(resultado.success, resultado.message,
                    resultado.estado))).build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error enviando el Mensaje Receptor de la factura " + id, e);
            return serverError("Error enviando el Mensaje Receptor");
        }
    }

    private static @Nullable Integer parseCodigoMensaje(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int valor = Integer.parseInt(raw.trim());
            return (valor >= 1 && valor <= 3) ? valor : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // ConsecutivoReceptor preview (non-mutating assignment view)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Preview of the next ConsecutivoReceptor for sucursal/terminal/tipo —
     * NON-mutating ({@link ConsecutivoReceptorService#findCounter}). The
     * authoritative increment happens inside
     * {@link MensajeReceptorService#enviarMensajeReceptor} via
     * {@code getNextSequential}, exactly like the legacy flow.
     */
    @GET
    @Path("/consecutivo-receptor")
    @Operation(summary = "Preview the next ConsecutivoReceptor (non-mutating)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Preview"),
        @APIResponse(responseCode = "400", description = "Invalid parameters"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response consecutivoReceptor(
            @QueryParam("sucursal") @DefaultValue("001") String sucursal,
            @QueryParam("terminal") @DefaultValue("001") String terminal,
            @QueryParam("codigoMensaje") @DefaultValue("1") String codigoMensaje) {
        Integer codigo = parseCodigoMensaje(codigoMensaje);
        if (codigo == null) {
            return badRequest("codigoMensaje debe ser 1 (aceptar), 2 (parcial) o 3 (rechazar)");
        }
        try {
            String mrType = mrTypeDe(codigo);
            String sucursalFmt = String.format("%03d", Integer.parseInt(sucursal.trim()));
            String terminalFmt = String.format("%05d", Integer.parseInt(terminal.trim()));
            Models.ConsecutivoReceptor contador =
                    consecutivoReceptorService.findCounter(sucursalFmt, terminalFmt, mrType);
            long actual = contador != null && contador.getUltimoSecuencial() != null
                    ? contador.getUltimoSecuencial() : 0L;
            String siguiente = String.format("%010d", actual + 1);
            return Response.ok(ApiResponse.ok(new ConsecutivoPreview(sucursalFmt, terminalFmt, mrType,
                    actual, siguiente, sucursalFmt + terminalFmt + mrType + siguiente))).build();
        } catch (NumberFormatException e) {
            return badRequest("sucursal debe ser numérico (3 dígitos) y terminal numérico (5 dígitos)");
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error consultando el consecutivo receptor", e);
            return serverError("Error consultando el consecutivo receptor");
        }
    }

    /** Legacy mapping in MensajeReceptorService: 1→05, 2→06, 3→07. */
    private static @Nonnull String mrTypeDe(int codigoMensaje) {
        return codigoMensaje == 1 ? "05" : (codigoMensaje == 2 ? "06" : "07");
    }

    // ════════════════════════════════════════════════════════════════════
    // XML upload (legacy parseXMLFromUploadedFile/processFacturas → Parser)
    // ════════════════════════════════════════════════════════════════════

    /**
     * POST /upload — multipart port of the legacy upload dialogs. Every file
     * part is processed SEQUENTIALLY (legacy loop semantics): pre-validate
     * well-formedness + consecutivo presence, then invoke the SAME
     * {@link Parser#parseXML(java.io.InputStream)} inside the SAME
     * {@link AsyncUserContext} ThreadLocal bracket. The parser persists the
     * comprobante (recording prevalidation issues without blocking).
     */
    @POST
    @Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Operation(summary = "Upload received-invoice XML files into the parser", hidden = true)
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

    /** One file's sequential processing (parseXMLFromUploadedFile parity). */
    private @Nonnull UploadFileResult processSingleFile(@Nonnull FileUpload parte, @Nonnull String username) {
        String fileName = parte.fileName() == null ? "(sin nombre)" : parte.fileName();
        byte[] contenido;
        try {
            contenido = Files.readAllBytes(parte.uploadedFile());
        } catch (IOException e) {
            alertas.registrarAlerta("Error al procesar archivo",
                    "Archivo: " + fileName + " - Error: " + e.getMessage(), null, 0,
                    "FacturasRecibidasResource.processSingleFile()", fileName, e.getMessage());
            return new UploadFileResult(fileName, false, "No se pudo leer el archivo: " + e.getMessage());
        }
        if (contenido.length == 0) {
            alertas.registrarAlerta("Error", "File is empty: " + fileName, null, 0,
                    "FacturasRecibidasResource.processSingleFile()", fileName, null);
            return new UploadFileResult(fileName, false, "El archivo está vacío");
        }
        String errorPrevalidacion = prevalidateXml(contenido);
        if (errorPrevalidacion != null) {
            alertas.registrarAlerta("Error", errorPrevalidacion + " (" + fileName + ")", null, 0,
                    "FacturasRecibidasResource.prevalidateXml()", fileName, null);
            return new UploadFileResult(fileName, false, errorPrevalidacion);
        }
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(contenido)) {
            // Same ThreadLocal bracket as the legacy JSF upload: set BEFORE
            // parseXML, cleared in finally, username captured from the
            // authenticated principal instead of the JSF session.
            AsyncUserContext.setCurrentUser(username);
            parser.parseXML(inputStream);
            alertas.registrarAlerta("Info", "Successfully processed file: " + fileName,
                    null, 0, "FacturasRecibidasResource.processSingleFile()", fileName, null);
            return new UploadFileResult(fileName, true, "Archivo procesado por el parser");
        } catch (IOException | RuntimeException e) {
            alertas.registrarAlerta("Error al parsear xml de factura",
                    "Archivo: " + fileName + " - Error: " + e.getMessage(), currentUser(), 0,
                    "FacturasRecibidasResource.processSingleFile()", e.getMessage(), null);
            return new UploadFileResult(fileName, false, "Error al procesar el archivo XML: " + e.getMessage());
        } finally {
            AsyncUserContext.clear();
        }
    }

    /**
     * Cheap structural gate BEFORE the parser runs so malformed documents get
     * a clean, deterministic danger notification (T35 parity).
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
        org.w3c.dom.NodeList hijos = raiz.getElementsByTagName("*");
        for (int i = 0; i < hijos.getLength(); i++) {
            Element hijo = (Element) hijos.item(i);
            String atributo = hijo.getAttribute("NumeroConsecutivo");
            if (!atributo.isEmpty()) {
                return atributo;
            }
            org.w3c.dom.NodeList nietos = hijo.getChildNodes();
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
    // Mappers (manual, codebase convention)
    // ════════════════════════════════════════════════════════════════════

    private static @Nonnull ComprobantesRecibidosListDTO toListDTO(@Nonnull ComprobantesRecibidos f) {
        Encabezado enc = f.getEncabezado();
        return new ComprobantesRecibidosListDTO(
                f.getId(),
                enc != null ? enc.getClave() : null,
                enc != null ? enc.getNumeroConsecutivo() : null,
                enc != null ? enc.getFechaEmision() : null,
                enc != null && enc.getEmisor() != null ? enc.getEmisor().getNombre() : null,
                f.getResumen() != null ? f.getResumen().getTotalComprobante() : null,
                f.getResumen() != null ? f.getResumen().getTotalImpuesto() : null,
                enc != null ? enc.getCodigoDocumento() : null,
                enc != null ? enc.getEstado() : null,
                f.getHaciendaMensajeReceptorEstado(),
                f.getMensajeReceptorLimite(),
                f.getStatus(),
                f.getProcessed(),
                f.getPaid());
    }

    private static @Nonnull DetalleResponse toDetalleResponse(@Nonnull ComprobantesRecibidos f) {
        Encabezado enc = f.getEncabezado();
        Emisor emisor = enc != null ? enc.getEmisor() : null;
        Receptor receptor = enc != null ? enc.getReceptor() : null;

        ComprobantesRecibidosDetailDTO dto = new ComprobantesRecibidosDetailDTO();
        dto.setId(f.getId());
        dto.setSchemaVersion(f.getSchemaVersion());
        dto.setStatus(f.getStatus());
        dto.setProcessed(f.getProcessed());
        dto.setPaid(f.getPaid());
        dto.setPrevalidationErrors(f.getPrevalidationErrors());
        dto.setUser(f.getUser());
        dto.setHaciendaMensajeReceptorEstado(f.getHaciendaMensajeReceptorEstado());
        dto.setHaciendaMensajeReceptorFecha(f.getHaciendaMensajeReceptorFecha());
        dto.setMensajeReceptorLimite(f.getMensajeReceptorLimite());
        if (enc != null) {
            dto.setClave(enc.getClave());
            dto.setProveedorSistemas(enc.getProveedorSistemas());
            dto.setCodigoActividadEmisor(enc.getCodigoActividadEmisor());
            dto.setCodigoActividadReceptor(enc.getCodigoActividadReceptor());
            dto.setConsecutivo(enc.getNumeroConsecutivo());
            dto.setFechaEmision(enc.getFechaEmision());
            dto.setCondicionVenta(enc.getCondicionVenta());
            dto.setCondicionVentaOtros(enc.getCondicionVentaOtros());
            dto.setPlazoCredito(enc.getPlazoCredito());
            dto.setCodigoDocumento(enc.getCodigoDocumento());
            dto.setEstado(enc.getEstado());
            dto.setMotivoRechazo(enc.getMotivoRechazo());
        }
        if (emisor != null) {
            dto.setEmisorNombre(emisor.getNombre());
            dto.setEmisorNombreComercial(emisor.getNombreComercial());
            dto.setEmisorTipoIdentificacion(emisor.getIdentificacion() != null
                    ? emisor.getIdentificacion().getTipo() : null);
            dto.setEmisorNumeroIdentificacion(emisor.getIdentificacion() != null
                    ? emisor.getIdentificacion().getNumero() : null);
            dto.setEmisorCorreosElectronicos(correos(emisor.getCorreosElectronicos()));
        }
        if (receptor != null) {
            dto.setReceptorNombre(receptor.getNombre());
            dto.setReceptorNombreComercial(receptor.getNombreComercial());
            dto.setReceptorTipoIdentificacion(receptor.getIdentificacion() != null
                    ? receptor.getIdentificacion().getTipo() : null);
            dto.setReceptorNumeroIdentificacion(receptor.getIdentificacion() != null
                    ? receptor.getIdentificacion().getNumero() : null);
            dto.setReceptorCorreosElectronicos(correosReceptor(receptor.getCorreosElectronicos()));
        }
        if (enc != null && enc.getMedioPago() != null) {
            dto.setMediosPago(enc.getMedioPago().stream()
                    .map(mp -> mp.getMedioPago())
                    .toList());
        }
        if (f.getResumen() != null) {
            var r = f.getResumen();
            dto.setCodigoMoneda(r.getCodigoMoneda() != null ? r.getCodigoMoneda().getCodigoMoneda() : null);
            dto.setTipoCambio(r.getCodigoMoneda() != null ? r.getCodigoMoneda().getTipoCambioMoneda() : null);
            dto.setTotalServGravados(r.getTotalServGravados());
            dto.setTotalServExentos(r.getTotalServExentos());
            dto.setTotalServExonerado(r.getTotalServExonerado());
            dto.setTotalServNoSujeto(r.getTotalServNoSujeto());
            dto.setTotalMercanciasGravadas(r.getTotalMercanciasGravadas());
            dto.setTotalMercanciasExentas(r.getTotalMercanciasExentas());
            dto.setTotalMercExonerada(r.getTotalMercExonerada());
            dto.setTotalMercNoSujeta(r.getTotalMercNoSujeta());
            dto.setTotalGravado(r.getTotalGravado());
            dto.setTotalExento(r.getTotalExento());
            dto.setTotalExonerado(r.getTotalExonerado());
            dto.setTotalNoSujeto(r.getTotalNoSujeto());
            dto.setTotalVenta(r.getTotalVenta());
            dto.setTotalDescuentos(r.getTotalDescuentos());
            dto.setTotalVentaNeta(r.getTotalVentaNeta());
            dto.setTotalImpuesto(r.getTotalImpuesto());
            dto.setTotalImpuestoAsumidoEmisorFabrica(r.getTotalImpuestoAsumidoEmisorFabrica());
            dto.setTotalIVADevuelto(r.getTotalIVADevuelto());
            dto.setTotalOtrosCargos(r.getTotalOtrosCargos());
            dto.setTotalComprobante(r.getTotalComprobante());
        }

        List<LineaView> lineas = new ArrayList<>();
        if (f.getDetalles() != null && f.getDetalles().getLineasDetalle() != null) {
            for (LineaDetalle linea : f.getDetalles().getLineasDetalle()) {
                lineas.add(LineaView.of(linea));
            }
        }
        return new DetalleResponse(dto, lineas);
    }

    private static @Nullable List<String> correos(@Nullable List<CorreoElectronicoEmisor> correos) {
        if (correos == null) {
            return null;
        }
        return correos.stream()
                .map(CorreoElectronicoEmisor::getCorreo)
                .filter(Objects::nonNull)
                .toList();
    }

    /** Same mapping for the receptor-side contact list (CorreoElectronicoReceptor). */
    private static @Nullable List<String> correosReceptor(@Nullable List<CorreoElectronicoReceptor> correos) {
        if (correos == null) {
            return null;
        }
        return correos.stream()
                .map(CorreoElectronicoReceptor::getCorreo)
                .filter(Objects::nonNull)
                .toList();
    }

    // ════════════════════════════════════════════════════════════════════
    // Paging/sorting helpers (ReportePageSupport semantics, local copies)
    // ════════════════════════════════════════════════════════════════════

    private static int clampPage(int page) {
        return Math.max(page, 1);
    }

    private static int clampSize(int size) {
        if (size < 1) {
            return 20;
        }
        return Math.min(size, 500);
    }

    private static int totalPages(long total, int size) {
        if (total <= 0 || size <= 0) {
            return 1;
        }
        return (int) ((total + size - 1) / size);
    }

    private static @Nonnull List<Integer> pageWindow(int page, int totalPaginas) {
        int ventana = 5;
        int mitad = ventana / 2;
        int inicio = Math.max(1, page - mitad);
        int fin = Math.min(totalPaginas, inicio + ventana - 1);
        inicio = Math.max(1, fin - ventana + 1);
        List<Integer> paginas = new ArrayList<>();
        for (int i = inicio; i <= fin; i++) {
            paginas.add(i);
        }
        return paginas;
    }

    private static boolean isDescending(@Nullable String dir) {
        return "desc".equalsIgnoreCase(dir);
    }

    private static @Nonnull Map<String, Object> params(Object... keyValuePairs) {
        Map<String, Object> mapa = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValuePairs.length; i += 2) {
            Object valor = keyValuePairs[i + 1];
            if (valor == null || (valor instanceof String s && s.isBlank())) {
                continue;
            }
            mapa.put(String.valueOf(keyValuePairs[i]), valor);
        }
        return mapa;
    }

    private static @Nonnull List<ComprobantesRecibidos> orEmpty(@Nullable List<ComprobantesRecibidos> lista) {
        return lista == null ? Collections.emptyList() : lista;
    }

    // ════════════════════════════════════════════════════════════════════
    // Current-user resolution + small response helpers (T35 parity)
    // ════════════════════════════════════════════════════════════════════

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
        return "system";
    }

    private boolean isHxRequest() {
        return ReportePageSupport.isHxRequest(httpHeaders);
    }

    private static Response htmlOk(@Nonnull TemplateInstance template) {
        return Response.ok(template.render())
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).build();
    }

    private static Response notFound(@Nonnull String mensaje) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(ApiResponse.error("NOT_FOUND", mensaje)).build();
    }

    private static Response badRequest(@Nonnull String mensaje) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(ApiResponse.error("VALIDATION_ERROR", mensaje)).build();
    }

    private static Response serverError(@Nonnull String mensaje) {
        return Response.serverError()
                .entity(ApiResponse.error("INTERNAL_ERROR", mensaje)).build();
    }

    private Response failureWithToast(@Nonnull TemplateInstance fragmento, @Nonnull String severidad,
                                      @Nonnull String mensaje, @Nonnull Response.Status status,
                                      @Nonnull String codigo) {
        if (isHxRequest()) {
            return Response.status(status)
                    .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                    .entity(fragmento
                            .data("toastSeverity", severidad)
                            .data("toastMessage", mensaje)
                            .render())
                    .build();
        }
        return Response.status(status).entity(ApiResponse.error(codigo, mensaje)).build();
    }

    /** Empty upload-result fragment instance (shared by failures). */
    private @Nonnull TemplateInstance uploadResultadoVacio() {
        return uploadResultado
                .data("resultados", Collections.emptyList())
                .data("procesados", 0L)
                .data("fallidos", 0L);
    }

    // ════════════════════════════════════════════════════════════════════
    // Small value carriers
    // ════════════════════════════════════════════════════════════════════

    /** Line-correction request body (JSON twin of the drawer input). */
    public record LineaCorrectionRequest(String codigoCabys) {}

    /** Mensaje-Receptor request body (JSON twin of the drawer forms). */
    public record MensajeReceptorRequest(String codigoMensaje, List<Long> lineasAceptadas) {}

    /** Reviewable line row of the detail drawer. */
    public record LineaView(Long id, Integer numeroLinea, String detalle, String codigoCabys,
                            BigDecimal cantidad, String unidadMedida, BigDecimal baseImponible,
                            BigDecimal impuestoNeto, BigDecimal montoTotal, BigDecimal montoTotalLinea,
                            Boolean aceptada) {
        static LineaView of(@Nullable LineaDetalle l) {
            if (l == null) {
                return null;
            }
            return new LineaView(l.getId(), l.getNumeroLinea(), l.getDetalle(), l.getCodigoCabys(),
                    l.getCantidad(), l.getUnidadMedida(), l.getBaseImponible(), l.getImpuestoNeto(),
                    l.getMontoTotal(), l.getMontoTotalLinea(), Boolean.TRUE);
        }
    }

    /** Detail payload: flattened DTO + reviewable lines. */
    public record DetalleResponse(ComprobantesRecibidosDetailDTO detalle, List<LineaView> lineas) {}

    /** Outcome of the Mensaje-Receptor send (MRResult mirror). */
    public record MRResultView(boolean success, String message, String estado) {}

    /** Non-mutating ConsecutivoReceptor preview. */
    public record ConsecutivoPreview(String sucursal, String terminal, String tipo,
                                     long secuencialActual, String secuencialSiguiente,
                                     String compuesto) {}

    /** Per-file upload outcome surfaced in the result fragment. */
    public record UploadFileResult(String fileName, boolean exito, String mensaje) {}

    /** Aggregate upload outcome for JSON clients. */
    public record UploadResponse(List<UploadFileResult> resultados, long procesados, long fallidos) {}
}
