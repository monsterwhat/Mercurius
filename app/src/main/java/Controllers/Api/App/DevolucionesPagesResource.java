package Controllers.Api.App;

import static Controllers.Api.App.AppRecursoBase.*;
import Models.ComprobantesEmitidos;
import Models.Encabezado.Encabezado;
import Models.NotaCredito;
import Services.ComprobantesEmitidosService;
import Services.NotaCreditoService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jboss.logging.Logger;

/**
 * HTML page of the Devoluciones module for the NEW Qute/HTMX app surface
 * (plan task T32): {@code GET /app/devoluciones} — the route the T11 navbar
 * reserved for the legacy secured/pages/Devoluciones/index.xhtml (deleted by
 * this task together with its JSF bean).
 *
 * <p>Server contract follows docs/ui-kit.md §2.9: with the {@code HX-Request}
 * header (the Buscar button) ONLY the {@code resultados} fragment is rendered
 * into {@code #resultados}; otherwise the whole layout page. Line selection,
 * live totals and the authorize modal are served by the JSON twin
 * {@link DevolucionesResource} ({@code /api/app/devoluciones/{id}/lineas},
 * {@code /authform}, {@code /initiate}, {@code /{id}/authorize}).</p>
 *
 * <p><b>Role gate</b>: {@code admin} + {@code facturacion}, mirroring the
 * legacy page's availability to the facturación area. Read-only rendering —
 * no mutation happens here.</p>
 */
@Path("/app/devoluciones")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"admin", "facturacion"})
public class DevolucionesPagesResource {

    private static final Logger LOG = Logger.getLogger(DevolucionesPagesResource.class);

    /** Legacy p:dataTable rows=10 on search results and historial. */
    private static final int PAGE_SIZE = 10;

    /**
     * Filas del historial de notas de credito.
     *
     * <p>Espeja {@code DevolucionesResource.HISTORIAL_OOB_LIMIT}: el
     * historial no tiene paginador y el refresco OOB posterior a una
     * devolucion ya pinta solo las mas recientes. La pagina inicial usa el
     * mismo tope para que ambas vistas coincidan.</p>
     */
    private static final int HISTORIAL_LIMIT = 20;

    @Inject
    @Nonnull
    ComprobantesEmitidosService emitidosService;

    @Inject
    @Nonnull
    NotaCreditoService notaCreditoService;

    @Inject
    @Nonnull
    Services.ClientService clientService;

    @Context
    @Nonnull
    HttpHeaders httpHeaders;

    @Inject
    @Nonnull
    @Location("pages/devoluciones/index")
    Template devolucionesPage;

    /**
     * Devoluciones page; with {@code HX-Request} returns only the
     * {@code resultados} fragment (the Buscar button swaps it).
     */
    @GET
    public Response index(
            @QueryParam("tipo") @DefaultValue("consecutivo") String tipo,
            @QueryParam("q") @Nullable String q,
            @QueryParam("page") @DefaultValue("1") int page) {
        try {
            // Qute 3.36: getFragment returns Template.Fragment (extends
            // Template), so .instance() yields the renderable TemplateInstance.
            TemplateInstance instance = isHxRequest()
                    ? devolucionesPage.getFragment("resultados").instance()
                    : devolucionesPage.instance();
            model(tipo, q, page).forEach(instance::data);
            return Response.ok(instance.render())
                    .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).build();
        } catch (RuntimeException e) {
            LOG.warn("Error renderizando la página de devoluciones", e);
            return Response.serverError()
                    .entity(Models.DTO.ApiResponse.error("INTERNAL_ERROR",
                            "No se pudieron cargar las devoluciones"))
                    .build();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Page model
    // ════════════════════════════════════════════════════════════════════

    private Map<String, Object> model(@Nonnull String tipo, @Nullable String q, int page) {
        List<Map<String, Object>> filas = new ArrayList<>();
        long total = 0;
        int totalPages = 1;
        int safePage = Math.max(page, 1);
        boolean busquedaActiva = q != null && !q.trim().isEmpty();

        if (busquedaActiva) {
            // Same filter semantics as the JSON twin (legacy buscarFactura),
            // now counted and paged in SQL. The legacy path materialized the
            // whole emitted-invoice history (211k rows) and paginated it in
            // memory: every Buscar paid a full scan plus full hydration.
            String criterio = q.trim();
            total = buscarTotal(tipo, criterio);
            totalPages = (int) Math.max(1L, (total + PAGE_SIZE - 1) / PAGE_SIZE);
            safePage = Math.min(safePage, totalPages);
            if (total > 0) {
                int offset = (safePage - 1) * PAGE_SIZE;
                for (ComprobantesEmitidos f : buscarPagina(tipo, criterio, offset, PAGE_SIZE)) {
                    filas.add(filaMap(f));
                }
            }
        }

        // Ventana del paginador (page ± 2), como el resto de las tablas del
        // kit: con el total exacto en SQL una busqueda amplia puede dar miles
        // de paginas y el pager no debe emitir un enlace por cada una.
        List<Integer> pages = pageWindow(safePage, totalPages);

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("titulo", "Devoluciones y Notas de Credito");
        model.put("tipo", tipo);
        model.put("q", q == null ? "" : q);
        model.put("busquedaActiva", busquedaActiva);
        model.put("baseUrl", "/app/devoluciones");
        model.put("resultados", filas);
        model.put("total", total);
        model.put("page", safePage);
        model.put("size", PAGE_SIZE);
        model.put("totalPages", totalPages);
        model.put("pages", pages);
        model.put("headersResultados", List.of(
                Map.of("label", "Número Consecutivo"),
                Map.of("label", "Fecha"),
                Map.of("label", "Cliente"),
                Map.of("label", "Total"),
                Map.of("label", "Acción")));
        // El fragmento de resultados (HX-Request) no incluye el historial: no
        // se consulta al Buscar; solo la pagina completa lo pinta.
        model.put("historialNotas", isHxRequest() ? List.of() : historialRows());
        model.put("headersHistorial", List.of(
                Map.of("label", "Fecha"),
                Map.of("label", "Factura Original"),
                Map.of("label", "Motivo"),
                Map.of("label", "Monto"),
                Map.of("label", "Estado Hacienda")));
        return model;
    }

    /**
     * Legacy {@code buscarFactura()} total, resuelto en SQL.
     *
     * <p>Cliente mode keeps the legacy guard: without a client matching the
     * criterion there are no results, so the invoice count is never asked for.
     * Consecutivo mode delegates to the same {@code LIKE} predicate as the
     * page query, so footer total and rows always describe one result set.</p>
     */
    private long buscarTotal(@Nonnull String tipo, @Nonnull String criterio) {
        if ("cliente".equals(tipo)) {
            List<Models.Clientes> clients = clientService.searchByName(criterio);
            if (clients == null || clients.isEmpty()) {
                return 0L;
            }
            return emitidosService.countByClientNameLike(criterio);
        }
        return emitidosService.countByIdContainingConsecutivo(criterio);
    }

    /** One SQL page of the legacy {@code buscarFactura()} result set. */
    private @Nonnull List<ComprobantesEmitidos> buscarPagina(
            @Nonnull String tipo, @Nonnull String criterio, int offset, int max) {
        if ("cliente".equals(tipo)) {
            return orEmpty(emitidosService.findByClientNameLike(criterio, offset, max));
        }
        return orEmpty(emitidosService.findByIdContainingConsecutivo(criterio, offset, max));
    }

    /** Table row of one invoice (same columns the template renders). */
    private static @Nonnull Map<String, Object> filaMap(@Nonnull ComprobantesEmitidos f) {
        Encabezado enc = f.getEncabezado();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", f.getId());
        row.put("consecutivo", enc != null ? enc.getNumeroConsecutivo() : null);
        row.put("fechaEmision", enc != null ? enc.getFechaEmision() : null);
        row.put("cliente", enc != null && enc.getReceptor() != null ? enc.getReceptor().getNombre() : null);
        row.put("total", f.getResumen() != null ? f.getResumen().getTotalComprobante() : null);
        return row;
    }

    private List<Map<String, Object>> historialRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        // Mismo tope que el refresco OOB del twin JSON: el historial no tiene
        // techo y materializarlo entero (50k notas, una consulta lazy por
        // fila) era el otro costo fijo de la pagina.
        for (NotaCredito nota : orEmpty(notaCreditoService.listRecent(HISTORIAL_LIMIT))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("fecha", nota.getFecha());
            row.put("facturaOriginal",
                    nota.getComprobanteOriginal() != null
                            && nota.getComprobanteOriginal().getEncabezado() != null
                            ? nota.getComprobanteOriginal().getEncabezado().getNumeroConsecutivo()
                            : null);
            row.put("motivo", nota.getMotivo());
            row.put("montoTotal", nota.getMontoTotal());
            row.put("haciendaEstado", nota.getHaciendaEstado());
            rows.add(row);
        }
        return rows;
    }

    private boolean isHxRequest() {
        String header = httpHeaders.getHeaderString("HX-Request");
        return header != null && !"false".equalsIgnoreCase(header);
    }
}
