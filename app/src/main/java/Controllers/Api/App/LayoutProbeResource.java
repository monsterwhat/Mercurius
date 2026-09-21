package Controllers.Api.App;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Map;

/**
 * Scaffolding smoke-probe that renders the new base Qute layout TODAY, while
 * the declarative auth policies are still dormant (plan task T11).
 *
 * <p>Serves {@code GET /app/_probe} (full URL {@code /Mercurius/app/_probe}
 * under the configured {@code quarkus.http.root-path}) returning
 * {@code templates/pages/probe.html}, which includes the shared
 * {@code templates/layout.html}: role-gated navbar fragment, toast container
 * fed with one sample notification per severity, footer and the web-bundler
 * bundle tags.</p>
 *
 * <p><b>Why it can be reached before login exists:</b> the
 * {@code quarkus.http.auth.permission.*} block that will guard
 * {@code /app/*} and {@code /api/app/*} is still commented out in
 * application.properties (tasks T13/T14 own its activation), so this route is
 * anonymous for now. Once those tasks land, the probe simply becomes an
 * authenticated smoke page like any other app route.</p>
 *
 * @deprecated Temporary scaffolding from plan task T11. DELETE this class
 *             together with {@code templates/pages/probe.html} in wave W4,
 *             as soon as real migrated pages render through the same layout.
 */
@Deprecated
@Path("/app/_probe")
public class LayoutProbeResource {

    /**
     * Shape of the toast entries expected by
     * {@code templates/fragments/toasts.html}: a map exposing
     * {@code severity} (error|warn|info|success) and {@code message}.
     * Built-in Qute map resolvers cover the dot access used by the fragment.
     */
    private static final List<Map<String, String>> SAMPLE_TOASTS = List.of(
            Map.of("severity", "info",
                    "message", "Sonda de renderizado: el layout base de Mercurius está operativo."),
            Map.of("severity", "success",
                    "message", "Éxito: las notificaciones Bulma se muestran y se cierran solas."),
            Map.of("severity", "warn",
                    "message", "Advertencia: la autenticación permanece dormante hasta la tarea T14."),
            Map.of("severity", "error",
                    "message", "Error de ejemplo: así se ve una alerta is-danger en la sonda."));

    @Inject
    @Location("pages/probe.html")
    Template probe;

    /**
     * Renders the layout with the navbar and toast fragments plus sample data.
     *
     * @return the probe page as HTML
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance renderLayout() {
        return probe.data("toasts", SAMPLE_TOASTS);
    }
}
