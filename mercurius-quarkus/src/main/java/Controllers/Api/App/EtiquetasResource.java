package Controllers.Api.App;

import Models.Articulos.Articulos;
import Models.DTO.ApiResponse;
import Models.DTO.EtiquetaDTO;
import Models.DTO.PagedResponse;
import Services.ArticulosService;
import Utils.ReportExporter;
import com.lowagie.text.DocumentException;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
 * Price-label generation for the JSON API surface — port of the legacy
 * {@code secured/pages/Inventario/Reportes/Etiquetas/index.xhtml} flow
 * ({@code EtiquetasController.getFilteredArticulos()} listing + print).
 *
 * <p><b>Behavior parity contract:</b></p>
 * <ul>
 *   <li>The printable-articles list delegates DIRECTLY to
 *       {@link ArticulosService#ListAllEnabled()} — the same listing the
 *       legacy page showed — with the legacy globalFilterFunction parity
 *       filter (codigo/nombre/codigoBarra contains, case-insensitive).</li>
 *   <li>Label generation produces its bytes through
 *       {@link ReportExporter#exportEtiquetasPdf}, which carries the label
 *       field set of the legacy table (codigo, nombre, codigo de barra,
 *       precio final). The legacy print action itself was a client-side
 *       {@code window.print()} over that table (no server-side PDF existed),
 *       so this endpoint is the canonical byte producer for API clients.</li>
 *   <li>Article resolution scans {@link ArticulosService#ListAllEnabled()} by
 *       {@code codigo} (Long equality) — only enabled articles are
 *       printable, exactly like the legacy selection list.</li>
 * </ul>
 */
@Path("/api/app/etiquetas")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"admin", "inventario"})
@Tag(name = "App - Reportes")
public class EtiquetasResource {

    private static final Logger LOG = Logger.getLogger(EtiquetasResource.class.getName());

    /** {dataset}-{yyyyMMdd HH:mm}.ext, quoted in Content-Disposition (ExportResource parity). */
    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd HH:mm").withLocale(Locale.ROOT);

    @Inject
    @Nonnull
    ArticulosService articulosService;

    /**
     * Printable-articles list (legacy getFilteredArticulos parity), paginated.
     */
    @GET
    @Transactional
    @Operation(summary = "List printable articles (legacy getFilteredArticulos parity)")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Paginated printable articles"),
        @APIResponse(responseCode = "401", description = "Not authenticated"),
        @APIResponse(responseCode = "403", description = "Role not allowed"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response articulos(
            @QueryParam("page") @DefaultValue("1") @Parameter(description = "Page number (1-based)") int page,
            @QueryParam("size") @DefaultValue("20") @Parameter(description = "Page size") int size,
            @QueryParam("sort") @Nullable @Parameter(description = "Sort key: codigo|nombre|precio|familia|departamento") String sort,
            @QueryParam("dir") @DefaultValue("asc") @Parameter(description = "Sort direction: asc|desc") String dir,
            @QueryParam("q") @Nullable @Parameter(description = "Global filter text (codigo/nombre/codigoBarra)") String q) {
        try {
            List<Articulos> articulos = orEmpty(articulosService.ListAllEnabled());
            List<Articulos> filtered = filtrarArticulos(articulos, q);
            ordenarArticulos(filtered, sort, dir);
            long total = filtered.size();
            Window w = windowOf(total, page, size);
            List<EtiquetaDTO> data = new ArrayList<>();
            for (Articulos articulo : filtered.subList(w.from(), w.to())) {
                data.add(toDTO(articulo, 1));
            }
            return Response.ok(new PagedResponse<>(data, total, w.page(), w.size())).build();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error listando los artículos imprimibles", e);
            return serverError("Error listando los artículos imprimibles");
        }
    }

    /**
     * Generates price-label PDF bytes for an article selection. One label per
     * unit ({@code cantidad} per article; null/absent cantidad = 1, the
     * legacy single-pass behavior). Streams octet-stream as an attachment.
     */
    @POST
    @Path("/generar")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Operation(summary = "Generate price-label PDF for an article selection")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "PDF bytes streamed as octet-stream attachment"),
        @APIResponse(responseCode = "400", description = "Empty selection or invalid quantities"),
        @APIResponse(responseCode = "404", description = "Unknown articuloCodigo"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response generar(@Nullable GeneracionRequest body) {
        if (body == null || body.articulos() == null || body.articulos().isEmpty()) {
            return badRequest("VALIDATION_ERROR", "Seleccione al menos un artículo");
        }
        try {
            Map<Long, Articulos> imprimibles = indiceImprimibles();
            List<Articulos> seleccion = new ArrayList<>();
            Map<Long, Integer> cantidades = new LinkedHashMap<>();
            for (EtiquetaItem item : body.articulos()) {
                if (item == null || item.articuloCodigo() == null) {
                    return badRequest("VALIDATION_ERROR", "El código de artículo es obligatorio");
                }
                int cantidad = item.cantidad() == null ? 1 : item.cantidad();
                if (cantidad < 1) {
                    return badRequest("VALIDATION_ERROR", "La cantidad debe ser mayor a cero");
                }
                Articulos articulo = imprimibles.get(item.articuloCodigo());
                if (articulo == null) {
                    return notFoundJson("No se encontró el artículo solicitado: "
                            + item.articuloCodigo());
                }
                seleccion.add(articulo);
                cantidades.put(articulo.getCodigo(), cantidad);
            }

            byte[] pdf = ReportExporter.exportEtiquetasPdf(seleccion, cantidades);
            String fileName = "etiquetas-" + LocalDateTime.now().format(FILE_STAMP) + ".pdf";
            return Response.ok(pdf)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                    .build();
        } catch (DocumentException e) {
            LOG.log(Level.WARNING, "Error generando las etiquetas", e);
            return serverError("No se pudieron generar las etiquetas");
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error generando las etiquetas", e);
            return serverError("Error generando las etiquetas");
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** codigo → artículo index over the enabled universe (printable set). */
    @Nonnull
    private Map<Long, Articulos> indiceImprimibles() {
        Map<Long, Articulos> indice = new LinkedHashMap<>();
        List<Articulos> articulos = articulosService.ListAllEnabled();
        if (articulos != null) {
            for (Articulos articulo : articulos) {
                indice.put(articulo.getCodigo(), articulo);
            }
        }
        return indice;
    }

    /** Legacy globalFilterFunction parity: codigo/nombre/codigoBarra contains. */
    private static List<Articulos> filtrarArticulos(@Nonnull List<Articulos> source,
                                                    @Nullable String q) {
        if (q == null || q.trim().isEmpty()) {
            return new ArrayList<>(source);
        }
        String filtro = q.trim().toLowerCase(Locale.ROOT);
        List<Articulos> out = new ArrayList<>();
        for (Articulos articulo : source) {
            if (String.valueOf(articulo.getCodigo()).contains(filtro)
                    || (articulo.getNombre() != null
                        && articulo.getNombre().toLowerCase(Locale.ROOT).contains(filtro))
                    || (articulo.getCodigoBarra() != null
                        && articulo.getCodigoBarra().toLowerCase(Locale.ROOT).contains(filtro))) {
                out.add(articulo);
            }
        }
        return out;
    }

    /** Typed comparator dispatch over a whitelisted key set. */
    private static void ordenarArticulos(@Nonnull List<Articulos> articulos, @Nullable String sort,
                                         @Nullable String dir) {
        if (articulos.isEmpty() || sort == null || sort.isBlank()) {
            return;
        }
        Comparator<Articulos> cmp = comparatorFor(sort);
        if (cmp != null) {
            articulos.sort("desc".equalsIgnoreCase(dir) ? cmp.reversed() : cmp);
        }
    }

    @Nullable
    private static Comparator<Articulos> comparatorFor(@Nonnull String sort) {
        return switch (sort) {
            case "codigo" -> Comparator.comparing(Articulos::getCodigo,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case "nombre" -> Comparator.comparing(Articulos::getNombre,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "precio" -> Comparator.comparing(EtiquetasResource::precioFinalDe,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case "familia" -> Comparator.comparing(EtiquetasResource::familiaDe,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            case "departamento" -> Comparator.comparing(EtiquetasResource::departamentoDe,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
            default -> null;
        };
    }

    @Nullable
    private static java.math.BigDecimal precioFinalDe(@Nullable Articulos articulo) {
        // getLastPrecio() NPEs on price-less articles through getLastPrecioArticulo;
        // guard it here (Reportes/EtiquetasResource parity).
        if (articulo == null || articulo.getLastPrecio() == null) {
            return null;
        }
        return articulo.getLastPrecio().getPrecioFinal();
    }

    @Nullable
    private static String familiaDe(@Nullable Articulos articulo) {
        return articulo != null && articulo.getFamilia() != null
                ? articulo.getFamilia().getNombre() : null;
    }

    @Nullable
    private static String departamentoDe(@Nullable Articulos articulo) {
        return articulo != null && articulo.getDepartamento() != null
                ? articulo.getDepartamento().getNombre() : null;
    }

    /** DTO mapper (manual, repo convention) — legacy column set preserved. */
    @Nonnull
    private static EtiquetaDTO toDTO(@Nonnull Articulos articulo, int cantidad) {
        return new EtiquetaDTO(
                articulo.getCodigo(),
                articulo.getNombre(),
                articulo.getCodigoBarra(),
                precioFinalDe(articulo),
                familiaDe(articulo),
                departamentoDe(articulo),
                cantidad);
    }

    private static <T> List<T> orEmpty(@Nullable List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }

    private record Window(int page, int size, int from, int to, int totalPages) {}

    /** Clamped 1-based window over an in-memory result (InventarioResource parity). */
    private static Window windowOf(long total, int page, int size) {
        int s = Math.min(Math.max(size, 1), 100);
        int totalPages = (int) Math.max(1L, (total + s - 1) / s);
        int p = Math.min(Math.max(page, 1), totalPages);
        int from = Math.min((p - 1) * s, (int) total);
        int to = Math.min(from + s, (int) total);
        return new Window(p, s, from, to, totalPages);
    }

    // ── Shared responses ────────────────────────────────────────────────

    // Explicit JSON type: error envelopes must serialize even under the PDF
    // endpoint's octet-stream @Produces.
    private static Response badRequest(@Nonnull String code, @Nonnull String mensaje) {
        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(ApiResponse.error(code, mensaje)).build();
    }

    private static Response notFoundJson(@Nonnull String mensaje) {
        return Response.status(Response.Status.NOT_FOUND)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(ApiResponse.error("NOT_FOUND", mensaje)).build();
    }

    private static Response serverError(@Nonnull String mensaje) {
        return Response.serverError()
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(ApiResponse.error("INTERNAL_ERROR", mensaje)).build();
    }

    // ── Small value carriers ────────────────────────────────────────────

    /** One selected article with its label quantity (JSON request item). */
    public record EtiquetaItem(Long articuloCodigo, Integer cantidad) {}

    /** Generate-labels request body (article selection + per-article qty). */
    public record GeneracionRequest(List<EtiquetaItem> articulos) {}
}
