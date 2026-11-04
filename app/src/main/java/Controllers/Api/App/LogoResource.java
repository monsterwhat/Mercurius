package Controllers.Api.App;

import Models.ConfiguracionAplicacion;
import Services.AppSettingsService;
import jakarta.annotation.Nonnull;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.InputStream;
import org.jboss.logging.Logger;

/**
 * Sirve el logo de la empresa en una ruta PÚBLICA ({@code /logo}) para que se
 * vea igual en el login (sin sesión) y en toda la app (sidebar, cabecera).
 *
 * <p>Si no hay logo subido, devuelve el PNG de fábrica empaquetado en
 * {@code META-INF/resources/mercurius-logo.png}. {@code no-store} para que el
 * logo recién subido no se sirva desde caché.</p>
 */
@Path("/logo")
@PermitAll
public class LogoResource {

    private static final Logger LOG = Logger.getLogger(LogoResource.class);
    private static final String FALLBACK = "META-INF/resources/mercurius-logo.png";

    @Inject
    @Nonnull
    AppSettingsService settingsService;

    @GET
    @Produces(MediaType.WILDCARD)
    public Response logo() {
        try {
            ConfiguracionAplicacion settings = settingsService.returnCurrent();
            if (settings != null && settings.getLogo() != null && settings.getLogo().length > 0) {
                String tipo = settings.getLogoMimeType() == null || settings.getLogoMimeType().isBlank()
                        ? "image/png" : settings.getLogoMimeType();
                return Response.ok(settings.getLogo())
                        .type(tipo)
                        .header("Cache-Control", "no-store")
                        .build();
            }
        } catch (RuntimeException e) {
            LOG.warn("No se pudo leer el logo configurado; se usa el de fábrica: "
                    + e.getMessage() + " | source=LogoResource.logo()");
        }
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(FALLBACK)) {
            if (in == null) {
                LOG.warn("Logo de fábrica no encontrado en " + FALLBACK);
                return Response.status(Response.Status.NOT_FOUND).build();
            }
            return Response.ok(in.readAllBytes())
                    .type("image/png")
                    .header("Cache-Control", "no-store")
                    .build();
        } catch (java.io.IOException e) {
            LOG.warn("No se pudo leer el logo de fábrica: " + e.getMessage()
                    + " | source=LogoResource.logo()", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }
    }
}
