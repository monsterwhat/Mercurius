package Controllers.Api.App;

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
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

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

    private static final Logger LOG = Logger.getLogger(DevolucionesPagesResource.class.getName());

    /** Legacy p:dataTable rows=10 on search results and historial. */
    private static final int PAGE_SIZE = 10;

    @Inject
    @Nonnull
    ComprobantesEmitidosService emitidosService;

    @Inject
    @Nonnull
    NotaCreditoService notaCreditoService;

    @Inject
    @Nonnull
    Services.ClientService clientService;

    @Inject
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
            LOG.log(Level.WARNING, "Error renderizando la página de devoluciones", e);
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
            // Same filter semantics as the JSON twin (legacy buscarFactura);
            // paged here for the kit table footer.
            List<Map<String, Object>> todas = buscar(tipo, q.trim());
            total = todas.size();
            totalPages = (int) Math.max(1L, (total + PAGE_SIZE - 1) / PAGE_SIZE);
            safePage = Math.min(safePage, totalPages);
            int from = Math.min((safePage - 1) * PAGE_SIZE, todas.size());
            int to = Math.min(from + PAGE_SIZE, todas.size());
            filas.addAll(todas.subList(from, to));
        }

        List<Integer> pages = new ArrayList<>();
        for (int p = 1; p <= totalPages; p++) {
            pages.add(p);
        }

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
        model.put("historialNotas", historialRows());
        model.put("headersHistorial", List.of(
                Map.of("label", "Fecha"),
                Map.of("label", "Factura Original"),
                Map.of("label", "Motivo"),
                Map.of("label", "Monto"),
                Map.of("label", "Estado Hacienda")));
        return model;
    }

    /** Legacy buscarFactura() filtering — kept verbatim (see API twin). */
    private List<Map<String, Object>> buscar(@Nonnull String tipo, @Nonnull String criterio) {
        List<ComprobantesEmitidos> source = new ArrayList<>();
        if ("cliente".equals(tipo)) {
            List<Models.Clients> clients = clientService.searchByName(criterio);
            if (clients == null || clients.isEmpty()) {
                return Collections.emptyList();
            }
            String needle = criterio.toLowerCase(Locale.ROOT);
            for (ComprobantesEmitidos f : orEmpty(emitidosService.listAll())) {
                Encabezado enc = f.getEncabezado();
                if (enc == null || enc.getReceptor() == null || enc.getReceptor().getNombre() == null) {
                    continue;
                }
                if (needle.contains(enc.getReceptor().getNombre().toLowerCase(Locale.ROOT))) {
                    source.add(f);
                }
            }
        } else {
            for (ComprobantesEmitidos f : orEmpty(emitidosService.listAll())) {
                Encabezado enc = f.getEncabezado();
                if (enc != null && enc.getNumeroConsecutivo() != null
                        && enc.getNumeroConsecutivo().contains(criterio)) {
                    source.add(f);
                }
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>(source.size());
        for (ComprobantesEmitidos f : source) {
            Encabezado enc = f.getEncabezado();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", f.getId());
            row.put("consecutivo", enc != null ? enc.getNumeroConsecutivo() : null);
            row.put("fechaEmision", enc != null ? enc.getFechaEmision() : null);
            row.put("cliente", enc != null && enc.getReceptor() != null ? enc.getReceptor().getNombre() : null);
            row.put("total", f.getResumen() != null ? f.getResumen().getTotalComprobante() : null);
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> historialRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (NotaCredito nota : orEmpty(notaCreditoService.listAll())) {
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

    private static <T> List<T> orEmpty(@Nullable List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }
}
