package Controllers.Api.App;

import Models.TipoCambio;
import Services.TipoCambioService;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.annotation.Nonnull;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Tipo de Cambio module on the NEW Qute/HTMX app surface — DIALOG-ONLY.
 * There is no standalone page: the shared navbar (fragments/navbar.html) opens
 * a kit modal ({@code modal-tipo-cambio}) whose body is hx-gotten from these
 * fragment endpoints:
 *
 * <ul>
 *   <li>{@code GET /app/tipo-cambio} (and {@code /app/tipo-cambio/fragment}) —
 *       renders {@code pages/tipo-cambio/fragment.html}, the rate card that
 *       fills the modal body.</li>
 *   <li>{@code POST /app/tipo-cambio/actualizar} — re-fetches the rate from
 *       BCCR via {@link TipoCambioService#getNewestTipoCambio()} (guarded to
 *       once per day by the service) and returns the same fragment for an
 *       in-place swap inside the open modal.</li>
 *   <li>{@code GET /app/tipo-cambio/historial} — returns a small table of the
 *       most recent rates for the nested historial modal body.</li>
 * </ul>
 *
 * <p>The rate view model mirrors the POS badge contract
 * ({@code PosResource#tipoCambioBadge()}: {@code disponible/venta/compra/fecha})
 * so the modal and the POS badge stay consistent.</p>
 *
 * <p><b>Role gate</b> mirrors the legacy "Tributacion Area"
 * ({@code admin} + {@code tributacion}).</p>
 */
@Path("/app/tipo-cambio")
@Produces(MediaType.TEXT_HTML)
@RolesAllowed({"admin", "tributacion"})
@Tag(name = "App - Tipo de Cambio")
public class TipoCambioPageResource {

    private static final Logger LOG = Logger.getLogger(TipoCambioPageResource.class.getName());

    /** Cap for the historial modal table. */
    private static final int HISTORIAL_LIMIT = 20;

    @Nonnull
    @Inject
    TipoCambioService tipoCambioService;

    @Nonnull
    @Location("pages/tipo-cambio/fragment")
    @Inject
    Template tipoCambioPage;

    /**
     * Renders the dialog-only fragment with the current BCCR rate (also the
     * body served for {@code POST /actualizar}).
     */
    @GET
    @Operation(summary = "Render the Tipo de Cambio dialog fragment")
    public Response page() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.putAll(tipoCambioModel());
        String html = tipoCambioPage.data(model).render();
        return Response.ok(html)
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                .build();
    }

    /**
     * Explicit fragment-alias route so the navbar modal's bodyUrl stays
     * self-documenting even after the standalone page is gone.
     */
    @GET
    @Path("/fragment")
    @Operation(summary = "Render the Tipo de Cambio dialog fragment (alias)")
    public Response fragment() {
        return page();
    }

    /**
     * Re-fetches the current rate from BCCR and returns the updated
     * fragment for an in-place HTMX swap inside the open modal.
     */
    @POST
    @Path("/actualizar")
    @Operation(summary = "Re-fetch the current BCCR dollar rate and return the updated fragment")
    public Response actualizar() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.putAll(tipoCambioModel());
        String html = tipoCambioPage.data(model).render();
        return Response.ok(html)
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                .build();
    }

    /**
     * Returns a small table of the most recent rates for the historial modal.
     */
    @GET
    @Path("/historial")
    @Operation(summary = "Recent tipo de cambio history for the modal body")
    public Response historial() {
        List<TipoCambio> todos = tipoCambioService.listAll();
        List<Map<String, Object>> filas = new ArrayList<>();
        if (todos != null) {
            int desde = Math.max(0, todos.size() - HISTORIAL_LIMIT);
            for (int i = todos.size() - 1; i >= desde; i--) {
                TipoCambio tc = todos.get(i);
                Map<String, Object> fila = new LinkedHashMap<>();
                fila.put("fecha", tc.getFecha() != null ? tc.getFecha().toString() : "—");
                fila.put("compra", tc.getValorCompra());
                fila.put("venta", tc.getValorVenta());
                filas.add(fila);
            }
        }
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("filas", filas);
        String html = historialTemplate.data(model).render();
        return Response.ok(html)
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                .build();
    }

    /**
     * View model for the current rate; mirrors the POS badge contract so the
     * page and the POS badge stay consistent. Nullable fields when no rate is
     * available yet.
     */
    @Nonnull
    private Map<String, Object> tipoCambioModel() {
        TipoCambio tc = null;
        try {
            tc = tipoCambioService.getNewestTipoCambio();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "tipo-cambio page unavailable", e);
        }
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("disponible", tc != null);
        model.put("venta", tc != null ? tc.getValorVenta() : null);
        model.put("compra", tc != null ? tc.getValorCompra() : null);
        model.put("fecha", tc != null && tc.getFecha() != null ? tc.getFecha().toString() : null);
        return model;
    }

    @Nonnull
    @Location("pages/tipo-cambio/historial")
    @Inject
    Template historialTemplate;
}
