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
 * Unauthenticated users hitting /app are bounced to /login by
 * quarkus.http.auth.form automatically, so this single hop covers both cases.
 */
@Path("/")
public class RootRedirectResource {

    private static Response redirectToApp() {
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
