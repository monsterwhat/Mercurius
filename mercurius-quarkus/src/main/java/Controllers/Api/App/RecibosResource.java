package Controllers.Api.App;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import Models.ComprobantesEmitidos;
import Models.DTO.ApiResponse;
import Models.DTO.ComprobantesEmitidosDetailDTO;
import Models.DTO.ComprobantesEmitidosListDTO;
import Models.DTO.PagedResponse;
import Models.Encabezado.CorreoElectronicoEmisor;
import Models.Encabezado.CorreoElectronicoReceptor;
import Models.Encabezado.Emisor;
import Models.Encabezado.Encabezado;
import Models.Encabezado.Receptor;
import Services.ComprobanteService;
import Services.ComprobantesEmitidosService;
import Services.LoginService;
import Controllers.Api.App.Reportes.ReportePageSupport;
import Utils.DiffUtils;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
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
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * T27 — Recibos module API ({@code /api/app/recibos}): the four-bucket
 * receipts board (Todas / Pagadas / Procesadas / Vencidos) that replaces the
 * legacy {@code secured/pages/Recibos/index.xhtml} tabView plus the receipt
 * actions of the deleted JSF pair.
 *
 * <p><b>Entity scoping (coordination contract):</b> received-invoice
 * processing lives in {@code FacturasRecibidasResource} (T36, over
 * {@code ComprobantesRecibidos}); THIS module manages the EMITTED comprobante
 * lifecycle through {@link ComprobantesEmitidosService} and the pre-built
 * {@link ComprobantesEmitidosListDTO}/{@link ComprobantesEmitidosDetailDTO}.
 * The legacy page's bucket semantics are mirrored onto the fields this entity
 * actually has (mapping table in
 * {@code .omo/evidence/t27/baseline-characterization.md}):</p>
 * <ul>
 *   <li><b>todas</b> — every row of {@code listAll()} (legacy Todas counted
 *       ALL rows via {@code count()});</li>
 *   <li><b>pagadas</b> — {@code Encabezado.estado == 'ACEPTADO'} (settled
 *       terminal state; legacy filtered {@code paid==true});</li>
 *   <li><b>procesadas</b> — {@code haciendaEstado} set and not PENDIENTE,
 *       i.e. the comprobante went through the Hacienda exchange (legacy
 *       {@code processed==true});</li>
 *   <li><b>vencidos</b> — unsettled credit sale past due:
 *       {@code condicionVenta=='02'} + parseable plazoCredito +
 *       {@code fechaEmision + plazo <= today}, mirroring
 *       {@code ComprobantesRecibidosService.listVencidas()} date math.</li>
 * </ul>
 *
 * <p><b>Actions</b> delegate VERBATIM to existing service methods with the
 * same side-effect shapes as their legacy origins:</p>
 * <ul>
 *   <li>{@code POST /{id}/pay} (+ north-star alias {@code /{id}/pagar}) —
 *       {@code FacturasController.paySelectedFactura} parity: guard, flip
 *       estado to ACEPTADO via {@code update}, audit alert with the verbatim
 *       legacy strings. Credit notes ('02') are refused server-side — commit
 *       20d3cde's hide-pay rule.</li>
 *   <li>{@code POST /{id}/process} — single-row port of
 *       {@code ComprobantesEmitidosController.reenviarSeleccionados}: one
 *       verbatim call to
 *       {@link ComprobanteService#enviarComprobanteAHacienda}, which performs
 *       all its own state flips and audit alerts inside the service.</li>
 *   <li>{@code POST /{id}/accept} / {@code POST /{id}/reject} — record the
 *       Mensaje-Receptor-style resolution on {@code Encabezado.estado}
 *       (accept/reject controller methods were removed from
 *       FacturasController by T36; the flip+alert shape is preserved).</li>
 *   <li>{@code DELETE /{id}} and {@code POST /{id}/toggle} — verbatim ports
 *       of {@code ComprobantesEmitidosController.deleteFactura/toggleFactura}
 *       (softDelete/toggle + DiffUtils snapshots + same alert strings) so the
 *       deleted JSF bean loses no behavior.</li>
 * </ul>
 *
 * <p><b>Fragments (docs/ui-kit.md):</b> every HTML endpoint checks
 * {@code HX-Request}; {@code GET /tabla?bucket=X} swaps ONE bucket table
 * (stable ids {@code recibos-tabla-*}), action endpoints re-render the whole
 * {@code #recibos-tablero} (stats + four tables) so buckets never disagree,
 * plus an out-of-band toast. Non-HTMX callers get plain JSON envelopes
 * (the templates always drive actions through htmx). Under HTMX, guard
 * failures return 200 with a danger toast because htmx does not swap 4xx
 * bodies by default; JSON mode keeps proper 404/409 codes.</p>
 *
 * <p><b>Roles:</b> {@code admin} + {@code facturacion} (legacy web.xml gate
 * for {@code /secured/pages/Recibos/*}).</p>
 */
@Path("/api/app/recibos")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"admin", "facturacion"})
public class RecibosResource {

    private static final Logger LOG = Logger.getLogger(RecibosResource.class.getName());

    /** Bucket keys (URL-facing); legacy tab titles kept in templates. */
    public static final String BUCKET_TODAS = "todas";
    public static final String BUCKET_PAGADAS = "pagadas";
    public static final String BUCKET_PROCESADAS = "procesadas";
    public static final String BUCKET_VENCIDOS = "vencidos";

    /** Hacienda document code for notas de crédito (commit 20d3cde rule). */
    private static final String CODIGO_NOTA_CREDITO = "02";

    @Nonnull
    @Inject
    ComprobantesEmitidosService comprobantesEmitidosService;

    @Nonnull
    @Inject
    ComprobanteService comprobanteService;

    
    @Nonnull
    @Inject
    LoginService loginService;

    @Inject
    @Nonnull
    SecurityIdentity identity;

    /** Request headers — source of HX-Request. */
    @Context
    @Nonnull
    HttpHeaders httpHeaders;

    @Nonnull
    @Location("pages/recibos/tablero")
    @Inject
    Template tablero;

    @Nonnull
    @Location("pages/recibos/tabla")
    @Inject
    Template tabla;

    @Nonnull
    @Location("pages/recibos/detalle")
    @Inject
    Template detalle;

    // ════════════════════════════════════════════════════════════════════
    // Bucket predicates (package-visible: shared with RecibosPagesResource)
    // ════════════════════════════════════════════════════════════════════

    /** Pagadas: settled terminal state (legacy {@code paid==true} analog). */
    static boolean esPagada(@Nullable ComprobantesEmitidos f) {
        Encabezado enc = f == null ? null : f.getEncabezado();
        return enc != null && "ACEPTADO".equalsIgnoreCase(enc.getEstado());
    }

    /** Procesadas: went through the Hacienda exchange at least once. */
    static boolean esProcesada(@Nullable ComprobantesEmitidos f) {
        String haciendaEstado = f == null ? null : f.getHaciendaEstado();
        return haciendaEstado != null && !"PENDIENTE".equalsIgnoreCase(haciendaEstado);
    }

    /**
     * Vencidos: unsettled credit receivable whose due mark
     * ({@code fechaEmision + plazoCredito days}) is today or past —
     * {@code listVencidas()} parity. Contado sales can never be overdue.
     */
    static boolean esVencida(@Nullable ComprobantesEmitidos f) {
        if (esPagada(f)) {
            return false;
        }
        Encabezado enc = f == null ? null : f.getEncabezado();
        if (enc != null && "RECHAZADO".equalsIgnoreCase(enc.getEstado())) {
            return false;
        }
        if (enc == null || enc.getFechaEmision() == null || enc.getPlazoCredito() == null) {
            return false;
        }
        if (!CODIGO_NOTA_CREDITO.equals(enc.getCondicionVenta())) {
            // condicionVenta '02' = crédito; anything else cannot vencer.
            return false;
        }
        int plazo;
        try {
            plazo = Integer.parseInt(enc.getPlazoCredito().trim());
        } catch (NumberFormatException e) {
            return false;
        }
        LocalDate vencimiento = enc.getFechaEmision().toLocalDate().plusDays(plazo);
        return !vencimiento.isAfter(LocalDate.now());
    }

    /** True when the row is a nota de crédito (hide-pay rule, 20d3cde). */
    static boolean esNotaCredito(@Nullable ComprobantesEmitidos f) {
        Encabezado enc = f == null ? null : f.getEncabezado();
        return enc != null && CODIGO_NOTA_CREDITO.equals(enc.getCodigoDocumento());
    }

    /**
     * Rows of one bucket, base query + controller-side predicate — exactly
     * the legacy pattern (FacturasController filtered ListAllEnabled() in the
     * bean instead of pushing predicates into the service).
     */
    private @Nonnull List<ComprobantesEmitidos> filasDelBucket(@Nonnull String bucket) {
        List<ComprobantesEmitidos> todas = orEmpty(comprobantesEmitidosService.listAll());
        return switch (bucket) {
            case BUCKET_PAGADAS -> todas.stream().filter(RecibosResource::esPagada).toList();
            case BUCKET_PROCESADAS -> todas.stream().filter(RecibosResource::esProcesada).toList();
            case BUCKET_VENCIDOS -> todas.stream().filter(RecibosResource::esVencida).toList();
            default -> todas;
        };
    }

    // ════════════════════════════════════════════════════════════════════
    // Reads: JSON list, stats, detail
    // ════════════════════════════════════════════════════════════════════

    /**
     * Paginated bucket list. {@code bucket} selects the legacy source query,
     * {@code q} reproduces the legacy {@code globalFilterFunction} field set.
     */
    @GET
    public Response list(
            @QueryParam("bucket") @DefaultValue(BUCKET_TODAS) String bucket,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("sort") @Nullable String sort,
            @QueryParam("dir") @DefaultValue("asc") String dir,
            @QueryParam("q") @Nullable String q) {
        try {
            String cubeta = normalizarBucket(bucket);
            List<ComprobantesEmitidos> filas = filtrar(filasDelBucket(cubeta), q);
            filas = ordenar(filas, sort, dir);
            long total = filas.size();
            int pagina = Math.max(page, 1);
            int medida = clampSize(size);
            int from = (pagina - 1) * medida;
            List<ComprobantesEmitidosListDTO> data = new ArrayList<>();
            if (from < filas.size()) {
                for (ComprobantesEmitidos f : filas.subList(from, Math.min(from + medida, filas.size()))) {
                    data.add(toListDTO(f));
                }
            }
            return Response.ok(new PagedResponse<>(data, total, pagina, medida)).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error listando los recibos", e);
            return serverError("Error listando los recibos");
        }
    }

    /**
     * Server-side bucket counts for the stats header (legacy stat cards).
     */
    @GET
    @Path("/stats")
    public Response stats() {
        try {
            return Response.ok(ApiResponse.ok(statsView())).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error calculando las estadísticas de recibos", e);
            return serverError("Error calculando las estadísticas");
        }
    }

    /**
     * Detail of one recibo (row-select panel payload). With
     * {@code HX-Request} renders the detail-panel fragment instead.
     */
    @GET
    @Path("/{id}")
    public Response detalle(@PathParam("id") long id) {
        try {
            ComprobantesEmitidos f = comprobantesEmitidosService.find(id);
            if (f == null) {
                return notFound("No se encontró el recibo solicitado");
            }
            if (isHxRequest()) {
                return htmlOk(detalle.data("vista", new DetalleView(f)));
            }
            return Response.ok(ApiResponse.ok(toDetailDTO(f))).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error obteniendo el detalle del recibo " + id, e);
            return serverError("Error obteniendo el detalle del recibo");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // HTML fragments (kit contract)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Bucket table fragment ({@code HX-Request}) — each bucket table is
     * independently swappable through its stable id. Without the header the
     * full tablero (stats + four tables) is rendered so the endpoint stays
     * bookmarkable.
     */
    @GET
    @Path("/tabla")
    @Produces(MediaType.TEXT_HTML)
    public Response tabla(
            @QueryParam("bucket") @DefaultValue(BUCKET_TODAS) String bucket,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("size") @DefaultValue("20") int size,
            @QueryParam("sort") @Nullable String sort,
            @QueryParam("dir") @DefaultValue("asc") String dir,
            @QueryParam("q") @Nullable String q) {
        try {
            String cubeta = normalizarBucket(bucket);
            if (isHxRequest()) {
                TemplateInstance instance = tabla.instance()
                        .data("modelo", tablaModel(cubeta, page, size, sort, dir, q))
                        .data("q", q);
                return htmlOk(instance);
            }
            return htmlOk(tableroInstance(null, null));
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error renderizando la tabla de recibos", e);
            return serverError("Error cargando la tabla de recibos");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Actions
    // ════════════════════════════════════════════════════════════════════

    /**
     * Mark a recibo as pagada — {@code paySelectedFactura} parity with the
     * commit-20d3cde credit-note rule enforced server-side. The settle flip
     * on this entity is {@code Encabezado.estado = ACEPTADO}.
     */
    @POST
    @Path("/{id}/pay")
    public Response pay(@PathParam("id") long id) {
        return doPay(id);
    }

    /**
     * North-star alias pinned by the F3 journey
     * (.omo/evidence/f3-journey): {@code POST /api/app/recibos/{id}/pagar}.
     */
    @POST
    @Path("/{id}/pagar")
    public Response pagar(@PathParam("id") long id) {
        return doPay(id);
    }

    private Response doPay(long id) {
        try {
            ComprobantesEmitidos f = comprobantesEmitidosService.find(id);
            if (f == null) {
                return notFound("No se encontró el recibo solicitado");
            }
            if (esNotaCredito(f)) {
                return guardFailure("Las notas de crédito no se pueden marcar como pagadas");
            }
            if (!Boolean.TRUE.equals(f.getStatus()) || esPagada(f)) {
                return guardFailure("La factura ya fue pagada.");
            }
            Encabezado enc = f.getEncabezado();
            if (enc == null) {
                return guardFailure("El recibo no tiene encabezado y no puede marcarse como pagada");
            }
            enc.setEstado("ACEPTADO");
            comprobantesEmitidosService.update(f);
                        LOG.info("Se marco la factura #" + f.getId() + " como pagada" + " | user=" + String.valueOf(currentUser()) + " | source=" + "paySelectedFactura" + " | antes=" + String.valueOf(f.toString()) + " | despues=" + String.valueOf((Object) null));
            return accionOk(id, "Se marco la factura como pagada!");
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error marcando el recibo " + id + " como pagada", e);
            return serverError("Error marcando el recibo como pagada");
        }
    }

    /**
     * Process a recibo through the Hacienda pipeline — single-row port of
     * {@code ComprobantesEmitidosController.reenviarSeleccionados}: one
     * verbatim {@link ComprobanteService#enviarComprobanteAHacienda} call;
     * all state flips and audit alerts happen inside the service (offline it
     * deterministically returns false without mutating the entity).
     */
    @POST
    @Path("/{id}/process")
    public Response process(@PathParam("id") long id) {
        try {
            ComprobantesEmitidos f = comprobantesEmitidosService.find(id);
            if (f == null) {
                return notFound("No se encontró el recibo solicitado");
            }
            if (!Boolean.TRUE.equals(f.getStatus()) || esProcesada(f)) {
                return guardFailure("La factura ya fue procesada.");
            }
            boolean enviado = comprobanteService.enviarComprobanteAHacienda(f);
            ComprobantesEmitidos actualizada = comprobantesEmitidosService.find(id);
            String estado = actualizada != null && actualizada.getEncabezado() != null
                    ? actualizada.getEncabezado().getEstado() : null;
            String haciendaEstado = actualizada != null ? actualizada.getHaciendaEstado() : null;
            return Response.ok(ApiResponse.ok(new AccionResult(enviado,
                    enviado ? "Comprobante enviado a Hacienda"
                            : "No se pudo enviar a Hacienda (quedó pendiente de reenvío)",
                    estado, haciendaEstado)))
                    .build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error procesando el recibo " + id, e);
            return serverError("Error procesando el recibo");
        }
    }

    /**
     * Accept a recibo (Mensaje-Receptor resolution recorded on
     * {@code Encabezado.estado}). Unlike pay, credit notes CAN be accepted.
     */
    @POST
    @Path("/{id}/accept")
    public Response accept(@PathParam("id") long id) {
        try {
            ComprobantesEmitidos f = comprobantesEmitidosService.find(id);
            if (f == null) {
                return notFound("No se encontró el recibo solicitado");
            }
            Encabezado enc = f.getEncabezado();
            if (enc == null) {
                return guardFailure("El recibo no tiene encabezado y no puede aceptarse");
            }
            String antes = DiffUtils.snapshotEntity(f);
            enc.setEstado("ACEPTADO");
            comprobantesEmitidosService.update(f);
                        LOG.info("Se acepto la factura #" + f.getId() + " (Mensaje Receptor)" + " | user=" + String.valueOf(currentUser()) + " | source=" + "RecibosResource.accept" + " | antes=" + String.valueOf(antes) + " | despues=" + String.valueOf(DiffUtils.snapshotEntity(f)));
            return accionOk(id, "Factura aceptada");
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error aceptando el recibo " + id, e);
            return serverError("Error aceptando el recibo");
        }
    }

    /** Form twin of {@link #rejectJson(long, RejectRequest)}. */
    @POST
    @Path("/{id}/reject")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response rejectForm(@PathParam("id") long id,
                               @FormParam("motivo") @Nullable String motivo) {
        return doReject(id, motivo);
    }

    /** JSON twin of {@link #rejectForm(long, String)}. */
    @POST
    @Path("/{id}/reject")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response rejectJson(@PathParam("id") long id, @Nullable RejectRequest cuerpo) {
        return doReject(id, cuerpo == null ? null : cuerpo.motivo());
    }

    private Response doReject(long id, @Nullable String motivo) {
        try {
            ComprobantesEmitidos f = comprobantesEmitidosService.find(id);
            if (f == null) {
                return notFound("No se encontró el recibo solicitado");
            }
            String motivoLimpio = motivo == null || motivo.isBlank() ? null : motivo.trim();
            if (motivoLimpio != null && motivoLimpio.length() > 500) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(ApiResponse.error("VALIDATION_ERROR",
                                "El motivo de rechazo no puede exceder 500 caracteres"))
                        .build();
            }
            Encabezado enc = f.getEncabezado();
            if (enc == null) {
                return guardFailure("El recibo no tiene encabezado y no puede rechazarse");
            }
            String antes = DiffUtils.snapshotEntity(f);
            enc.setEstado("RECHAZADO");
            enc.setMotivoRechazo(motivoLimpio);
            comprobantesEmitidosService.update(f);
                        LOG.info("Se rechazo la factura #" + f.getId()
                            + (motivoLimpio != null ? " Motivo: " + motivoLimpio : "") + " | user=" + String.valueOf(currentUser()) + " | source=" + "RecibosResource.reject" + " | antes=" + String.valueOf(antes) + " | despues=" + String.valueOf(DiffUtils.snapshotEntity(f)));
            return accionOk(id, "Factura rechazada");
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error rechazando el recibo " + id, e);
            return serverError("Error rechazando el recibo");
        }
    }

    /**
     * Verbatim port of {@code ComprobantesEmitidosController.deleteFactura}:
     * snapshot → softDelete → same audit strings, error path included.
     */
    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") long id) {
        try {
            ComprobantesEmitidos f = comprobantesEmitidosService.find(id);
            if (f == null) {
                return notFound("No se encontró el recibo solicitado");
            }
            String antes = DiffUtils.snapshotEntity(f);
            comprobantesEmitidosService.softDelete(f);
            ComprobantesEmitidos despues = comprobantesEmitidosService.find(id);
                        LOG.info("La factura ha sido eliminada correctamente." + " | user=" + String.valueOf(currentUser()) + " | source=" + "ComprobantesEmitidosController.deleteFactura" + " | antes=" + String.valueOf(antes) + " | despues=" + String.valueOf(despues != null ? DiffUtils.snapshotEntity(despues) : null));
            return accionOk(id, "La factura ha sido eliminada correctamente.");
        } catch (RuntimeException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error al eliminar la factura." + " | user=" + String.valueOf(currentUser()) + " | source=" + "ComprobantesEmitidosController.deleteFactura" + " | antes=" + String.valueOf(String.valueOf(id)) + " | despues=" + String.valueOf(e.getMessage()));
            LOG.log(Level.WARNING, "Error eliminando el recibo " + id, e);
            return serverError("Error al eliminar la factura.");
        }
    }

    /**
     * Verbatim port of {@code ComprobantesEmitidosController.toggleFactura}:
     * snapshot → toggle → same audit strings, error path included.
     */
    @POST
    @Path("/{id}/toggle")
    public Response toggle(@PathParam("id") long id) {
        try {
            ComprobantesEmitidos f = comprobantesEmitidosService.find(id);
            if (f == null) {
                return notFound("No se encontró el recibo solicitado");
            }
            String antes = DiffUtils.snapshotEntity(f);
            comprobantesEmitidosService.toggle(f);
            ComprobantesEmitidos despues = comprobantesEmitidosService.find(id);
                        LOG.info("El estado de la factura ha sido cambiado correctamente." + " | user=" + String.valueOf(currentUser()) + " | source=" + "ComprobantesEmitidosController.toggleFactura" + " | antes=" + String.valueOf(antes) + " | despues=" + String.valueOf(despues != null ? DiffUtils.snapshotEntity(despues) : null));
            return accionOk(id, "El estado de la factura ha sido cambiado correctamente.");
        } catch (RuntimeException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error al cambiar el estado de la factura." + " | user=" + String.valueOf(currentUser()) + " | source=" + "ComprobantesEmitidosController.toggleFactura" + " | antes=" + String.valueOf(String.valueOf(id)) + " | despues=" + String.valueOf(e.getMessage()));
            LOG.log(Level.WARNING, "Error cambiando el estado del recibo " + id, e);
            return serverError("Error al cambiar el estado de la factura.");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Shared model builders (also consumed by RecibosPagesResource)
    // ════════════════════════════════════════════════════════════════════

    /** Kit data-table model for ONE bucket (docs/ui-kit.md §3.1 contract). */
    public @Nonnull Map<String, Object> tablaModel(@Nonnull String bucket, int page, int size,
                                                   @Nullable String sort, @Nullable String dir,
                                                   @Nullable String q) {
        List<ComprobantesEmitidos> filas = ordenar(filtrar(filasDelBucket(bucket), q), sort, dir);
        long total = filas.size();
        int pagina = Math.max(page, 1);
        int medida = clampSize(size);
        int totalPaginas = totalPages(total, medida);
        int from = (pagina - 1) * medida;
        List<FilaRecibo> datos = new ArrayList<>();
        if (from < filas.size()) {
            for (ComprobantesEmitidos f : filas.subList(from, Math.min(from + medida, filas.size()))) {
                datos.add(new FilaRecibo(f));
            }
        }
        List<Map<String, Object>> columnas = List.of(
                ReportePageSupport.columna("Consecutivo", "consecutivo"),
                ReportePageSupport.columna("Fecha", "fechaEmision"),
                ReportePageSupport.columna("Emisor", "emisorNombre"),
                ReportePageSupport.columna("Total", "totalComprobante"),
                ReportePageSupport.columna("Impuesto", "totalImpuesto"),
                ReportePageSupport.columna("Estado", null),
                ReportePageSupport.columna("Acciones", null));
        Map<String, Object> modelo = new LinkedHashMap<>();
        modelo.put("id", "recibos-tabla-" + bucket);
        modelo.put("bucket", bucket);
        modelo.put("baseUrl", "/api/app/recibos/tabla");
        modelo.put("columnas", columnas);
        modelo.put("filas", datos);
        modelo.put("sortKey", sort == null ? "" : sort);
        modelo.put("sortDir", ReportePageSupport.isDescending(dir) ? "desc" : "asc");
        modelo.put("page", pagina);
        modelo.put("size", medida);
        modelo.put("total", total);
        modelo.put("totalPages", totalPaginas);
        modelo.put("paginas", ReportePageSupport.pageWindow(pagina, totalPaginas));
        modelo.put("filtros", ReportePageSupport.params("bucket", bucket, "q", q));
        return modelo;
    }

    /** Stats header view (server-computed bucket counts). */
    public @Nonnull StatsView statsView() {
        List<ComprobantesEmitidos> todas = orEmpty(comprobantesEmitidosService.listAll());
        long pagadas = todas.stream().filter(RecibosResource::esPagada).count();
        long procesadas = todas.stream().filter(RecibosResource::esProcesada).count();
        long vencidos = todas.stream().filter(RecibosResource::esVencida).count();
        return new StatsView(todas.size(), pagadas, procesadas, vencidos);
    }

    /** Fresh tablero instance (stats + four tables [+ OOB toast keys]). */
    public @Nonnull TemplateInstance tableroInstance(@Nullable String toastSeverity,
                                                    @Nullable String toastMessage) {
        Map<String, Object> modelos = new LinkedHashMap<>();
        for (String bucket : List.of(BUCKET_TODAS, BUCKET_PAGADAS, BUCKET_PROCESADAS, BUCKET_VENCIDOS)) {
            modelos.put(bucket, tablaModel(bucket, 1, clampSize(null), null, "asc", null));
        }
        TemplateInstance instance = tablero.instance()
                .data("stats", statsView())
                .data("modelos", modelos);
        if (toastSeverity != null && toastMessage != null) {
            instance.data("toastSeverity", toastSeverity).data("toastMessage", toastMessage);
        }
        return instance;
    }

    // ════════════════════════════════════════════════════════════════════
    // Filtering / sorting (legacy globalFilterFunction field set)
    // ════════════════════════════════════════════════════════════════════

    private static @Nonnull String normalizarBucket(@Nullable String bucket) {
        if (bucket == null || bucket.isBlank()) {
            return BUCKET_TODAS;
        }
        return switch (bucket.trim().toLowerCase(Locale.ROOT)) {
            case BUCKET_PAGADAS -> BUCKET_PAGADAS;
            case BUCKET_PROCESADAS -> BUCKET_PROCESADAS;
            case BUCKET_VENCIDOS -> BUCKET_VENCIDOS;
            default -> BUCKET_TODAS;
        };
    }

    /** Legacy globalFilterFunction fields, case-insensitive contains. */
    private static @Nonnull List<ComprobantesEmitidos> filtrar(@Nonnull List<ComprobantesEmitidos> filas,
                                                               @Nullable String q) {
        String texto = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        if (texto.isEmpty()) {
            return filas;
        }
        List<ComprobantesEmitidos> salida = new ArrayList<>();
        for (ComprobantesEmitidos f : filas) {
            Encabezado enc = f.getEncabezado();
            Emisor emisor = enc == null ? null : enc.getEmisor();
            if (contiene(enc == null ? null : enc.getCodigoActividadEmisor(), texto)
                    || contiene(enc == null ? null : enc.getCondicionVenta(), texto)
                    || contiene(emisor == null ? null : emisor.getNombre(), texto)
                    || contieneCorreoEmisor(emisor == null ? null : emisor.getCorreosElectronicos(), texto)
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
        return fuente != null && fuente.toLowerCase(Locale.ROOT).contains(filtro);
    }

    private static boolean contieneCorreoEmisor(@Nullable List<CorreoElectronicoEmisor> correos,
                                                @Nonnull String filtro) {
        if (correos == null) {
            return false;
        }
        return correos.stream()
                .map(CorreoElectronicoEmisor::getCorreo)
                .filter(Objects::nonNull)
                .anyMatch(correo -> correo.toLowerCase(Locale.ROOT).contains(filtro));
    }

    /** Whitelisted sort keys (kit golden rule #5: reserved keys excluded). */
    private static @Nonnull List<ComprobantesEmitidos> ordenar(@Nonnull List<ComprobantesEmitidos> filas,
                                @Nullable String sort, @Nullable String dir) {
        // callers may hand immutable lists (stream().toList()); sort a copy
        List<ComprobantesEmitidos> ordenada = new ArrayList<>(filas);
        Comparator<ComprobantesEmitidos> base;
        if ("consecutivo".equals(sort)) {
            base = Comparator.comparing(RecibosResource::consecutivoDe,
                    Comparator.nullsFirst(Comparator.naturalOrder()));
        } else if ("fechaEmision".equals(sort)) {
            base = Comparator.comparing(RecibosResource::fechaDe,
                    Comparator.nullsFirst(Comparator.naturalOrder()));
        } else if ("emisorNombre".equals(sort)) {
            base = Comparator.comparing(RecibosResource::emisorDe,
                    Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER));
        } else if ("totalComprobante".equals(sort)) {
            base = Comparator.comparing(RecibosResource::totalDe,
                    Comparator.nullsFirst(Comparator.naturalOrder()));
        } else if ("totalImpuesto".equals(sort)) {
            base = Comparator.comparing(RecibosResource::impuestoDe,
                    Comparator.nullsFirst(Comparator.naturalOrder()));
        } else if ("haciendaEstado".equals(sort)) {
            base = Comparator.comparing(ComprobantesEmitidos::getHaciendaEstado,
                    Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER));
        } else {
            base = Comparator.comparing(ComprobantesEmitidos::getId,
                    Comparator.nullsFirst(Comparator.naturalOrder()));
        }
        ordenada.sort(ReportePageSupport.isDescending(dir) ? base.reversed() : base);
        return ordenada;
    }

    private static @Nullable String consecutivoDe(@Nonnull ComprobantesEmitidos f) {
        return f.getEncabezado() != null ? f.getEncabezado().getNumeroConsecutivo() : null;
    }

    private static @Nullable java.time.LocalDateTime fechaDe(@Nonnull ComprobantesEmitidos f) {
        return f.getEncabezado() != null ? f.getEncabezado().getFechaEmision() : null;
    }

    private static @Nullable String emisorDe(@Nonnull ComprobantesEmitidos f) {
        return f.getEncabezado() != null && f.getEncabezado().getEmisor() != null
                ? f.getEncabezado().getEmisor().getNombre() : null;
    }

    private static @Nullable BigDecimal totalDe(@Nonnull ComprobantesEmitidos f) {
        return f.getResumen() != null ? f.getResumen().getTotalComprobante() : null;
    }

    private static @Nullable BigDecimal impuestoDe(@Nonnull ComprobantesEmitidos f) {
        return f.getResumen() != null ? f.getResumen().getTotalImpuesto() : null;
    }

    // ════════════════════════════════════════════════════════════════════
    // Mappers (manual, codebase convention)
    // ════════════════════════════════════════════════════════════════════

    private static @Nonnull ComprobantesEmitidosListDTO toListDTO(@Nonnull ComprobantesEmitidos f) {
        Encabezado enc = f.getEncabezado();
        Receptor receptor = enc == null ? null : enc.getReceptor();
        return new ComprobantesEmitidosListDTO(
                f.getId(),
                enc != null ? enc.getNumeroConsecutivo() : null,
                enc != null ? enc.getFechaEmision() : null,
                enc != null && enc.getEmisor() != null ? enc.getEmisor().getNombre() : null,
                receptor != null ? receptor.getNombre() : null,
                f.getResumen() != null ? f.getResumen().getTotalComprobante() : null,
                f.getResumen() != null ? f.getResumen().getTotalImpuesto() : null,
                enc != null ? enc.getCondicionVenta() : null,
                enc != null ? enc.getPlazoCredito() : null,
                enc != null ? enc.getCodigoDocumento() : null,
                f.getHaciendaEstado(),
                f.getStatus());
    }

    private static @Nonnull ComprobantesEmitidosDetailDTO toDetailDTO(@Nonnull ComprobantesEmitidos f) {
        Encabezado enc = f.getEncabezado();
        Emisor emisor = enc != null ? enc.getEmisor() : null;
        Receptor receptor = enc != null ? enc.getReceptor() : null;

        ComprobantesEmitidosDetailDTO dto = new ComprobantesEmitidosDetailDTO();
        dto.setId(f.getId());
        dto.setClave(enc != null ? enc.getClave() : null);
        dto.setHaciendaClave(f.getHaciendaClave());
        dto.setHaciendaEstado(f.getHaciendaEstado());
        dto.setHaciendaFechaEnvio(f.getHaciendaFechaEnvio());
        dto.setHaciendaFechaRespuesta(f.getHaciendaFechaRespuesta());
        dto.setCorrectionAttempts(f.getCorrectionAttempts());
        dto.setUltimaCorreccion(f.getUltimaCorreccion());
        dto.setStatus(f.getStatus());
        dto.setUser(f.getUser());
        dto.setProveedorSistemas(enc != null ? enc.getProveedorSistemas() : null);
        dto.setCodigoActividadEmisor(enc != null ? enc.getCodigoActividadEmisor() : null);
        dto.setCodigoActividadReceptor(enc != null ? enc.getCodigoActividadReceptor() : null);
        dto.setConsecutivo(enc != null ? enc.getNumeroConsecutivo() : null);
        dto.setFechaEmision(enc != null ? enc.getFechaEmision() : null);
        dto.setCondicionVenta(enc != null ? enc.getCondicionVenta() : null);
        dto.setCondicionVentaOtros(enc != null ? enc.getCondicionVentaOtros() : null);
        dto.setPlazoCredito(enc != null ? enc.getPlazoCredito() : null);
        dto.setCodigoDocumento(enc != null ? enc.getCodigoDocumento() : null);
        dto.setEstado(enc != null ? enc.getEstado() : null);
        dto.setMotivoRechazo(enc != null ? enc.getMotivoRechazo() : null);
        if (emisor != null) {
            dto.setEmisorNombre(emisor.getNombre());
            dto.setEmisorNombreComercial(emisor.getNombreComercial());
            dto.setEmisorTipoIdentificacion(emisor.getIdentificacion() != null
                    ? emisor.getIdentificacion().getTipo() : null);
            dto.setEmisorNumeroIdentificacion(emisor.getIdentificacion() != null
                    ? emisor.getIdentificacion().getNumero() : null);
            dto.setEmisorCorreosElectronicos(correosEmisor(emisor.getCorreosElectronicos()));
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
        return dto;
    }

    private static @Nullable List<String> correosEmisor(@Nullable List<CorreoElectronicoEmisor> correos) {
        if (correos == null) {
            return null;
        }
        return correos.stream()
                .map(CorreoElectronicoEmisor::getCorreo)
                .filter(Objects::nonNull)
                .toList();
    }

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
    // Response helpers
    // ════════════════════════════════════════════════════════════════════

    /** Success payload for JSON callers. */
    private Response accionOk(long id, @Nonnull String mensaje) {
        ComprobantesEmitidos f = comprobantesEmitidosService.find(id);
        String estado = f != null && f.getEncabezado() != null ? f.getEncabezado().getEstado() : null;
        String haciendaEstado = f != null ? f.getHaciendaEstado() : null;
        if (isHxRequest()) {
            return htmlOk(tableroInstance("success", mensaje));
        }
        return Response.ok(ApiResponse.ok(new AccionResult(true, mensaje, estado, haciendaEstado)))
                .build();
    }

    /**
     * Guard failure: JSON gets a 409 envelope; HTMX gets a 200 tablero swap
     * with a danger toast (htmx does not swap 4xx bodies by default).
     */
    private Response guardFailure(@Nonnull String mensaje) {
        if (isHxRequest()) {
            return htmlOk(tableroInstance("error", mensaje));
        }
        return Response.status(Response.Status.CONFLICT)
                .entity(ApiResponse.error("BUSINESS_RULE", mensaje))
                .build();
    }

    private static @Nonnull Response htmlOk(@Nonnull TemplateInstance template) {
        return Response.ok(template.render())
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).build();
    }

    private static @Nonnull Response notFound(@Nonnull String mensaje) {
        return Response.status(Response.Status.NOT_FOUND)
                .entity(ApiResponse.error("NOT_FOUND", mensaje)).build();
    }

    private static @Nonnull Response serverError(@Nonnull String mensaje) {
        return Response.serverError()
                .entity(ApiResponse.error("INTERNAL_ERROR", mensaje)).build();
    }

    private boolean isHxRequest() {
        return ReportePageSupport.isHxRequest(httpHeaders);
    }

    /**
     * Resolves the authenticated {@link Models.Users} row through the T12
     * identity provider's principal; null for anonymous/system contexts
     * (alertas accepts null, mirroring the legacy null-session branches).
     */
    private @Nullable Models.Users currentUser() {
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

    private static int clampSize(@Nullable Integer size) {
        if (size == null || size < 1) {
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

    private static @Nonnull List<ComprobantesEmitidos> orEmpty(@Nullable List<ComprobantesEmitidos> lista) {
        return lista == null ? java.util.Collections.emptyList() : lista;
    }

    // ════════════════════════════════════════════════════════════════════
    // Small value carriers
    // ════════════════════════════════════════════════════════════════════

    /** Reject request body (JSON twin of the form field {@code motivo}). */
    public record RejectRequest(String motivo) {}

    /** Outcome of an action (JSON payload). */
    public record AccionResult(boolean success, String mensaje, String estado,
                               String haciendaEstado) {}

    /** Server-computed bucket counts for the stats header. */
    public record StatsView(long todas, long pagadas, long procesadas, long vencidos) {}

    /**
     * List row handed to templates: the pre-built ListDTO plus the computed
     * bucket flags (the DTO intentionally carries only persisted scalars, so
     * the wrapper keeps it untouched while letting the template render chips
     * and enable/disable buttons exactly like the legacy rendered attrs).
     */
    public record FilaRecibo(ComprobantesEmitidosListDTO dto, boolean pagada, boolean procesada,
                             boolean vencida, boolean notaCredito, boolean activa) {
        public FilaRecibo(@Nonnull ComprobantesEmitidos f) {
            this(toListDTO(f), esPagada(f), esProcesada(f), esVencida(f),
                    esNotaCredito(f), Boolean.TRUE.equals(f.getStatus()));
        }
    }

    /** Detail row handed to the panel fragment (DTO + computed flags). */
    public record DetalleView(ComprobantesEmitidosDetailDTO detalle, boolean pagada,
                              boolean procesada, boolean vencida, boolean notaCredito,
                              boolean activa) {
        public DetalleView(@Nonnull ComprobantesEmitidos f) {
            this(toDetailDTO(f), esPagada(f), esProcesada(f), esVencida(f),
                    esNotaCredito(f), Boolean.TRUE.equals(f.getStatus()));
        }
    }
}
