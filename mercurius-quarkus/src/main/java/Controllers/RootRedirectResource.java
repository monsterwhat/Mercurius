package Controllers;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import java.net.URI;

/**
 * Legacy XHTML redirect trap — all old JSF entry points (/index.xhtml,
 * /secured/index.xhtml, /) now redirect to the new Qute/HTMX app at /app.
 * Unauthenticated users hitting /app are bounced to /login by
 * quarkus.http.auth.form automatically, so this single hop covers both cases.
 */
@Path("/")
public class RootRedirectResource {

    private static Response redirectToApp() {
        return Response.seeOther(URI.create("/app/dashboard")).build();
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

    @GET
    @Path("/app")
    public Response app() {
        return redirectToApp();
    }
}
