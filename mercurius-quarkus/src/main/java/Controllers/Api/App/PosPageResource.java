package Controllers.Api.App;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * T37 template phase: serves the Qute POS page
 * ({@code templates/pages/facturas/factura.html}) at {@code GET /app/pos},
 * replacing the legacy JSF {@code secured/pages/Facturas/Facturas/factura.xhtml}.
 *
 * <p>The page composes through the shared {@code layout.html} and embeds the
 * SAME cart-panel model that {@link PosResource#cartPanel()} renders for HTMX
 * swaps (package-private collaboration, single source of truth). The
 * tipo-cambio badge is resolved server-side on first paint; the client picker,
 * payment dialog and supervisor modal load their bodies via hx-get from
 * PosResource fragment endpoints.</p>
 */
@Path("/app/pos")
@RolesAllowed({"admin", "facturacion"})
@Tag(name = "App - POS")
public class PosPageResource {

    @Inject
    @Nonnull
    @Location("pages/facturas/factura")
    Template facturaPage;

    @Inject
    @Nonnull
    PosResource posResource;

    @Inject
    @Nonnull
    SecurityIdentity securityIdentity;

    /**
     * Renders the full POS page: barcode capture, live cart panel, client
     * picker, payment dialog, puntos redemption and facturar/cancel actions.
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Render the POS (factura) page")
    public Response page() {
        String username = currentUsername();
        Map<String, Object> authInicial = new LinkedHashMap<>();
        authInicial.put("exito", false);
        authInicial.put("errorGeneral", null);
        authInicial.put("panelHtml", null);

        Map<String, Object> page = new LinkedHashMap<>();
        page.put("panel", posResource.panelModel(username, null));
        page.put("badge", posResource.tipoCambioBadge());
        page.put("usuario", username);
        page.put("authInicial", authInicial);
        String html = facturaPage.data(page).render();
        return Response.ok(html)
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                .build();
    }

    private @Nullable String currentUsername() {
        if (securityIdentity.isAnonymous() || securityIdentity.getPrincipal() == null) {
            return null;
        }
        return securityIdentity.getPrincipal().getName();
    }
}
