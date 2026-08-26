package Controllers.Api.App;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import jakarta.annotation.Nonnull;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Serves the NEW-world Qute login page ({@code templates/login.html}) at
 * {@code GET /Mercurius/login} (path relative to {@code quarkus.http.root-path=/Mercurius}).
 *
 * <p>This page backs Quarkus form-based cookie auth (T13 config block): the
 * form-auth mechanism redirects unauthenticated users here, and after failed
 * credentials it redirects back to {@code /Mercurius/login?error}. The
 * {@code error} query flag is read via the standard JAX-RS
 * {@link QueryParam} contract and forwarded to the template so it can render
 * the error banner conditionally.</p>
 *
 * <p>Implementation notes:
 * <ul>
 *   <li>The template is resolved with {@link Location}&#40;"login"&#41; from the
 *       standard {@code src/main/resources/templates} root.</li>
 *   <li>The template is rendered to a {@link String} and returned inside a
 *       {@code text/html} {@link Response}: this project ships
 *       {@code quarkus-qute} (transitively, via quarkus-web-bundler) but NOT
 *       {@code quarkus-rest-qute}, so returning a raw {@code TemplateInstance}
 *       from a Jakarta REST method has no registered message-body writer on
 *       this stack.</li>
 *   <li>Path is public via the T13 permit policy
 *       ({@code quarkus.http.auth.permission.public.paths=/login,...}); no
 *       security annotation is needed.</li>
 * </ul></p>
 */
@Path("/login")
@Tag(name = "App - Auth")
public class LoginPageResource {

    @Inject
    @Nonnull
    @Location("login")
    Template login;

    /**
     * Renders the standalone login page.
     *
     * @param error presence marker appended by the form-auth mechanism's
     *              error-page redirect ({@code /Mercurius/login?error});
     *              any non-blank value turns the banner on
     */
    @GET
    @Produces(MediaType.TEXT_HTML)
    @Operation(summary = "Render the Mercurius login page")
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Login page HTML"),
        @APIResponse(responseCode = "500", description = "Template rendering failure")
    })
    public Response render(@QueryParam("error") @DefaultValue("") @Nonnull String error) {
        boolean showError = !error.isBlank();
        String html = login.data("error", showError).render();
        return Response.ok(html)
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8"))
                .build();
    }
}
