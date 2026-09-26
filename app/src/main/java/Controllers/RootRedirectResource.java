package Controllers;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import java.net.URI;

/**
 * Legacy XHTML redirect trap — old JSF entry points (/index.xhtml,
 * /secured/index.xhtml) now redirect to the new Qute/HTMX app.
 * Locations carry the /Mercurius root-path prefix verbatim: JAX-RS redirect
 * targets are emitted as-is (no prefix is added), so unprefixed targets 404.
 * Bare "/" itself never reaches this resource (outside the app router) —
 * see RootLoginRedirectRoute for that hop.
 *
 * <p>Every hop answers a single seeOther to the dashboard, for anonymous and
 * authenticated callers alike: "/" and "/app" are permitted by the public
 * permission policy precisely so THIS resource runs (otherwise form auth
 * answers its own 302 challenge first). An anonymous visitor is then bounced
 * to /login by the still-secured /app/* policy, carrying the
 * quarkus-redirect-location cookie, so the deep link is replayed after login
 * — the same bounce-back contract the login journey asserts.
 */
@Path("/")
public class RootRedirectResource {

    private Response redirectToApp() {
        return Response.seeOther(URI.create("/Mercurius/app/dashboard")).build();
    }

    @GET
    public Response root() {
        return redirectToApp();
    }

    @GET
    @Path("/index.xhtml")
    public Response indexXhtml() {
        return redirectToApp();
    }

    @GET
    @Path("/index.html")
    public Response indexHtml() {
        return redirectToApp();
    }

    @GET
    @Path("/secured/index.xhtml")
    public Response securedIndex() {
        return redirectToApp();
    }
}
